package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.model.ArchitectBrief;
import com.business.discovery.worker.model.BusinessEntity;
import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.BriefContext;
import com.business.discovery.worker.service.llm.FeatureSpec;
import com.business.discovery.worker.service.llm.FileEntry;
import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.ProjectDependencies;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.business.discovery.worker.service.GitService;
import com.business.discovery.worker.service.SpringInitializrClient;
import com.business.discovery.worker.util.ArchitectureJsonUtil;
import com.business.discovery.worker.util.SlugUtil;
import com.business.discovery.worker.util.WorkspaceReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
@Order(5)
@Slf4j
public class ProjectPlanningNode implements WorkerNode {

    private static final int PROCESS_TIMEOUT_MINUTES = 5;

    // "mail" is included by default: briefs routinely produce Notification/Lead features whose
    // services autowire JavaMailSender — without the starter the app compiles but fails to boot
    // (caught by the smoke boot gate on multifit-aundh, 2026-07-04).
    private static final List<String> DEFAULT_SPRING_STARTERS =
            List.of("web", "data-jpa", "postgresql", "lombok", "validation", "actuator", "security", "mail");

    // zod pinned to v3: v4 makes z.coerce.number() input type `unknown`, which breaks the
    // @hookform/resolvers v5 Resolver generic — an unfixable-by-prompt error class that sank
    // the yeti-himalayan-kitchen frontend build (2026-07-07). Prompt guidance in file_generate.txt
    // and feature_enrichment.txt did not prevent it; the version pin is the real defense.
    private static final List<String> DEFAULT_NPM_PACKAGES =
            List.of("@tanstack/react-query", "react-hook-form", "zod@^3", "axios", "react-router-dom");

    // The platform stack is fixed — briefs describe the business, never the technology.
    // A brief-supplied stack (e.g. "Next.js frontend") once reached the planning prompt and
    // made the model return an empty spec; see docs/llm evidence on multifit-aundh attempt 1-4.
    private static final Map<String, String> PLATFORM_TECH_STACK = Map.of(
            "frontend", "React 19 + TypeScript on Vite, react-router-dom, Tailwind CSS",
            "backend", "Spring Boot 3 (Java 17) + Spring Data JPA",
            "database", "PostgreSQL");

    // Frameworks that conflict with the platform stack — never installed even if the spec asks
    private static final Set<String> FORBIDDEN_NPM_PACKAGES = Set.of(
            "next", "nuxt", "gatsby", "remix", "@remix-run/react", "svelte", "vue",
            "@angular/core", "express", "@nestjs/core");

    private final LlmGeneratorService llm;        // Pro — used for architecture spec generation only
    private final LlmGeneratorService enrichLlm;  // Flash — used for per-feature enrichment
    private final SpringInitializrClient initializrClient;
    private final GitService gitService;

    public ProjectPlanningNode(@Qualifier("geminiPro") LlmGeneratorService llm,
                               @Qualifier("geminiFlash") LlmGeneratorService enrichLlm,
                               SpringInitializrClient initializrClient,
                               GitService gitService) {
        this.llm = llm;
        this.enrichLlm = enrichLlm;
        this.initializrClient = initializrClient;
        this.gitService = gitService;
    }

