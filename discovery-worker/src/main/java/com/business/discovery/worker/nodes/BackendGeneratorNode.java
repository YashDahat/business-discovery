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
@Order(7)
@Slf4j
public class BackendGeneratorNode implements WorkerNode {

    private final LlmGeneratorService llm;
    private final GeneratedFileRepository fileRepo;

    public BackendGeneratorNode(@Qualifier("geminiFlash") LlmGeneratorService llm,
                                GeneratedFileRepository fileRepo) {
        this.llm = llm;
        this.fileRepo = fileRepo;
    }

    @Override
    public void execute(WorkerContext ctx) {
        List<FileEntry> backendFiles = ctx.getFileManifest().stream()
                .filter(e -> e.type() == FileType.BACKEND)
                .toList();

        List<String> allPaths = ctx.getFileManifest().stream()
                .map(FileEntry::path)
                .toList();

        boolean requestedChangesMode = ctx.getBriefCtx().requestedChanges() != null
                && !ctx.getBriefCtx().requestedChanges().isBlank();

        Map<String, String> codebaseContext = CodebaseContextBuilder.build(ctx.getWorkspaceDir(), "");
        log.info("[BackendGeneratorNode] Codebase context: {} files ({} chars)",
                codebaseContext.size(),
                codebaseContext.values().stream().mapToInt(String::length).sum());

        try {
            for (FileEntry entry : backendFiles) {
                Path filePath = ctx.getWorkspaceDir().resolve(entry.path());

                if (!requestedChangesMode && Files.exists(filePath)) {
                    log.info("[BackendGeneratorNode] Skipping already-generated: {}", entry.path());
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
                        .fileType(GeneratedFile.FileType.BACKEND)
                        .status(GeneratedFile.FileStatus.GENERATED)
                        .attemptNumber(ctx.getAttemptNumber())
                        .build());

                log.info("[BackendGeneratorNode] Generated {}", entry.path());
            }
        } catch (WorkerException e) {
            throw e;
        } catch (IOException e) {
            throw new WorkerException(FailureType.INFRA,
                    "Failed to write backend file: " + e.getMessage(), e);
        }

        log.info("[BackendGeneratorNode] Generated {} backend files", backendFiles.size());
    }

    private String readExistingIfPresent(Path filePath, WorkerContext ctx) {
        if (!Files.exists(filePath)) return null;
        try {
            return Files.readString(filePath);
        } catch (IOException e) {
            log.warn("[BackendGeneratorNode] Could not read existing {}: {}", filePath, e.getMessage());
            return null;
        }
    }
}
