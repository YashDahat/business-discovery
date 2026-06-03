package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.model.GeneratedFile;
import com.business.discovery.worker.repository.GeneratedFileRepository;
import com.business.discovery.worker.service.llm.FileEntry;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.business.discovery.worker.util.ArchitectureJsonUtil;
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

@Component
@Order(9)
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

        // Read ARCHITECTURE.json once — all infra files get the same spec context
        String architectureSpec = readArchitectureSpec(workspace);

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

                String existingContent = readExistingIfPresent(filePath);

                String content = llm.generateFileContent(
                        entry.path(), entry.description(), "INFRA",
                        ctx.getBriefCtx(), allPaths,
                        existingContent, architectureSpec, configContext);

                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, content);

                upsertRecord(ctx, entry.path(), GeneratedFile.FileType.INFRA);

                log.info("[InfraGeneratorNode] Generated {}", entry.path());
            }

            writeCiWorkflowIfAbsent(workspace);
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

    private String readArchitectureSpec(Path workspace) {
        if (!ArchitectureJsonUtil.exists(workspace)) return "";
        try {
            return Files.readString(workspace.resolve(ArchitectureJsonUtil.ARCH_PATH));
        } catch (IOException e) {
            log.warn("[InfraGeneratorNode] Could not read ARCHITECTURE.json: {}", e.getMessage());
            return "";
        }
    }

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