    @Override
    public void execute(WorkerContext ctx) {
        BusinessEntity business = ctx.getBusiness();
        ArchitectBrief brief = ctx.getBrief();
        String slug = SlugUtil.toSlug(business.getTitle());
        Path workspace = ctx.getWorkspaceDir();

        BriefContext briefCtx = buildBriefContext(brief, business, ctx.getProjectHistory());
        ctx.setBriefCtx(briefCtx);

        // ── Decide what to skip ───────────────────────────────────────────────
        boolean hasChanges = briefCtx.requestedChanges() != null && !briefCtx.requestedChanges().isBlank();
        // Load existing spec whenever it exists — even for requestedChanges runs.
        // Changes are handled via enrichment (which sets change_required per file),
        // not by regenerating the entire spec from scratch.
        boolean archSpecExists = workspace != null && ArchitectureJsonUtil.exists(workspace);

        ArchitectureSpec spec = null;
        boolean skipGeneration = false;
        boolean skipEnrichment = false;

        if (archSpecExists) {
            try {
                ArchitectureSpec existing = ArchitectureJsonUtil.read(workspace);
                boolean hasFiles = existing.getFiles() != null && !existing.getFiles().isEmpty();
                if (hasFiles) {
                    skipGeneration = true;
                    // Skip enrichment only when no changes requested AND all non-INFRA features already enriched
                    skipEnrichment = !hasChanges && allFeaturesHaveInstructions(existing);
                    spec = existing;
                    log.info("[ProjectPlanningNode] ARCHITECTURE.json loaded — skipGeneration={} skipEnrichment={}",
                            skipGeneration, skipEnrichment);
                } else {
                    // Empty spec left by old code or a failed first run — regenerate from scratch
                    log.warn("[ProjectPlanningNode] Existing ARCHITECTURE.json has no files — regenerating spec");
                }
            } catch (IOException e) {
                log.warn("[ProjectPlanningNode] Could not read existing spec, replanning: {}", e.getMessage());
            }
        }

        // ── Change targeting: resolve the client's request to specific features + files ──
        // This is the update path's ONLY way to route the request into generation: enrichment
        // does not re-run on update runs (its per-feature resume guard skips every enriched
        // feature), so without this pass change_required keeps its stale persisted marks and
        // the request text never reaches a generation prompt.
        if (hasChanges && skipGeneration && spec != null) {
            enrichLlm.targetChanges(spec, briefCtx);
            try {
                ArchitectureJsonUtil.write(workspace, spec);
            } catch (IOException e) {
                throw new WorkerException(FailureType.INFRA,
                        "Checkpoint write failed after change targeting: " + e.getMessage(), e);
            }
        }

        // ── Outline generation (retried in-process inside generateArchitectureSpec) ──
        if (!skipGeneration) {
            spec = llm.generateArchitectureSpec(briefCtx, slug);
            int fileCount = spec.getFiles() == null ? 0 : spec.getFiles().size();
            // Belt-and-suspenders: the retry loop already guards this
            if (fileCount == 0) {
                throw new WorkerException(FailureType.CODE,
                        "Architecture outline has 0 files after retries — check model response in docs/llm/interactions.jsonl");
            }
        }

        // ── Enrich: one Pro call per feature — bounded output, resumable per-feature ──
        if (!skipEnrichment) {
            enrichFeatures(spec, briefCtx, workspace, ctx.getGithubBranch());
            try {
                writeEnrichmentDoc(workspace, spec, ctx.getAttemptNumber());
            } catch (IOException e) {
                log.warn("[ProjectPlanningNode] Could not write enrichment doc: {}", e.getMessage());
            }
        }

        // ── Cross-reference validation: add stub entries for missing imports ──
        spec = validateCrossReferences(spec);

        // ── Merge: preserve VALIDATED status from prior run ───────────────────
        spec = mergeWithExisting(spec, workspace);

        ctx.setFileManifest(toManifest(spec));

        try {
            // Scaffold only on first run — on retry the cloned repo already has these
            if (!Files.exists(workspace.resolve("backend/pom.xml"))) {
                ProjectDependencies deps = resolveDependencies(spec);
                scaffoldSpringBoot(workspace, slug, business.getTitle(), deps.getSpringBootStarters(),
                        deps.getMavenDependencies());
                scaffoldVite(workspace, deps.getNpmPackages());
                writeDockerArtifacts(workspace, slug);
                log.info("[ProjectPlanningNode] CLI scaffold complete for '{}'", business.getTitle());
            } else {
                // Scaffold was already done on a prior attempt. Remove any Application.java
                // that landed in a wrong sub-package (happens when packageName param was missing).
                // Expected location: backend/src/main/java/com/<slug>/<ArtifactId>Application.java
                Path expectedPackageDir = workspace.resolve("backend/src/main/java/com/" + slug);
                Path javaRoot = workspace.resolve("backend/src/main/java");
                if (Files.exists(javaRoot)) {
                    try (var walk = Files.walk(javaRoot)) {
                        walk.filter(p -> p.getFileName().toString().endsWith("Application.java"))
                            .filter(p -> !p.getParent().equals(expectedPackageDir))
                            .forEach(p -> {
                                try {
                                    Files.delete(p);
                                    log.info("[ProjectPlanningNode] Removed stale Application.java at wrong package: {}", p);
                                } catch (IOException e) {
                                    log.warn("[ProjectPlanningNode] Could not remove stale Application.java: {}", p);
                                }
                            });
                    }
                }
            }

            ArchitectureJsonUtil.write(workspace, spec);
            writeHistoryStub(workspace, ctx, spec);

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkerException(FailureType.INFRA, "Scaffold failed: " + e.getMessage(), e);
        }
    }

    // ── Spring Boot scaffold via Spring Initializr ────────────────────────

    private void scaffoldSpringBoot(Path workspace, String slug, String businessName,
                                    List<String> starters,
                                    List<com.business.discovery.worker.service.llm.MavenCoordinate> specDeclaredDeps) throws IOException {
        // Validate dependency IDs against Initializr's real list — drops LLM hallucinations
        List<String> validStarters = initializrClient.filterValidDependencies(starters);
        String deps = String.join(",", validStarters);

        // Use the API's current default boot version — never hardcode a version that can go EOL
        String bootVersion = initializrClient.getDefaultBootVersion();
        String versionParam = (bootVersion != null && !bootVersion.isBlank())
                ? "&bootVersion=" + bootVersion : "";

        String url = "https://start.spring.io/starter.zip"
                + "?type=maven-project&language=java"
                + versionParam
                + "&baseDir=backend"
                + "&groupId=com." + slug
                + "&artifactId=" + slug + "-backend"
                + "&name=" + slug + "backend"
                + "&packageName=com." + slug
                + "&dependencies=" + deps;

        log.info("[ProjectPlanningNode] Downloading Spring Initializr: {}", url);

        try (InputStream in = URI.create(url).toURL().openStream();
             ZipInputStream zip = new ZipInputStream(in)) {

            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = workspace.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }

        // mvnw must be executable for BackendValidationNode
        workspace.resolve("backend/mvnw").toFile().setExecutable(true);

        // Inject JWT library deps — not available via Spring Initializr, added directly to pom.xml
        injectJwtDependencies(workspace.resolve("backend/pom.xml"));

        // Inject any extra Maven deps declared by the planning LLM in the spec
        if (specDeclaredDeps != null && !specDeclaredDeps.isEmpty()) {
            injectSpecDeclaredDeps(workspace.resolve("backend/pom.xml"), specDeclaredDeps);
        }

        // Override the generated application.properties.
        // jwt.secret uses ${JWT_SECRET:<default>} so the app starts in demo without any env vars set.
        // The default is a 512-bit Base64 key — long enough for JJWT HS256/HS512. Production must
        // override JWT_SECRET with a real secret.
        Files.writeString(workspace.resolve("backend/src/main/resources/application.properties"), """
                spring.application.name=%s
                server.port=8080
                spring.datasource.url=${DB_URL}
                spring.datasource.username=${DB_USERNAME}
                spring.datasource.password=${DB_PASSWORD}
                spring.jpa.hibernate.ddl-auto=update
                spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect
                management.endpoints.web.exposure.include=health,info
                jwt.secret=${JWT_SECRET:ZGVtby1vbmx5LXNlY3JldC1rZXktZm9yLWxvY2FsLXRlc3RpbmctY2hhbmdlLWluLXByb2R1Y3Rpb24tZW52aXJvbm1lbnQ=}
                jwt.expiration-ms=${JWT_EXPIRATION_MS:86400000}
                admin.email=${ADMIN_EMAIL:admin@example.com}
                admin.password=${ADMIN_PASSWORD:admin123}
                spring.mail.host=${SMTP_HOST:localhost}
                spring.mail.port=${SMTP_PORT:587}
                spring.mail.username=${SMTP_USERNAME:}
                spring.mail.password=${SMTP_PASSWORD:}
                management.health.mail.enabled=false
                """.formatted(slug));

        // Write canonical SpaController — forwards all non-file, non-API routes to index.html
        // so React Router handles client-side navigation on direct URL access or refresh.
        // Covers 1, 2 and 3-segment paths: /menu, /admin/dashboard, /order-confirmation/123
        Path controllerDir = workspace.resolve("backend/src/main/java/com/" + slug + "/controller");
        Files.createDirectories(controllerDir);
        Files.writeString(controllerDir.resolve("SpaController.java"), """
                package com.%s.controller;

                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.GetMapping;

                @Controller
                public class SpaController {

                    @GetMapping(value = {
                        "/{path:[^\\\\.]*}",
                        "/{path1:[^\\\\.]*}/{path2:[^\\\\.]*}",
                        "/{path1:[^\\\\.]*}/{path2:[^\\\\.]*}/{path3:[^\\\\.]*}"
                    })
                    public String fallback() {
                        return "forward:/index.html";
                    }
                }
                """.formatted(slug));

        log.info("[ProjectPlanningNode] Spring Boot scaffold extracted with starters: {}", starters);
    }

