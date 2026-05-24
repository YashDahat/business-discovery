package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.model.GeneratedFile;
import com.business.discovery.worker.repository.GeneratedFileRepository;
import com.business.discovery.worker.service.llm.FileEntry;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
@Order(7)
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

        try {
            for (FileEntry entry : infraFiles) {
                String content = llm.generateFileContent(
                        entry.path(), entry.description(), ctx.getBriefCtx(), allPaths);

                Path filePath = ctx.getWorkspaceDir().resolve(entry.path());
                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, content);

                fileRepo.save(GeneratedFile.builder()
                        .taskId(ctx.getTaskId())
                        .filePath(entry.path())
                        .fileType(GeneratedFile.FileType.INFRA)
                        .status(GeneratedFile.FileStatus.GENERATED)
                        .attemptNumber(ctx.getAttemptNumber())
                        .build());

                log.info("[InfraGeneratorNode] Generated {}", entry.path());
            }

            writeCiWorkflowIfAbsent(ctx.getWorkspaceDir());
        } catch (WorkerException e) {
            throw e;
        } catch (IOException e) {
            throw new WorkerException(FailureType.INFRA,
                    "Failed to write infra file: " + e.getMessage(), e);
        }

        log.info("[InfraGeneratorNode] Generated {} infra files + CI workflow", infraFiles.size());
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
