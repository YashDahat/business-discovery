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
@Order(13)
@Slf4j
@RequiredArgsConstructor
public class FrontendValidationNode implements WorkerNode {

    private final BuildToolService buildTool;
    private final ErrorFixAgent errorFixAgent;

    @Override
    public void execute(WorkerContext ctx) {
        Path frontendDir = ctx.getWorkspaceDir().resolve("frontend");
        Path frontendSrc = frontendDir.resolve("src");

        // Unconditional pre-pass: reconcile the route registry against reality — every
        // manifest route must resolve to a real page (hard fail), every page on disk must be
        // routed (appended), and App.tsx is re-derived from the reconciled manifest. A blank
        // or partial App.tsx COMPILES, so without this it would sail past the build and only
        // trip the routing assertion at the end.
        com.business.discovery.worker.util.RouteManifestReconciler.reconcile(ctx.getWorkspaceDir());

        // Frontend↔backend contract: auto-fix API prefix doubling and report method/path
        // mismatches — runtime bugs the compiler and bundler cannot see.
        com.business.discovery.worker.util.ApiContractChecker.fixAndReport(
                frontendSrc, ctx.getWorkspaceDir().resolve("backend/src/main/java"));

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
        //   3. JsxTypeImportFixer: adds `import type { JSX }` where JSX.Element annotations slipped in
        //   4. UiImportRewriter: inventory-driven correction of invented Radix/shadcn imports
        //   5. ServiceImportRewriter: retargets imports of pruned service modules to the
        //      derived SDK module that actually exports each symbol
        // All deterministic and fast. Doing them first saves ErrorFixAgent rounds.
        boolean exportsFixed = TsxExportGuard.fix(frontendSrc);
        boolean packagesFixed = NpmPackageFixer.fix(frontendDir, initial.output(), buildTool);
        boolean jsxImportsFixed = com.business.discovery.worker.util.JsxTypeImportFixer.fix(frontendSrc);
        boolean uiImportsFixed = com.business.discovery.worker.util.UiImportRewriter.fix(frontendSrc,
                com.business.discovery.worker.util.UiComponentInventory.build(frontendDir));
        boolean svcImportsFixed = com.business.discovery.worker.util.ServiceImportRewriter.fix(frontendSrc);

        if (exportsFixed || packagesFixed || jsxImportsFixed || uiImportsFixed || svcImportsFixed) {
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
        assertAppHasRouting(ctx.getWorkspaceDir());
        try {
            ArchitectureJsonUtil.markAllByTypeAsValidated(ctx.getWorkspaceDir(), "FRONTEND");
            log.info("[FrontendValidationNode] Marked frontend files as VALIDATED in ARCHITECTURE.json");
        } catch (IOException e) {
            log.warn("[FrontendValidationNode] Could not update ARCHITECTURE.json: {}", e.getMessage());
        }
    }

    /**
     * Blank-SPA guard: a compiling, bundling frontend whose App.tsx renders nothing is a
     * white page in production — multifit-aundh shipped `return <div />` while all page
     * content sat in unrouted Next.js-style pages/ files. Compile checks and even the
     * smoke frontend gate (which only asserts the bundle serves) cannot see this; the
     * router wiring is the one structural fact we can assert statically.
     */
    private void assertAppHasRouting(java.nio.file.Path workspace) {
        java.nio.file.Path appTsx = workspace.resolve("frontend/src/App.tsx");
        try {
            if (!java.nio.file.Files.exists(appTsx)) {
                throw new WorkerException(FailureType.CODE,
                        "frontend/src/App.tsx is missing — the SPA entry point was never generated");
            }
            String content = java.nio.file.Files.readString(appTsx);
            boolean hasRouting = content.contains("<Route")
                    || content.contains("createBrowserRouter")
                    || content.contains("RouterProvider");
            if (!hasRouting) {
                throw new WorkerException(FailureType.CODE,
                        "frontend/src/App.tsx contains no router wiring (no <Route>/createBrowserRouter) — "
                        + "the built SPA would render a blank page. App.tsx must declare BrowserRouter + "
                        + "Routes for every page per the architecture spec's FRONTEND ROUTING section.");
            }
        } catch (IOException e) {
            throw new WorkerException(FailureType.CODE,
                    "Could not verify App.tsx routing: " + e.getMessage(), e);
        }
    }
}
