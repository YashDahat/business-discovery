package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.model.GeneratedFile;
import com.business.discovery.worker.repository.GeneratedFileRepository;
import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FileEntry;
import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.business.discovery.worker.util.ArchitectureJsonUtil;
import com.business.discovery.worker.util.EnvVarScanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Order(11)
@Slf4j
public class InfraGeneratorNode implements WorkerNode {

    private final LlmGeneratorService llm;
    private final GeneratedFileRepository fileRepo;

    public InfraGeneratorNode(@Qualifier("geminiFlash") LlmGeneratorService llm,
                              GeneratedFileRepository fileRepo) {
        this.llm = llm;
        this.fileRepo = fileRepo;
    }

    @Override
    public void execute(WorkerContext ctx) {
        List<FileEntry> infraFiles = ctx.getFileManifest().stream()
                .filter(e -> e.type() == FileType.INFRA)
                .toList();

        List<String> allPaths = ctx.getFileManifest().stream()
                .map(FileEntry::path)
                .toList();

        boolean requestedChangesMode = ctx.getBriefCtx().requestedChanges() != null
                && !ctx.getBriefCtx().requestedChanges().isBlank();

        Path workspace = ctx.getWorkspaceDir();

        // Load spec for file_role lookup
        Map<String, FileSpec> specByPath = loadSpecByPath(workspace);

        // Targeted config context: only the files infra actually needs to produce a working docker setup
        Map<String, String> configContext = buildInfraContext(workspace);
        log.info("[InfraGeneratorNode] Config context: {} files", configContext.size());

        try {
            for (FileEntry entry : infraFiles) {
                Path filePath = workspace.resolve(entry.path());

                if (!requestedChangesMode && Files.exists(filePath)) {
                    log.info("[InfraGeneratorNode] Skipping already-generated: {}", entry.path());
                    continue;
                }

                Files.createDirectories(filePath.getParent());

                // Try template-based generation first — zero LLM for known structural patterns
                String templateContent = tryGenerateFromTemplate(entry.path(), ctx);
                if (templateContent != null) {
                    Files.writeString(filePath, templateContent);
                    upsertRecord(ctx, entry.path(), GeneratedFile.FileType.INFRA);
                    log.info("[InfraGeneratorNode] Generated {} from template", entry.path());
                    continue;
                }

                // Fall back to LLM for non-standard infra files
                String existingContent = readExistingIfPresent(filePath);
                FileSpec spec = specByPath.get(entry.path());
                String fileRole = spec != null && spec.getFileRole() != null && !spec.getFileRole().isBlank()
                        ? spec.getFileRole()
                        : "Generate: " + entry.path() + "\n" + entry.description()
                          + "\nBusiness: " + ctx.getBriefCtx().businessName()
                          + "\nTech stack: " + ctx.getBriefCtx().techStack();

                // INFRA features aren't enriched — no feature context, just the per-file role
                String content = llm.generateFileContent(entry.path(), fileRole, configContext, existingContent);

                Files.writeString(filePath, content);
                upsertRecord(ctx, entry.path(), GeneratedFile.FileType.INFRA);
                log.info("[InfraGeneratorNode] Generated {} via LLM", entry.path());
            }

            writeCiWorkflowIfAbsent(workspace);

            // Seed typed defaults (booleans/ints/URLs) from application.properties into any blank
            // .env.example values, so the runtime smoke harness never poisons a typed @Value with a
            // placeholder string (e.g. S3_PATH_STYLE= → demo-placeholder → "Invalid boolean value").
            EnvVarScanner.backfillEnvExampleDefaults(workspace);
        } catch (WorkerException e) {
            throw e;
        } catch (IOException e) {
            throw new WorkerException(FailureType.INFRA,
                    "Failed to write infra file: " + e.getMessage(), e);
        }

        log.info("[InfraGeneratorNode] Generated {} infra files + CI workflow", infraFiles.size());
    }

    private void upsertRecord(WorkerContext ctx, String filePath, GeneratedFile.FileType fileType) {
        fileRepo.findByTaskIdAndFilePath(ctx.getTaskId(), filePath).ifPresentOrElse(
                existing -> {
                    existing.setStatus(GeneratedFile.FileStatus.GENERATED);
                    existing.setAttemptNumber(ctx.getAttemptNumber());
                    existing.setErrorMessage(null);
                    fileRepo.save(existing);
                },
                () -> fileRepo.save(GeneratedFile.builder()
                        .taskId(ctx.getTaskId())
                        .filePath(filePath)
                        .fileType(fileType)
                        .status(GeneratedFile.FileStatus.GENERATED)
                        .attemptNumber(ctx.getAttemptNumber())
                        .build())
        );
    }

    // ── Config context ────────────────────────────────────────────────────

