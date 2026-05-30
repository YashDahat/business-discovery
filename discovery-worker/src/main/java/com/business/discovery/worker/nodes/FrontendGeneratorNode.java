package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.model.GeneratedFile;
import com.business.discovery.worker.repository.GeneratedFileRepository;
import com.business.discovery.worker.service.llm.CodebaseContextBuilder;
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
import java.util.Map;

@Component
@Order(8)
@Slf4j
public class FrontendGeneratorNode implements WorkerNode {

    private final LlmGeneratorService llm;
    private final GeneratedFileRepository fileRepo;

    public FrontendGeneratorNode(@Qualifier("geminiFlash") LlmGeneratorService llm,
                                 GeneratedFileRepository fileRepo) {
        this.llm = llm;
        this.fileRepo = fileRepo;
    }

    @Override
    public void execute(WorkerContext ctx) {
        List<FileEntry> frontendFiles = ctx.getFileManifest().stream()
                .filter(e -> e.type() == FileType.FRONTEND)
                .toList();

        List<String> allPaths = ctx.getFileManifest().stream()
                .map(FileEntry::path)
                .toList();

        boolean requestedChangesMode = ctx.getBriefCtx().requestedChanges() != null
                && !ctx.getBriefCtx().requestedChanges().isBlank();

        Map<String, String> codebaseContext = CodebaseContextBuilder.build(ctx.getWorkspaceDir(), "");
        log.info("[FrontendGeneratorNode] Codebase context: {} files ({} chars)",
                codebaseContext.size(),
                codebaseContext.values().stream().mapToInt(String::length).sum());

        try {
            for (FileEntry entry : frontendFiles) {
                Path filePath = ctx.getWorkspaceDir().resolve(entry.path());

                if (!requestedChangesMode && Files.exists(filePath)) {
                    log.info("[FrontendGeneratorNode] Skipping already-generated: {}", entry.path());
                    continue;
                }

                String existingContent = readExistingIfPresent(filePath, ctx);

                String content = llm.generateFileContent(
                        entry.path(), entry.description(), ctx.getBriefCtx(), allPaths,
                        existingContent, codebaseContext);

                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, content);

                fileRepo.save(GeneratedFile.builder()
                        .taskId(ctx.getTaskId())
                        .filePath(entry.path())
                        .fileType(GeneratedFile.FileType.FRONTEND)
                        .status(GeneratedFile.FileStatus.GENERATED)
                        .attemptNumber(ctx.getAttemptNumber())
                        .build());

                log.info("[FrontendGeneratorNode] Generated {}", entry.path());
            }
        } catch (WorkerException e) {
            throw e;
        } catch (IOException e) {
            throw new WorkerException(FailureType.INFRA,
                    "Failed to write frontend file: " + e.getMessage(), e);
        }

        log.info("[FrontendGeneratorNode] Generated {} frontend files", frontendFiles.size());
    }

    private String readExistingIfPresent(Path filePath, WorkerContext ctx) {
        if (!Files.exists(filePath)) return null;
        try {
            return Files.readString(filePath);
        } catch (IOException e) {
            log.warn("[FrontendGeneratorNode] Could not read existing {}: {}", filePath, e.getMessage());
            return null;
        }
    }
}
