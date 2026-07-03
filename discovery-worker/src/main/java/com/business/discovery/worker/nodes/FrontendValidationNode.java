package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.service.BuildToolService;
import com.business.discovery.worker.service.BuildToolService.BuildResult;
import com.business.discovery.worker.util.ArchitectureJsonUtil;
import com.business.discovery.worker.util.NpmPackageFixer;
import com.business.discovery.worker.util.TsxExportGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

@Component
@Order(12)
@Slf4j
@RequiredArgsConstructor
public class FrontendValidationNode implements WorkerNode {

    private final BuildToolService buildTool;
    private final ErrorFixAgent errorFixAgent;

    @Override
    public void execute(WorkerContext ctx) {
        Path frontendDir = ctx.getWorkspaceDir().resolve("frontend");

        BuildResult install = buildTool.runNpmInstall(frontendDir);
        if (!install.success()) throw new WorkerException(FailureType.INFRA,
                "npm install failed:\n" + install.output());

        BuildResult initial = buildTool.runNpmBuild(frontendDir);

        if (initial.success()) {
            log.info("[FrontendValidationNode] npm run build passed — no fixes needed");
            markFilesValidated(ctx);
            return;
        }

        // Pre-ErrorFixAgent mechanical fixes — same pattern as BackendValidationNode:
        //   1. TsxExportGuard: wraps raw JSX files that have no export default (truncated LLM output)
        //   2. NpmPackageFixer: installs missing npm packages / rewrites renamed package imports
        // Both are deterministic and fast. Doing them first saves ErrorFixAgent rounds.
        Path frontendSrc = frontendDir.resolve("src");
        boolean exportsFixed = TsxExportGuard.fix(frontendSrc);
        boolean packagesFixed = NpmPackageFixer.fix(frontendDir, initial.output(), buildTool);

        if (exportsFixed || packagesFixed) {
            BuildResult postFix = buildTool.runNpmBuild(frontendDir);
            if (postFix.success()) {
                log.info("[FrontendValidationNode] npm build passed after mechanical fixes — skipping ErrorFixAgent");
                markFilesValidated(ctx);
                return;
            }
            log.info("[FrontendValidationNode] Mechanical fixes incomplete — handing off to ErrorFixAgent");
        }

        log.warn("[FrontendValidationNode] npm run build failed — starting ErrorFixAgent loop");
        boolean fixed = errorFixAgent.fix(FileType.FRONTEND, ctx);

        if (!fixed) throw new WorkerException(FailureType.CODE,
                "Frontend build could not be fixed after " + ErrorFixAgent.MAX_TOOL_ROUNDS + " agent tool rounds");

        // Final authoritative build after agent loop (agent uses tsc --noEmit; this bundles too)
        BuildResult finalBuild = buildTool.runNpmBuild(frontendDir);
        if (!finalBuild.success()) throw new WorkerException(FailureType.CODE,
                "Frontend npm run build still failing after ErrorFixAgent:\n" + finalBuild.output());

        markFilesValidated(ctx);
    }

    private void markFilesValidated(WorkerContext ctx) {
        try {
            ArchitectureJsonUtil.markAllByTypeAsValidated(ctx.getWorkspaceDir(), "FRONTEND");
            log.info("[FrontendValidationNode] Marked frontend files as VALIDATED in ARCHITECTURE.json");
        } catch (IOException e) {
            log.warn("[FrontendValidationNode] Could not update ARCHITECTURE.json: {}", e.getMessage());
        }
    }
}