    // ── React scaffold via Vite CLI ───────────────────────────────────────

    private void scaffoldVite(Path workspace, List<String> extraPackages)
            throws IOException, InterruptedException {

        // npm create vite@latest frontend -- --template react-ts
        run(workspace, "npm", "create", "vite@latest", "frontend", "--", "--template", "react-ts");

        Path frontend = workspace.resolve("frontend");

        // Install base deps declared in the generated package.json
        run(frontend, "npm", "install");

        // Overwrite generated config files with canonical templates immediately after scaffold.
        // This ensures the committed versions are always correct — no patching on later runs.
        writeCanonicalFrontendConfigs(frontend);

        // Install project-specific packages decided by the planning LLM.
        // Filter out GitHub-style "owner/repo.js" references — not valid npm package names.
        // Scoped packages like @tanstack/react-query start with '@' and are always valid.
        List<String> validPackages = extraPackages.stream()
                .filter(pkg -> !pkg.contains("/") || pkg.startsWith("@"))
                .toList();

        if (!validPackages.isEmpty()) {
            List<String> installCmd = new ArrayList<>(List.of("npm", "install"));
            installCmd.addAll(validPackages);
            run(frontend, installCmd.toArray(new String[0]));
        }

        if (validPackages.size() != extraPackages.size()) {
            List<String> rejected = extraPackages.stream()
                    .filter(pkg -> pkg.contains("/") && !pkg.startsWith("@"))
                    .toList();
            log.warn("[ProjectPlanningNode] Skipped invalid npm packages (use CDN instead): {}", rejected);
        }

        log.info("[ProjectPlanningNode] Vite scaffold complete, installed: {}", validPackages);
    }

    private void writeCanonicalFrontendConfigs(Path frontendDir) throws IOException {
        Path viteConfig = frontendDir.resolve("vite.config.ts");
        if (Files.exists(viteConfig)) {
            Files.writeString(viteConfig, CANONICAL_VITE_CONFIG);
            log.info("[ProjectPlanningNode] Wrote canonical vite.config.ts");
        }
        Path tsconfigApp = frontendDir.resolve("tsconfig.app.json");
        if (Files.exists(tsconfigApp)) {
            Files.writeString(tsconfigApp, CANONICAL_TSCONFIG_APP);
            log.info("[ProjectPlanningNode] Wrote canonical tsconfig.app.json");
        }
        Path tsconfigRoot = frontendDir.resolve("tsconfig.json");
        if (Files.exists(tsconfigRoot)) {
            Files.writeString(tsconfigRoot, CANONICAL_TSCONFIG_ROOT);
            log.info("[ProjectPlanningNode] Wrote canonical tsconfig.json");
        }
    }

    private static final String CANONICAL_VITE_CONFIG = """
            import path from 'node:path'
            import { defineConfig } from 'vite'
            import react from '@vitejs/plugin-react'

            export default defineConfig({
              plugins: [react()],
              resolve: {
                alias: { '@': path.resolve(__dirname, './src') },
              },
              server: {
                proxy: {
                  '/api': {
                    target: 'http://localhost:8080',
                    changeOrigin: true,
                  },
                },
              },
              build: {
                outDir: 'dist',
              },
            })
            """;

