package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.model.ArchitectBrief;
import com.business.discovery.worker.model.BusinessEntity;
import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.BriefContext;
import com.business.discovery.worker.service.llm.FileEntry;
import com.business.discovery.worker.service.llm.ProjectDependencies;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.business.discovery.worker.util.ArchitectureJsonUtil;
import com.business.discovery.worker.util.SlugUtil;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
@Order(5)
@Slf4j
public class ProjectPlanningNode implements WorkerNode {

    private static final int PROCESS_TIMEOUT_MINUTES = 5;

    private static final List<String> DEFAULT_SPRING_STARTERS =
            List.of("web", "data-jpa", "postgresql", "lombok", "validation", "actuator");

    private static final List<String> DEFAULT_NPM_PACKAGES =
            List.of("@tanstack/react-query", "react-hook-form", "zod", "axios", "react-router-dom");

    private final LlmGeneratorService llm;

    public ProjectPlanningNode(@Qualifier("geminiPro") LlmGeneratorService llm) {
        this.llm = llm;
    }

    @Override
    public void execute(WorkerContext ctx) {
        BusinessEntity business = ctx.getBusiness();
        ArchitectBrief brief = ctx.getBrief();
        String slug = SlugUtil.toSlug(business.getTitle());
        Path workspace = ctx.getWorkspaceDir();

        BriefContext briefCtx = buildBriefContext(brief, business, ctx.getProjectHistory());
        ctx.setBriefCtx(briefCtx);

        ArchitectureSpec spec = llm.generateArchitectureSpec(briefCtx, slug);

        List<FileEntry> manifest = spec.getFiles().stream()
                .map(f -> new FileEntry(f.getFilePath(), FileType.valueOf(f.getFileType()), f.getDescription()))
                .toList();
        ctx.setFileManifest(manifest);

        log.info("[ProjectPlanningNode] Planned {} files for '{}'", manifest.size(), business.getTitle());

        try {
            // Scaffold only on first run — on retry the cloned repo already has these
            if (!Files.exists(workspace.resolve("backend/pom.xml"))) {
                ProjectDependencies deps = resolveDependencies(spec);
                scaffoldSpringBoot(workspace, slug, business.getTitle(), deps.getSpringBootStarters());
                scaffoldVite(workspace, deps.getNpmPackages());
                writeDockerArtifacts(workspace, slug);
                log.info("[ProjectPlanningNode] CLI scaffold complete for '{}'", business.getTitle());
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
                                    List<String> starters) throws IOException {
        String deps = String.join(",", starters);
        String url = "https://start.spring.io/starter.zip"
                + "?type=maven-project&language=java&bootVersion=3.3.4"
                + "&baseDir=backend"
                + "&groupId=com." + slug
                + "&artifactId=" + slug + "-backend"
                + "&name=" + slug + "backend"
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

        // Override the generated application.properties — no hardcoded defaults for secrets
        Files.writeString(workspace.resolve("backend/src/main/resources/application.properties"), """
                spring.application.name=%s
                server.port=8080
                spring.datasource.url=${DB_URL}
                spring.datasource.username=${DB_USERNAME}
                spring.datasource.password=${DB_PASSWORD}
                spring.jpa.hibernate.ddl-auto=update
                spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect
                management.endpoints.web.exposure.include=health,info
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

        // Install project-specific packages decided by the planning LLM
        if (!extraPackages.isEmpty()) {
            List<String> installCmd = new ArrayList<>(List.of("npm", "install"));
            installCmd.addAll(extraPackages);
            run(frontend, installCmd.toArray(new String[0]));
        }

        log.info("[ProjectPlanningNode] Vite scaffold complete, installed: {}", extraPackages);
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

                FROM eclipse-temurin:17-jre-alpine
                RUN addgroup -S app && adduser -S app -G app
                WORKDIR /app
                COPY --from=backend-build /app/target/*.jar app.jar
                USER app
                EXPOSE 8080
                HEALTHCHECK --interval=30s --timeout=5s CMD wget -qO- http://localhost:8080/actuator/health || exit 1
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
                      test: ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health"]
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
                """.formatted(slug, slug));

        Files.writeString(workspace.resolve(".gitignore"), """
                .env
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

    // ── Dependency resolver ───────────────────────────────────────────────

    private ProjectDependencies resolveDependencies(ArchitectureSpec spec) {
        if (spec.getProjectDependencies() != null) {
            ProjectDependencies deps = spec.getProjectDependencies();
            List<String> starters = (deps.getSpringBootStarters() != null && !deps.getSpringBootStarters().isEmpty())
                    ? deps.getSpringBootStarters() : DEFAULT_SPRING_STARTERS;
            List<String> npm = (deps.getNpmPackages() != null && !deps.getNpmPackages().isEmpty())
                    ? deps.getNpmPackages() : DEFAULT_NPM_PACKAGES;
            return new ProjectDependencies(starters, npm);
        }
        return new ProjectDependencies(DEFAULT_SPRING_STARTERS, DEFAULT_NPM_PACKAGES);
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
                nullSafeMap(brief.getRecommendedTechStack()),
                nullSafeList(brief.getSeoKeywords()),
                nullSafe(brief.getDesignDirection(), "modern and professional"),
                nullSafe(brief.getColorScheme(), "blue and white"),
                nullSafe(brief.getTone(), "professional"),
                nullSafe(brief.getCompetitorInsights(), ""),
                nullSafe(brief.getIndustryInsights(), ""),
                nullSafe(brief.getArchitecturalNotes(), ""),
                brief.getRequestedChanges(),
                projectHistory
        );
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
