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
import com.business.discovery.worker.util.LayerOrderUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
        // Sort by layer: Types → Constants → Utils → Services → Hooks → Components → Pages → App
        List<FileEntry> frontendFiles = ctx.getFileManifest().stream()
                .filter(e -> e.type() == FileType.FRONTEND)
                .sorted(Comparator.comparingInt(LayerOrderUtil::frontendPriority))
                .toList();

        List<String> allPaths = ctx.getFileManifest().stream()
                .map(FileEntry::path)
                .toList();

        boolean requestedChangesMode = ctx.getBriefCtx().requestedChanges() != null
                && !ctx.getBriefCtx().requestedChanges().isBlank();

        Path workspace = ctx.getWorkspaceDir();

        log.info("[FrontendGeneratorNode] Generating {} frontend files in layer order", frontendFiles.size());

        try {
            for (FileEntry entry : frontendFiles) {
                Path filePath = workspace.resolve(entry.path());
                String layer = LayerOrderUtil.frontendLayerName(entry);

                if (!requestedChangesMode && isFileValidated(workspace, entry.path())) {
                    log.info("[FrontendGeneratorNode] Skipping validated [{}]: {}", layer, entry.path());
                    continue;
                }

                log.info("[FrontendGeneratorNode] Generating [{}]: {}", layer, entry.path());

                String existingContent = readExistingIfPresent(filePath);

                // Read ARCHITECTURE.json fresh — reflects GENERATED statuses from prior files in this loop
                String architectureSpec = readArchitectureSpec(workspace);

                // Build targeted context: only the files this file depends on
                Map<String, String> dependencyFiles = buildDependencyContext(workspace, entry.path());

                String content = llm.generateFileContent(
                        entry.path(), entry.description(), layer,
                        ctx.getBriefCtx(), allPaths,
                        existingContent, architectureSpec, dependencyFiles);

                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, content);

                // Mark as GENERATED in ARCHITECTURE.json so the next file sees accurate status
                try {
                    ArchitectureJsonUtil.updateFileStatus(workspace, entry.path(), "GENERATED");
                } catch (IOException e) {
                    log.warn("[FrontendGeneratorNode] Could not update ARCHITECTURE.json for {}: {}",
                            entry.path(), e.getMessage());
                }

                upsertRecord(ctx, entry.path(), GeneratedFile.FileType.FRONTEND);

                log.info("[FrontendGeneratorNode] Generated [{}]: {}", layer, entry.path());
            }
        } catch (WorkerException e) {
            throw e;
        } catch (IOException e) {
            throw new WorkerException(FailureType.INFRA,
                    "Failed to write frontend file: " + e.getMessage(), e);
        }

        log.info("[FrontendGeneratorNode] Done — {} frontend files", frontendFiles.size());
    }

    // ── Context builders ──────────────────────────────────────────────────

    private Map<String, String> buildDependencyContext(Path workspace, String filePath) {
        Map<String, String> ctx = new LinkedHashMap<>();

        Set<String> depPaths = getDepsFromSpec(workspace, filePath);
        if (depPaths.isEmpty()) {
            depPaths = getSameDirectoryFiles(workspace, filePath);
        }

        for (String dep : depPaths) {
            Path file = workspace.resolve(dep);
            if (Files.exists(file)) {
                try {
                    ctx.put(dep, Files.readString(file));
                } catch (IOException ignored) {}
            }
        }
        return ctx;
    }

    private Set<String> getDepsFromSpec(Path workspace, String filePath) {
        if (!ArchitectureJsonUtil.exists(workspace)) return Set.of();
        try {
            Map<String, String> nameToPath = ArchitectureJsonUtil.read(workspace).getFiles().stream()
                    .filter(f -> f.getFilePath() != null)
                    .collect(Collectors.toMap(
                            f -> lastName(f.getFilePath()),
                            f -> f.getFilePath(),
                            (a, b) -> a));

            var specOpt = ArchitectureJsonUtil.findByPath(workspace, filePath);
            if (specOpt.isEmpty()) return Set.of();

            var spec = specOpt.get();
            Set<String> raw = new LinkedHashSet<>();
            if (spec.getDependsOn() != null) raw.addAll(spec.getDependsOn());
            if (spec.getImportsFrom() != null) raw.addAll(spec.getImportsFrom());

            return raw.stream()
                    .map(dep -> dep.contains("/") ? dep : nameToPath.getOrDefault(dep, null))
                    .filter(p -> p != null)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (IOException e) {
            return Set.of();
        }
    }

    private Set<String> getSameDirectoryFiles(Path workspace, String filePath) {
        Path dir = workspace.resolve(filePath).getParent();
        if (dir == null || !Files.exists(dir)) return Set.of();
        try {
            return Files.list(dir)
                    .filter(p -> p.toFile().isFile())
                    .filter(p -> !workspace.relativize(p).toString().equals(filePath))
                    .map(p -> workspace.relativize(p).toString())
                    .limit(5)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (IOException e) {
            return Set.of();
        }
    }

    private String readArchitectureSpec(Path workspace) {
        if (!ArchitectureJsonUtil.exists(workspace)) return "";
        try {
            return Files.readString(workspace.resolve(ArchitectureJsonUtil.ARCH_PATH));
        } catch (IOException e) {
            log.warn("[FrontendGeneratorNode] Could not read ARCHITECTURE.json: {}", e.getMessage());
            return "";
        }
    }

    // ── Other helpers ─────────────────────────────────────────────────────

    private boolean isFileValidated(Path workspace, String filePath) {
        if (!ArchitectureJsonUtil.exists(workspace)) return false;
        try {
            return ArchitectureJsonUtil.findByPath(workspace, filePath)
                    .map(f -> "VALIDATED".equalsIgnoreCase(f.getStatus()))
                    .orElse(false);
        } catch (IOException e) {
            log.warn("[FrontendGeneratorNode] Could not check validation status for {}: {}", filePath, e.getMessage());
            return false;
        }
    }

    private String readExistingIfPresent(Path filePath) {
        if (!Files.exists(filePath)) return null;
        try {
            return Files.readString(filePath);
        } catch (IOException e) {
            log.warn("[FrontendGeneratorNode] Could not read existing {}: {}", filePath, e.getMessage());
            return null;
        }
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

    private String lastName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