    private static final String CANONICAL_TSCONFIG_APP = """
            {
              "compilerOptions": {
                "target": "ES2020",
                "useDefineForClassFields": true,
                "lib": ["ES2020", "DOM", "DOM.Iterable"],
                "module": "ESNext",
                "skipLibCheck": true,
                "moduleResolution": "bundler",
                "allowImportingTsExtensions": true,
                "isolatedModules": true,
                "moduleDetection": "force",
                "noEmit": true,
                "jsx": "react-jsx",
                "strict": true,
                "noUnusedLocals": false,
                "noUnusedParameters": false,
                "noFallthroughCasesInSwitch": true,
                "baseUrl": ".",
                "paths": { "@/*": ["./src/*"] }
              },
              "include": ["src"]
            }
            """;

    private static final String CANONICAL_TSCONFIG_ROOT = """
            {
              "files": [],
              "references": [
                { "path": "./tsconfig.app.json" },
                { "path": "./tsconfig.node.json" }
              ],
              "compilerOptions": {
                "baseUrl": ".",
                "paths": { "@/*": ["./src/*"] }
              }
            }
            """;

    // ── JWT pom injection ─────────────────────────────────────────────────

    private void injectJwtDependencies(Path pomPath) throws IOException {
        if (!Files.exists(pomPath)) {
            log.warn("[ProjectPlanningNode] pom.xml not found at {} — skipping JWT injection", pomPath);
            return;
        }

        String pom = Files.readString(pomPath);
        if (pom.contains("jjwt-api")) {
            log.info("[ProjectPlanningNode] JWT deps already present in pom.xml");
            return;
        }

        String jwtDeps = """
                \t\t<dependency>
                \t\t\t<groupId>io.jsonwebtoken</groupId>
                \t\t\t<artifactId>jjwt-api</artifactId>
                \t\t\t<version>0.11.5</version>
                \t\t</dependency>
                \t\t<dependency>
                \t\t\t<groupId>io.jsonwebtoken</groupId>
                \t\t\t<artifactId>jjwt-impl</artifactId>
                \t\t\t<version>0.11.5</version>
                \t\t\t<scope>runtime</scope>
                \t\t</dependency>
                \t\t<dependency>
                \t\t\t<groupId>io.jsonwebtoken</groupId>
                \t\t\t<artifactId>jjwt-jackson</artifactId>
                \t\t\t<version>0.11.5</version>
                \t\t\t<scope>runtime</scope>
                \t\t</dependency>
                """;

        // Insert before the closing </dependencies> tag
        String patched = pom.replace("</dependencies>", jwtDeps + "\t</dependencies>");
        Files.writeString(pomPath, patched);
        log.info("[ProjectPlanningNode] JWT dependencies injected into pom.xml");
    }

    private void injectSpecDeclaredDeps(Path pomPath,
            List<com.business.discovery.worker.service.llm.MavenCoordinate> deps) throws IOException {
        if (!Files.exists(pomPath)) return;
        String pom = Files.readString(pomPath);
        StringBuilder toAdd = new StringBuilder();
        for (var dep : deps) {
            if (dep.getGroupId() == null || dep.getArtifactId() == null) continue;
            String tag = "<artifactId>" + dep.getArtifactId() + "</artifactId>";
            if (pom.contains(tag) || toAdd.toString().contains(tag)) continue;
            toAdd.append("\t\t<dependency>\n")
                    .append("\t\t\t<groupId>").append(dep.getGroupId()).append("</groupId>\n")
                    .append("\t\t\t<artifactId>").append(dep.getArtifactId()).append("</artifactId>\n");
            if (dep.getVersion() != null) toAdd.append("\t\t\t<version>").append(dep.getVersion()).append("</version>\n");
            if (dep.getScope() != null && !dep.getScope().isBlank()) toAdd.append("\t\t\t<scope>").append(dep.getScope()).append("</scope>\n");
            toAdd.append("\t\t</dependency>\n");
            log.info("[ProjectPlanningNode] Injected spec-declared dep: {}:{}", dep.getGroupId(), dep.getArtifactId());
        }
        if (toAdd.isEmpty()) return;
        String patched = pom.replace("</dependencies>", toAdd + "\t</dependencies>");
        Files.writeString(pomPath, patched);
    }

    // ── Docker artifacts (Dockerfile, docker-compose, .env.example) ──────

    private void writeDockerArtifacts(Path workspace, String slug) throws IOException {
        Files.writeString(workspace.resolve("Dockerfile"), """
                FROM node:20-alpine AS frontend-build
                WORKDIR /app/frontend
                COPY frontend/package*.json ./
                RUN npm install
                COPY frontend/ .
                RUN npm run build

                FROM maven:3.9-eclipse-temurin-17 AS backend-build
                WORKDIR /app
                COPY backend/ .
                COPY --from=frontend-build /app/frontend/dist ./src/main/resources/static
                RUN mvn package -q -DskipTests

                FROM eclipse-temurin:17-jre-jammy
                RUN groupadd -r app && useradd -r -g app app
                WORKDIR /app
                COPY --from=backend-build /app/target/*.jar app.jar
                USER app
                EXPOSE 8080
                HEALTHCHECK --interval=30s --timeout=5s CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1
                ENTRYPOINT ["java", "-jar", "app.jar"]
                """);

        Files.writeString(workspace.resolve("docker-compose.yml"), """
                version: "3.9"
                services:
                  app:
                    build: .
                    ports:
                      - "8080:8080"
                    env_file: .env
                    restart: unless-stopped
                    depends_on:
                      db:
                        condition: service_healthy
                    healthcheck:
                      # temurin-jammy has no wget/curl — probe the port with bash's /dev/tcp instead
                      test: ["CMD-SHELL", "bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080'"]
                      interval: 30s
                      retries: 3
                  db:
                    image: postgres:16-alpine
                    env_file: .env
                    volumes:
                      - pgdata:/var/lib/postgresql/data
                    healthcheck:
                      test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER}"]
                      interval: 10s
                      retries: 5
                    restart: unless-stopped
                volumes:
                  pgdata:
                """);

        Files.writeString(workspace.resolve(".env.example"), """
                DB_URL=jdbc:postgresql://db:5432/%s
                DB_USERNAME=postgres
                DB_PASSWORD=changeme
                POSTGRES_DB=%s
                POSTGRES_USER=postgres
                POSTGRES_PASSWORD=changeme
                VITE_API_URL=http://localhost:8080
                JWT_SECRET=change-this-to-a-secure-random-256-bit-secret
                JWT_EXPIRATION_MS=86400000
                ADMIN_EMAIL=owner@yourbusiness.com
                ADMIN_PASSWORD=changeme123
                SMTP_HOST=localhost
                SMTP_PORT=587
                SMTP_USERNAME=
                SMTP_PASSWORD=
                """.formatted(slug, slug));

        Files.writeString(workspace.resolve(".gitignore"), """
                .env
                .smoke/
                docs/llm/
                target/
                node_modules/
                dist/
                *.class
                .idea/
                .DS_Store
                """);
    }

