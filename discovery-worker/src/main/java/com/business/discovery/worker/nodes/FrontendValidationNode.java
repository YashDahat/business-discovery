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
import org.springframework.beans.factory.annotation.Value;
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

    /** Enforcement Point B strictness: false (default) = advisory report + warn; true = fail fast on
     *  an unresolved local import. Keep advisory until missing-producer synthesis (Change 2) lands —
     *  mirrors worker.smoke.flows-strict. */
    @Value("${worker.completeness.import-closure-strict:false}")
    private boolean importClosureStrict;

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
                com.business.discovery.worker.util.UiComponentInventory.build(frontendDir),
                com.business.discovery.worker.util.NodeModuleExportRegistry.build(frontendDir));
        boolean svcImportsFixed = com.business.discovery.worker.util.ServiceImportRewriter.fix(frontendSrc);
        //   5b. ProcessEnvPatcher: process.env.X (Next/CRA habit) → import.meta.env.VITE_X (Vite).
        //   5c. TanStackImportFixer: add a react-query hook that's used but never imported (TS2304).
        boolean envFixed = com.business.discovery.worker.util.ProcessEnvPatcher.fix(frontendSrc);
        boolean tanstackFixed = com.business.discovery.worker.util.TanStackImportFixer.fix(frontendSrc);
        //   6. TypeScriptImportFixer: registry-driven correction of wrong @/ and relative import
        //      paths (resolved to where each symbol is actually exported) and default↔named
        //      mismatches (TS2613/TS2614). Runs per-file during generation; re-applying it here on
        //      the complete file set catches cross-file mismatches only visible once every file exists.
        boolean tsImportsFixed = com.business.discovery.worker.util.TypeScriptImportFixer.fixAll(
                frontendSrc, ctx.getWorkspaceDir(),
                com.business.discovery.worker.util.TypeScriptExportRegistry.buildFromDisk(
                        frontendSrc, ctx.getWorkspaceDir()));

        if (exportsFixed || packagesFixed || jsxImportsFixed || uiImportsFixed || svcImportsFixed
                || envFixed || tanstackFixed || tsImportsFixed) {
            BuildResult postFix = buildTool.runNpmBuild(frontendDir);
            if (postFix.success()) {
                log.info("[FrontendValidationNode] npm build passed after mechanical fixes — skipping ErrorFixAgent");
                markFilesValidated(ctx);
                return;
            }
            log.info("[FrontendValidationNode] Mechanical fixes incomplete — handing off to ErrorFixAgent");
        }

        // Enforcement Point B (gen-time closure): after the deterministic path fixers above, every
        // local import must resolve to a real file on disk. An unresolved one is a missing producer —
        // exactly the class the ErrorFixAgent cannot author reliably (AdminLayout survived 3 attempts),
        // and it catches modules invented only at generation (which the plan-time checks cannot see).
        // Advisory by default (report + warn, then let the agent try); strict fails fast to avoid a
        // doomed 30-round loop. Keep advisory until missing-producer synthesis lands.
        java.util.List<com.business.discovery.worker.util.ImportClosureChecker.Unresolved> unresolved =
                com.business.discovery.worker.util.ImportClosureChecker.check(frontendSrc);
        if (!unresolved.isEmpty()) {
            String report = com.business.discovery.worker.util.ImportClosureChecker.render(unresolved);
            com.business.discovery.worker.util.ImportClosureChecker.writeReport(ctx.getWorkspaceDir(), report);
            if (importClosureStrict) {
                // Strict: surface loudly instead of auto-stubbing, for teams who want a real fix.
                throw new WorkerException(FailureType.CODE,
                        "Frontend import closure violated — modules referenced but never generated:\n" + report);
            }
            // Repair (Change 2 synthesis): write permissive placeholders so the imports resolve, then
            // rebuild — a still-failing build now carries only residual type errors for the ErrorFixAgent.
            int synthesized = com.business.discovery.worker.util.MissingModuleSynthesizer.synthesize(
                    frontendSrc, unresolved);
            log.warn("[FrontendValidationNode] Import closure — {} missing module(s); synthesized {} placeholder(s):\n{}",
                    unresolved.size(), synthesized, report);
            if (synthesized > 0) {
                BuildResult postSynth = buildTool.runNpmBuild(frontendDir);
                if (postSynth.success()) {
                    log.info("[FrontendValidationNode] npm build passed after missing-module synthesis — skipping ErrorFixAgent");
                    markFilesValidated(ctx);
                    return;
                }
            }
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
