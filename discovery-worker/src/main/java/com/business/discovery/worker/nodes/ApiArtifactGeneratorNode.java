package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.model.GeneratedFile;
import com.business.discovery.worker.repository.GeneratedFileRepository;
import com.business.discovery.worker.service.llm.FileEntry;
import com.business.discovery.worker.util.ApiInventory;
import com.business.discovery.worker.util.ArchitectureJsonUtil;
import com.business.discovery.worker.util.ServicePlanPruner;
import com.business.discovery.worker.util.TsSdkGenerator;
import com.business.discovery.worker.util.TsTypeGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Derives the frontend's API-facing layer from the compile-validated backend — the
 * "measured reality over model memory" pattern applied to the API contract.
 *
 * Runs AFTER BackendValidationNode (backend is truth) and BEFORE FrontendGeneratorNode
 * (pages/hooks consume the derived artifacts via the existing dependency-context
 * mechanism). Emits:
 *   - docs/API_INVENTORY.json                     (evidence / debugging)
 *   - frontend/src/types/<domain>.ts              (transpiled wire types)
 *   - frontend/src/services/<domain>Service.ts    (typed SDK — one function per endpoint)
 *
 * Emitted files are marked VALIDATED in ARCHITECTURE.json so the frontend generator's
 * shouldSkip excludes them from LLM generation, and retries (Sisyphus-safe) never
 * regenerate them via LLM. Idempotent: re-derives from the current backend truth on
 * every attempt, so backend fixes made by the ErrorFixAgent are always reflected.
 *
 * Zero LLM cost. Deliberately non-fatal on partial extraction — an empty inventory on a
 * backend that passed validation indicates a parsing gap, which we surface loudly but
 * let the LLM path cover (the spec still plans the files; unskipped entries generate).
 */
@Component
@Order(9)
@Slf4j
public class ApiArtifactGeneratorNode implements WorkerNode {

    private final GeneratedFileRepository fileRepo;

    public ApiArtifactGeneratorNode(GeneratedFileRepository fileRepo) {
        this.fileRepo = fileRepo;
    }

    /**
     * api/client.ts is infrastructure, not judgment — Flash once wrote it with baseURL: ''
     * by accident, and next run it might set a prefix and silently break every derived call.
     * baseURL stays '' DELIBERATELY: the derived SDK carries full /api/v1 paths.
     */
    private static final String CANONICAL_API_CLIENT = """
            // GENERATED from the backend API contract — do not edit by hand.
            import axios from 'axios';

            const apiClient = axios.create({ baseURL: '' });

            apiClient.interceptors.request.use((config) => {
              const token = localStorage.getItem('token');
              if (token) config.headers.Authorization = `Bearer ${token}`;
              return config;
            });

            export default apiClient;
            """;

    @Override
    public void execute(WorkerContext ctx) {
        Path workspace = ctx.getWorkspaceDir();
        Path backendSrcJava = workspace.resolve("backend/src/main/java");

        // Emitted even on the parser-gap path below, so the SDK's `import apiClient from
        // '@/api/client'` always resolves and Flash never gets to invent a baseURL.
        try {
            writeAll(ctx, workspace, Map.of("frontend/src/api/client.ts", CANONICAL_API_CLIENT));
        } catch (IOException e) {
            throw new WorkerException(FailureType.INFRA,
                    "Failed writing derived api/client.ts: " + e.getMessage(), e);
        }

        ApiInventory inventory = ApiInventory.extract(backendSrcJava);
        if (inventory.isEmpty()) {
            log.warn("[ApiArtifactGeneratorNode] No endpoints extracted from a validated backend — "
                    + "parser gap? Falling back to LLM generation for types/services this run.");
            return;
        }
        inventory.writeJson(workspace);

        List<String> plannedTypePaths = plannedPaths(ctx, "frontend/src/types/");
        List<String> plannedServicePaths = plannedPaths(ctx, "frontend/src/services/");

        TsTypeGenerator.Result typeResult = TsTypeGenerator.generate(inventory, plannedTypePaths);
        Map<String, String> serviceFiles =
                TsSdkGenerator.generate(inventory, typeResult.typeToPath(), plannedServicePaths);

        try {
            writeAll(ctx, workspace, typeResult.files());
            writeAll(ctx, workspace, serviceFiles);
        } catch (IOException e) {
            throw new WorkerException(FailureType.INFRA,
                    "Failed writing derived API artifacts: " + e.getMessage(), e);
        }

        // Derivation assigns every endpoint to exactly one domain file, so any planned
        // services/ path it did NOT claim is a duplicate or a phantom — strip it from the
        // plan and the run's manifest before FrontendGeneratorNode can hand it to Flash.
        List<String> stripped = ServicePlanPruner.prune(workspace, serviceFiles.keySet());
        if (!stripped.isEmpty()) {
            ctx.setFileManifest(ctx.getFileManifest().stream()
                    .filter(e -> !stripped.contains(e.path()))
                    .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new)));
            log.info("[ApiArtifactGeneratorNode] Pruned {} unclaimed planned service file(s) "
                    + "from the manifest: {}", stripped.size(), stripped);
        }

        log.info("[ApiArtifactGeneratorNode] Derived {} type + {} service file(s) from {} endpoints / {} types",
                typeResult.files().size(), serviceFiles.size(),
                inventory.endpoints().size(), inventory.types().size());
    }

    private void writeAll(WorkerContext ctx, Path workspace, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Path target = workspace.resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, entry.getValue());

            // VALIDATED: shouldSkip excludes from LLM generation + Sisyphus-safe on retries
            try {
                ArchitectureJsonUtil.updateFileStatus(workspace, entry.getKey(), "VALIDATED");
            } catch (IOException e) {
                log.warn("[ApiArtifactGeneratorNode] Could not mark {} VALIDATED in spec: {}",
                        entry.getKey(), e.getMessage());
            }
            upsertRecord(ctx, entry.getKey());
            log.info("[ApiArtifactGeneratorNode] Derived {}", entry.getKey());
        }
    }

    private void upsertRecord(WorkerContext ctx, String filePath) {
        fileRepo.findByTaskIdAndFilePath(ctx.getTaskId(), filePath).ifPresentOrElse(
                existing -> {
                    existing.setStatus(GeneratedFile.FileStatus.VALIDATED);
                    existing.setAttemptNumber(ctx.getAttemptNumber());
                    existing.setErrorMessage(null);
                    fileRepo.save(existing);
                },
                () -> fileRepo.save(GeneratedFile.builder()
                        .taskId(ctx.getTaskId())
                        .filePath(filePath)
                        .fileType(GeneratedFile.FileType.FRONTEND)
                        .status(GeneratedFile.FileStatus.VALIDATED)
                        .attemptNumber(ctx.getAttemptNumber())
                        .build())
        );
    }

    private List<String> plannedPaths(WorkerContext ctx, String prefix) {
        return ctx.getFileManifest().stream()
                .filter(e -> e.type() == FileType.FRONTEND)
                .map(FileEntry::path)
                .filter(p -> p.startsWith(prefix) && p.endsWith(".ts"))
                .toList();
    }
}