    // ── ProcessBuilder helper ─────────────────────────────────────────────

    private void run(Path dir, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(dir.toFile())
                .redirectErrorStream(true);
        pb.environment().put("CI", "true");

        log.info("[ProjectPlanningNode] Running: {}", String.join(" ", cmd));
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());

        boolean finished = process.waitFor(PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new WorkerException(FailureType.INFRA,
                    "Timed out after " + PROCESS_TIMEOUT_MINUTES + "m: " + String.join(" ", cmd));
        }
        if (process.exitValue() != 0) {
            throw new WorkerException(FailureType.INFRA,
                    "Command failed (exit " + process.exitValue() + "): "
                            + String.join(" ", cmd) + "\n" + output.lines().limit(20).collect(Collectors.joining("\n")));
        }
        log.debug("[ProjectPlanningNode] Output: {}", output.lines().limit(5).collect(Collectors.joining("; ")));
    }

    // ── Enrichment doc ────────────────────────────────────────────────────

    private void writeEnrichmentDoc(Path workspace, ArchitectureSpec spec, int attempt)
            throws IOException {
        Path docsDir = workspace.resolve("docs");
        Files.createDirectories(docsDir);

        Map<String, List<FileSpec>> filesByFeature = buildFilesByFeature(spec);

        StringBuilder md = new StringBuilder();
        md.append("# Feature Enrichment — Attempt ").append(attempt).append("\n\n");
        md.append("Generated: ").append(LocalDate.now()).append("\n\n");
        md.append("Each section is one LLM call (~5–8K tokens). ")
          .append("The instruction tells the generator how all files in the feature ")
          .append("interact and what contracts they must honour.\n\n");
        md.append("---\n\n");

        if (spec.getFeatures() == null || spec.getFeatures().isEmpty()) {
            md.append("_No features found in spec._\n");
        } else {
            for (FeatureSpec feature : spec.getFeatures()) {
                md.append("## ").append(feature.getFeatureDisplayName() != null
                        ? feature.getFeatureDisplayName() : feature.getFeatureName()).append("\n\n");
                md.append("**Name:** `").append(feature.getFeatureName()).append("`  \n");
                md.append("**Type:** ").append(feature.getFeatureType()).append("  \n");
                md.append("**Change required:** ").append(feature.isChangeRequired()).append("\n\n");

                List<FileSpec> featureFiles = filesByFeature.getOrDefault(feature.getFeatureName(), List.of());
                if (!featureFiles.isEmpty()) {
                    md.append("**Files in this feature:**\n");
                    for (FileSpec f : featureFiles) {
                        md.append("- `").append(f.getFilePath()).append("`");
                        if (f.getFileRole() != null && !f.getFileRole().isBlank()) {
                            md.append(" — ").append(f.getFileRole());
                        }
                        md.append("\n");
                    }
                    md.append("\n");
                }

                md.append("**Feature Instruction:**\n\n");
                if (feature.getFeatureInstruction() != null && !feature.getFeatureInstruction().isBlank()) {
                    md.append(feature.getFeatureInstruction()).append("\n\n");
                } else {
                    md.append("_Not enriched (INFRA or skipped)._\n\n");
                }
                md.append("---\n\n");
            }
        }

        Files.writeString(docsDir.resolve("ENRICHMENT.md"), md.toString());
        log.info("[ProjectPlanningNode] ENRICHMENT.md written to docs/");
    }

    // ── History stub ──────────────────────────────────────────────────────

    private void writeHistoryStub(Path workspace, WorkerContext ctx, ArchitectureSpec spec)
            throws IOException {
        Path historyPath = workspace.resolve("docs/PROJECT_HISTORY.md");
        Files.createDirectories(historyPath.getParent());

        String stub = """
                ## Attempt %d — %s [IN PROGRESS]

                **Business:** %s
                **Planned Files (%d):**
                %s

                ---
                """.formatted(
                ctx.getAttemptNumber(),
                LocalDate.now(),
                ctx.getBusiness().getTitle(),
                spec.getFiles().size(),
                spec.getFiles().stream().map(f -> "- " + f.getFilePath()).collect(Collectors.joining("\n")));

        if (Files.exists(historyPath)) {
            Files.writeString(historyPath, Files.readString(historyPath) + "\n" + stub);
        } else {
            Files.writeString(historyPath, "# Project History\n\nThis file tracks each generation attempt.\n\n" + stub);
        }
    }

    // ── Planning helpers ──────────────────────────────────────────────────

    private boolean allFeaturesHaveInstructions(ArchitectureSpec spec) {
        if (spec.getFeatures() == null || spec.getFeatures().isEmpty()) return false;
        return spec.getFeatures().stream()
                .filter(f -> !"INFRA".equalsIgnoreCase(f.getFeatureType()))
                .allMatch(f -> f.getFeatureInstruction() != null
                        && !f.getFeatureInstruction().isBlank());
    }

    private void enrichFeatures(ArchitectureSpec spec, BriefContext briefCtx, Path workspace, String branch) {
        List<FeatureSpec> features = spec.getFeatures();
        if (features == null || features.isEmpty()) {
            log.warn("[ProjectPlanningNode] No features in spec — enrichment skipped. " +
                     "Regenerate the spec to assign feature groupings.");
            return;
        }

        Map<String, List<FileSpec>> filesByFeature = buildFilesByFeature(spec);

        WorkspaceReader workspaceReader = new WorkspaceReader(workspace);

        // Enrich BACKEND features first (then SHARED, then FRONTEND): enrichment now fills
        // the per-file heavy detail (publicFunctions/apiEndpoints) that the outline omits,
        // so frontend features see REAL backend API summaries instead of empty stubs.
        // enrichFeature mutates the shared FeatureSpec/FileSpec objects, so the lazy
        // per-iteration peer summaries below pick up detail from earlier iterations.
        List<FeatureSpec> ordered = new ArrayList<>(features);
        ordered.sort(Comparator.comparingInt(f -> enrichmentPriority(f.getFeatureType())));

        for (FeatureSpec feature : ordered) {

            if ("INFRA".equalsIgnoreCase(feature.getFeatureType())) {
                log.info("[ProjectPlanningNode] Skipping INFRA feature: {}", feature.getFeatureName());
                continue;
            }

            if (feature.getFeatureInstruction() != null && !feature.getFeatureInstruction().isBlank()) {
                log.info("[ProjectPlanningNode] Feature already enriched, skipping: {}", feature.getFeatureName());
                continue;
            }

            List<FileSpec> featureFiles = filesByFeature.getOrDefault(feature.getFeatureName(), List.of());
            if (featureFiles.isEmpty()) {
                log.warn("[ProjectPlanningNode] Feature {} has no matching files — skipping", feature.getFeatureName());
                continue;
            }

            // Lazy: reflects the heavy detail features enriched earlier in this loop just filled in
            Map<String, Map<String, Object>> allPeerSummaries = buildAllPeerSummaries(features, filesByFeature);
            Map<String, Object> peerSummaries = buildPeerSummariesExcluding(allPeerSummaries, feature.getFeatureName());

            log.info("[ProjectPlanningNode] Enriching feature: {} ({} files)",
                    feature.getFeatureName(), featureFiles.size());

            // WorkerException (CODE/INFRA) propagates immediately — no catch-and-swallow
            // (enrichFeature mutates `feature` in place; it remains referenced by spec.features)
            enrichLlm.enrichFeature(feature, featureFiles, peerSummaries, briefCtx, workspaceReader);

            // Checkpoint after every feature — enables resume on container retry
            try {
                ArchitectureJsonUtil.write(workspace, spec);
                log.info("[ProjectPlanningNode] Checkpoint written after enriching: {}", feature.getFeatureName());
            } catch (IOException e) {
                throw new WorkerException(FailureType.INFRA,
                        "Checkpoint write failed after enriching " + feature.getFeatureName()
                        + ": " + e.getMessage(), e);
            }
            // Push checkpoint to git so a fresh container can resume from this point
            if (branch != null && !branch.isBlank()) {
                gitService.commitEnrichmentCheckpoint(workspace, feature.getFeatureName(), branch);
            }
        }
        log.info("[ProjectPlanningNode] Feature enrichment complete — {} features processed", features.size());
    }

    /** BACKEND and SHARED enrich before FRONTEND so frontend sees real backend API summaries. */
    private static int enrichmentPriority(String featureType) {
        if (featureType == null) return 3;
        return switch (featureType.toUpperCase()) {
            case "SHARED"  -> 0;   // auth/security contracts referenced by everything
            case "BACKEND" -> 1;
            case "FRONTEND" -> 2;
            default -> 3;          // INFRA (skipped) and unknowns last
        };
    }

    private Map<String, List<FileSpec>> buildFilesByFeature(ArchitectureSpec spec) {
        Map<String, FileSpec> fileByPath = (spec.getFiles() == null ? List.<FileSpec>of() : spec.getFiles())
                .stream()
                .filter(f -> f.getFilePath() != null)
                .collect(Collectors.toMap(FileSpec::getFilePath, f -> f, (a, b) -> a));

        Map<String, List<FileSpec>> result = new LinkedHashMap<>();
        if (spec.getFeatures() != null) {
            for (FeatureSpec feature : spec.getFeatures()) {
                List<FileSpec> featureFiles = feature.getFilePaths() == null ? List.of() :
                        feature.getFilePaths().stream()
                                .map(fileByPath::get)
                                .filter(Objects::nonNull)
                                .toList();
                result.put(feature.getFeatureName(), featureFiles);
            }
        }
        return result;
    }

    private Map<String, Map<String, Object>> buildAllPeerSummaries(
            List<FeatureSpec> features, Map<String, List<FileSpec>> filesByFeature) {
        Map<String, Map<String, Object>> summaries = new LinkedHashMap<>();
        for (FeatureSpec f : features) {
            List<FileSpec> files = filesByFeature.getOrDefault(f.getFeatureName(), List.of());
            summaries.put(f.getFeatureName(), LlmGeneratorService.buildApiSummary(f, files));
        }
        return summaries;
    }

    private Map<String, Object> buildPeerSummariesExcluding(
            Map<String, Map<String, Object>> allSummaries, String excludeFeatureName) {
        Map<String, Object> peers = new LinkedHashMap<>();
        allSummaries.entrySet().stream()
                .filter(e -> !e.getKey().equals(excludeFeatureName))
                .forEach(e -> peers.put(e.getKey(), e.getValue()));
        return peers;
    }

    private List<FileEntry> toManifest(ArchitectureSpec spec) {
        return spec.getFiles().stream()
                .map(f -> new FileEntry(f.getFilePath(), FileType.valueOf(f.getFileType()), f.getDescription()))
                .toList();
    }

    private ArchitectureSpec validateCrossReferences(ArchitectureSpec spec) {
        Set<String> knownPaths = spec.getFiles().stream()
                .map(FileSpec::getFilePath)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        List<FileSpec> toAdd = new ArrayList<>();

        for (FileSpec file : spec.getFiles()) {
            if (file.getImportsFrom() == null) continue;
            for (String importPath : file.getImportsFrom()) {
                if (importPath == null || knownPaths.contains(importPath)) continue;
                if (toAdd.stream().anyMatch(f -> importPath.equals(f.getFilePath()))) continue;

                log.warn("[ProjectPlanningNode] Missing manifest entry: {} (imported by {})",
                        importPath, file.getFilePath());
                toAdd.add(buildStubEntry(importPath));
                knownPaths.add(importPath);
            }
        }

        if (!toAdd.isEmpty()) {
            List<FileSpec> all = new ArrayList<>(spec.getFiles());
            all.addAll(toAdd);
            spec.setFiles(all);
            log.info("[ProjectPlanningNode] Added {} missing stub entries to manifest", toAdd.size());
        }
        return spec;
    }

    private FileSpec buildStubEntry(String filePath) {
        String fileName = filePath.contains("/")
                ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;
        String fileType = filePath.startsWith("frontend/") ? "FRONTEND" : "BACKEND";
        return FileSpec.builder()
                .fileName(fileName)
                .filePath(filePath)
                .fileType(fileType)
                .layer(inferLayer(filePath))
                .status("PLANNED")
                .description("Auto-added: referenced in imports_from but missing from manifest")
                .build();
    }

    private String inferLayer(String path) {
        String p = path.toLowerCase();
        if (p.contains("/pages/"))      return "PAGE";
        if (p.contains("/components/")) return "COMPONENT";
        if (p.contains("/hooks/"))      return "HOOK";
        if (p.contains("/context/"))    return "CONTEXT";
        if (p.contains("/services/"))   return "SERVICE";
        if (p.contains("/types/"))      return "UTIL";
        if (p.contains("/model/"))      return "MODEL";
        if (p.contains("/repository/")) return "REPOSITORY";
        if (p.contains("/controller/")) return "CONTROLLER";
        if (p.contains("/service/"))    return "SERVICE";
        if (p.contains("/dto/"))        return "DTO";
        if (p.contains("/exception/"))  return "EXCEPTION";
        if (p.contains("/config/"))     return "CONFIG";
        return "UTIL";
    }

    private ArchitectureSpec mergeWithExisting(ArchitectureSpec newSpec, Path workspace) {
        if (workspace == null || !ArchitectureJsonUtil.exists(workspace)) return newSpec;
        try {
            ArchitectureSpec existing = ArchitectureJsonUtil.read(workspace);

            // ── File status merge: preserve terminal statuses for files whose feature hasn't changed ──
            Map<String, FileSpec> existingByPath = (existing.getFiles() == null ? List.<FileSpec>of() : existing.getFiles())
                    .stream()
                    .filter(f -> f.getFilePath() != null)
                    .collect(Collectors.toMap(FileSpec::getFilePath, f -> f, (a, b) -> a));

            // changeRequired now lives on FeatureSpec — build lookup
            Map<String, Boolean> featureChangeRequired = new LinkedHashMap<>();
            if (newSpec.getFeatures() != null) {
                for (FeatureSpec feature : newSpec.getFeatures()) {
                    featureChangeRequired.put(feature.getFeatureName(), feature.isChangeRequired());
                }
            }

            newSpec.getFiles().forEach(f -> {
                FileSpec old = existingByPath.get(f.getFilePath());
                if (old == null) return;
                String oldStatus = old.getStatus();
                boolean isTerminal = "VALIDATED".equalsIgnoreCase(oldStatus)
                        || "SPEC_COMPLIANT".equalsIgnoreCase(oldStatus)
                        || "GENERATION_FAILED".equalsIgnoreCase(oldStatus);
                // File-grain flag wins when present; feature grain is the fallback (old specs).
                boolean changeRequired = f.getChangeRequired() != null
                        ? f.getChangeRequired()
                        : featureChangeRequired.getOrDefault(f.getFeatureName(), true);
                if (isTerminal && !changeRequired) {
                    f.setStatus(oldStatus);
                }
            });

            // ── Feature instruction merge: preserve enriched instructions across spec regeneration ──
            if (newSpec.getFeatures() != null && existing.getFeatures() != null) {
                Map<String, FeatureSpec> existingFeatures = existing.getFeatures().stream()
                        .filter(f -> f.getFeatureName() != null)
                        .collect(Collectors.toMap(FeatureSpec::getFeatureName, f -> f, (a, b) -> a));

                newSpec.getFeatures().forEach(f -> {
                    if (f.getFeatureInstruction() == null || f.getFeatureInstruction().isBlank()) {
                        FeatureSpec oldFeature = existingFeatures.get(f.getFeatureName());
                        if (oldFeature != null && oldFeature.getFeatureInstruction() != null
                                && !oldFeature.getFeatureInstruction().isBlank()) {
                            f.setFeatureInstruction(oldFeature.getFeatureInstruction());
                        }
                    }
                });
            }

        } catch (IOException e) {
            log.warn("[ProjectPlanningNode] Could not merge with existing spec: {}", e.getMessage());
        }
        return newSpec;
    }

    // ── Dependency resolver ───────────────────────────────────────────────

    /**
     * Union of platform defaults and spec extras — the spec can ADD packages but never
     * REPLACE the baseline. A spec once dropped react-router-dom by shipping its own
     * (Next.js-flavored) npm list; the frontend then couldn't compile its router imports.
     * Forbidden framework packages are stripped regardless of what the spec asks for.
     */
    private ProjectDependencies resolveDependencies(ArchitectureSpec spec) {
        List<String> starters = new ArrayList<>(DEFAULT_SPRING_STARTERS);
        List<String> npm = new ArrayList<>(DEFAULT_NPM_PACKAGES);
        List<com.business.discovery.worker.service.llm.MavenCoordinate> maven = null;

        if (spec.getProjectDependencies() != null) {
            ProjectDependencies deps = spec.getProjectDependencies();
            if (deps.getSpringBootStarters() != null) {
                deps.getSpringBootStarters().stream()
                        .filter(s -> s != null && !starters.contains(s))
                        .forEach(starters::add);
            }
            if (deps.getNpmPackages() != null) {
                // Dedup by BASE package name (version- and scope-aware), not exact string: a
                // pinned default like "zod@^3" must suppress an LLM-supplied bare "zod" or "zod@4",
                // otherwise `npm install zod@^3 zod` installs both and the later (v4) wins.
                Set<String> presentBases = npm.stream()
                        .map(ProjectPlanningNode::basePackageName)
                        .collect(Collectors.toCollection(HashSet::new));
                deps.getNpmPackages().stream()
                        .filter(Objects::nonNull)
                        .filter(p -> {
                            if (FORBIDDEN_NPM_PACKAGES.contains(basePackageName(p))) {
                                log.warn("[ProjectPlanningNode] Spec requested forbidden framework package '{}' — stripped", p);
                                return false;
                            }
                            return true;
                        })
                        .filter(p -> presentBases.add(basePackageName(p)))
                        .forEach(npm::add);
            }
            maven = deps.getMavenDependencies();
        }
        return new ProjectDependencies(starters, npm, maven);
    }

    /**
     * Strips a trailing {@code @version} range from an npm package spec, preserving the leading
     * scope marker: {@code "zod@^3" → "zod"}, {@code "@tanstack/react-query@5" → "@tanstack/react-query"}.
     * Lower-cased so it can key dedup and forbidden-package sets.
     */
    private static String basePackageName(String pkg) {
        if (pkg == null) return "";
        String p = pkg.trim();
        int at = p.startsWith("@") ? p.indexOf('@', 1) : p.indexOf('@');
        return (at > 0 ? p.substring(0, at) : p).toLowerCase();
    }

    // ── BriefContext builder ──────────────────────────────────────────────

    private BriefContext buildBriefContext(ArchitectBrief brief, BusinessEntity business, String projectHistory) {
        return new BriefContext(
                business.getTitle(),
                nullSafe(brief.getBusinessCategory(), business.getCategory()),
                nullSafe(brief.getLocation(), "India"),
                brief.getWebsiteType() != null ? brief.getWebsiteType().name() : "INFORMATIONAL",
                nullSafeList(brief.getMustHaveFeatures()),
                nullSafeList(brief.getNiceToHaveFeatures()),
                nullSafeList(brief.getRecommendedPages()),
                // NEVER brief.getRecommendedTechStack() — the platform stack is fixed and
                // brief-supplied stacks (Next.js etc.) have derailed planning before
                PLATFORM_TECH_STACK,
                nullSafeList(brief.getSeoKeywords()),
                nullSafe(brief.getDesignDirection(), "modern and professional"),
                nullSafe(brief.getColorScheme(), "blue and white"),
                nullSafe(brief.getTone(), "professional"),
                nullSafe(brief.getCompetitorInsights(), ""),
                nullSafe(brief.getIndustryInsights(), ""),
                nullSafe(brief.getArchitecturalNotes(), ""),
                brief.getRequestedChanges(),
                projectHistory,
                nullSafe(business.getAddress(), ""),
                nullSafe(business.getPhone(), ""),
                business.getLatitude() != null ? String.valueOf(business.getLatitude()) : "",
                business.getLongitude() != null ? String.valueOf(business.getLongitude()) : "",
                formatOpenHours(business.getOpenHours())
        );
    }

    private String formatOpenHours(Map<String, String> openHours) {
        if (openHours == null || openHours.isEmpty()) return "";
        return openHours.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private String nullSafe(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    private List<String> nullSafeList(List<String> list) {
        return list != null ? list : List.of();
    }

    private Map<String, String> nullSafeMap(Map<String, String> map) {
        return map != null ? map : Map.of(
                "frontend", "React 19 + TypeScript",
                "backend", "Spring Boot 3",
                "database", "PostgreSQL");
    }
}