    /**
     * Reads the specific config files infra generation needs to produce a correct, runnable
     * docker-compose setup. Goal: docker-compose up --build starts all services cold.
     *
     * pom.xml             → Java version, artifact ID, packaging for Dockerfile FROM/COPY
     * application.properties → server port, DB config, app name
     * .env.example        → all required environment variables for docker-compose env_file
     * package.json        → Node version, build script (npm run build), output dir
     * tsconfig.json       → TypeScript/Vite config
     */
    private Map<String, String> buildInfraContext(Path workspace) {
        Map<String, String> ctx = new LinkedHashMap<>();
        readIfExists(ctx, workspace, "backend/pom.xml");
        readIfExists(ctx, workspace, "backend/src/main/resources/application.properties");
        readIfExists(ctx, workspace, ".env.example");
        readIfExists(ctx, workspace, "frontend/package.json");
        readIfExists(ctx, workspace, "frontend/tsconfig.json");
        readIfExists(ctx, workspace, "frontend/tsconfig.app.json");
        return ctx;
    }

    private void readIfExists(Map<String, String> ctx, Path workspace, String relativePath) {
        Path file = workspace.resolve(relativePath);
        if (Files.exists(file)) {
            try {
                ctx.put(relativePath, Files.readString(file));
            } catch (IOException e) {
                log.warn("[InfraGeneratorNode] Could not read {}: {}", relativePath, e.getMessage());
            }
        }
    }

    private Map<String, FileSpec> loadSpecByPath(Path workspace) {
        if (!ArchitectureJsonUtil.exists(workspace)) return Map.of();
        try {
            ArchitectureSpec spec = ArchitectureJsonUtil.read(workspace);
            return spec.getFiles().stream()
                    .filter(f -> f.getFilePath() != null)
                    .collect(Collectors.toMap(FileSpec::getFilePath, f -> f, (a, b) -> a));
        } catch (IOException e) {
            log.warn("[InfraGeneratorNode] Could not load spec: {}", e.getMessage());
            return Map.of();
        }
    }

    // ── Template-based infra generation ──────────────────────────────────

    private String tryGenerateFromTemplate(String filePath, WorkerContext ctx) {
        String name = filePath.contains("/") ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;
        String lower = name.toLowerCase();

        // backend/Dockerfile or Dockerfile (standalone backend image)
        if (lower.equals("dockerfile") && filePath.startsWith("backend/")) {
            return BACKEND_DOCKERFILE;
        }
        // nginx.conf for frontend SPA serving
        if (lower.endsWith("nginx.conf")) {
            return NGINX_CONF;
        }
        // docker-compose files — already written as top-level by ProjectPlanningNode.writeDockerArtifacts()
        // but the spec may also declare a service-level one; generate template
        if (lower.startsWith("docker-compose") && lower.endsWith(".yml")) {
            return null; // let LLM handle compose variants; top-level is already template-based
        }
        return null; // not a recognized template pattern
    }

    private static final String BACKEND_DOCKERFILE = """
            FROM eclipse-temurin:17-jdk-jammy AS builder
            WORKDIR /app
            COPY .mvn/ .mvn
            COPY mvnw pom.xml ./
            RUN ./mvnw dependency:resolve -q
            COPY src ./src
            RUN ./mvnw package -DskipTests -q

            FROM eclipse-temurin:17-jre
            WORKDIR /app
            RUN groupadd -r appuser && useradd -r -g appuser appuser
            COPY --from=builder /app/target/*.jar app.jar
            USER appuser
            EXPOSE 8080
            HEALTHCHECK --interval=30s --timeout=5s CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1
            ENTRYPOINT ["java", "-jar", "app.jar"]
            """;

    private static final String NGINX_CONF = """
            server {
              listen 80;
              root /usr/share/nginx/html;
              index index.html;

              location /api/ {
                proxy_pass http://backend:8080;
                proxy_set_header Host $host;
                proxy_set_header X-Real-IP $remote_addr;
              }

              location / {
                try_files $uri $uri/ /index.html;
              }

              gzip on;
              gzip_types text/plain text/css application/json application/javascript text/xml;
            }
            """;

    // ── Other helpers ─────────────────────────────────────────────────────

    private String readExistingIfPresent(Path filePath) {
        if (!Files.exists(filePath)) return null;
        try {
            return Files.readString(filePath);
        } catch (IOException e) {
            log.warn("[InfraGeneratorNode] Could not read existing {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    private void writeCiWorkflowIfAbsent(Path workspace) throws IOException {
        Path ciPath = workspace.resolve(".github/workflows/ci.yml");
        if (Files.exists(ciPath)) return;
        Files.createDirectories(ciPath.getParent());
        Files.writeString(ciPath, CI_WORKFLOW);
    }

    private static final String CI_WORKFLOW = """
            name: CI
            on:
              push:
                branches: [main, "feature/**"]
              pull_request:
                branches: [main]
            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@v4
                  - name: Set up JDK 17
                    uses: actions/setup-java@v4
                    with:
                      java-version: '17'
                      distribution: 'temurin'
                  - name: Build backend
                    working-directory: backend
                    run: mvn package -q -DskipTests
                  - name: Set up Node 20
                    uses: actions/setup-node@v4
                    with:
                      node-version: '20'
                  - name: Build frontend
                    working-directory: frontend
                    run: npm ci && npm run build
            """;
}
