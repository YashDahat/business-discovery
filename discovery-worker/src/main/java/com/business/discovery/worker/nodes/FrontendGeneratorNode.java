package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.model.GeneratedFile;
import com.business.discovery.worker.repository.GeneratedFileRepository;
import com.business.discovery.worker.service.BuildToolService;
import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FeatureSpec;
import com.business.discovery.worker.service.llm.FileEntry;
import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.business.discovery.worker.util.ApiContractCard;
import com.business.discovery.worker.util.ArchitectureJsonUtil;
import com.business.discovery.worker.util.EslintCycleParser;
import com.business.discovery.worker.util.LayerOrderUtil;
import com.business.discovery.worker.util.TruncationDetector;
import com.business.discovery.worker.util.TypeScriptExportRegistry;
import com.business.discovery.worker.util.TypeScriptImportChecker;
import com.business.discovery.worker.util.TypeScriptImportFixer;
import com.business.discovery.worker.service.GitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Order(10)
@Slf4j
public class FrontendGeneratorNode implements WorkerNode {

    // Max concurrent Flash LLM calls per layer — limits Gemini API rate pressure.
    private static final int MAX_PARALLEL_PER_LAYER = 5;

    // Marker written as the first-line comment in every foundation frontend file so isFenced()
    // protects them from LLM overwrite. The LLM must never regenerate AuthContext, api/client,
    // lib/utils, or context shims — these are part of the deterministic foundation scaffold.
    public static final String FOUNDATION_FENCE_MARKER = "GENERATED foundation scaffold";

    // Same-attempt regeneration budget for a file that comes back truncated (mid-token).
    private static final int MAX_GEN_ATTEMPTS = 3;

    // Canonical tsconfig.app.json — written when the LLM-generated one can't be patched.
    // noUnusedLocals/noUnusedParameters are false: LLM code has many transient unused
    // symbols and those tsc errors waste fix-LLM attempts on trivialities.
    // verbatimModuleSyntax is omitted (defaults false) so plain `import` works everywhere.
    private static final String CANONICAL_TSCONFIG_APP = """
            {
              "compilerOptions": {
                "target": "ES2020",
                "useDefineForClassFields": true,
                "lib": ["ES2020", "DOM", "DOM.Iterable"],
                "module": "ESNext",
                "skipLibCheck": true,
                "moduleResolution": "bundler",
                "allowImportingTsExtensions": true,
                "isolatedModules": true,
                "moduleDetection": "force",
                "noEmit": true,
                "jsx": "react-jsx",
                "strict": true,
                "noUnusedLocals": false,
                "noUnusedParameters": false,
                "noFallthroughCasesInSwitch": true,
                "forceConsistentCasingInFileNames": true,
                "paths": { "@/*": ["./src/*"] }
              },
              "include": ["src"]
            }
            """;

    // Canonical vite.config.ts — written at scaffold time and again at each ensureFrontendWorkspace call.
    private static final String CANONICAL_VITE_CONFIG = """
            import path from 'node:path'
            import { defineConfig } from 'vite'
            import react from '@vitejs/plugin-react'

            export default defineConfig({
              plugins: [react()],
              resolve: {
                alias: { '@': path.resolve(__dirname, './src') },
              },
              server: {
                proxy: {
                  '/api': {
                    target: 'http://localhost:8080',
                    changeOrigin: true,
                  },
                },
              },
              build: {
                outDir: 'dist',
              },
            })
            """;

    // Canonical root tsconfig.json — includes @/* paths so shadcn CLI can resolve component aliases.
    private static final String CANONICAL_TSCONFIG_ROOT = """
            {
              "files": [],
              "references": [
                { "path": "./tsconfig.app.json" },
                { "path": "./tsconfig.node.json" }
              ],
              "compilerOptions": {
                "baseUrl": ".",
                "paths": { "@/*": ["./src/*"] }
              }
            }
            """;

    // Minimal ESLint flat config used for our --fix pass AND the cycle check.
    // Ignores shadcn/ui components (generated by CLI, not by us).
    //
    // import-x/no-cycle catches ES-module circular imports (A.tsx→B.tsx→A.tsx), which build
    // clean under tsc+vite and crash only at RUNTIME (TDZ "Cannot access X before
    // initialization"). eslint-plugin-import-x is the flat-config-native fork of
    // eslint-plugin-import and carries the same rule. The typescript resolver is REQUIRED —
    // without it the rule cannot follow the '@/' alias and silently sees zero import edges.
    // ignoreExternal skips node_modules; type-only import cycles are erased at build time and
    // harmless, but the rule still flags them, so real hits are what matter here.
    private static final String ESLINT_FIX_CONFIG = """
            import unusedImports from 'eslint-plugin-unused-imports';
            import importX from 'eslint-plugin-import-x';
            import tsParser from '@typescript-eslint/parser';

            export default [
              { ignores: ['dist', 'src/components/ui'] },
              {
                files: ['**/*.{ts,tsx}'],
                languageOptions: { parser: tsParser },
                plugins: { 'unused-imports': unusedImports, 'import-x': importX },
                settings: {
                  'import-x/resolver': { typescript: { project: './tsconfig.app.json' } },
                },
                rules: {
                  'no-unused-vars': 'off',
                  'unused-imports/no-unused-imports': 'error',
                  'unused-imports/no-unused-vars': [
                    'warn',
                    { vars: 'all', varsIgnorePattern: '^_', args: 'after-used', argsIgnorePattern: '^_' }
                  ],
                  'import-x/no-cycle': ['error', { ignoreExternal: true }],
                },
              },
            ];
            """;

    private final LlmGeneratorService flashLlm;
    private final BuildToolService buildToolService;
    private final GeneratedFileRepository fileRepo;
    private final GitService gitService;

    // Guards concurrent writes to ARCHITECTURE.json from parallel Flash LLM threads within a layer.
    private final ReentrantLock archJsonLock = new ReentrantLock();

    // Playwright e2e scaffold — config + dev dep only (spec SCRIPTS are authored later by
    // PlaywrightSpecGenerator). Skipped entirely when e2e is disabled.
    @org.springframework.beans.factory.annotation.Value("${worker.e2e.enabled:true}")
    private boolean e2eEnabled;
    @org.springframework.beans.factory.annotation.Value("${worker.e2e.playwright-version:1.48.0}")
    private String playwrightVersion;

    public FrontendGeneratorNode(@Qualifier("geminiFlash") LlmGeneratorService flashLlm,
                                 BuildToolService buildToolService,
                                 GeneratedFileRepository fileRepo,
                                 GitService gitService) {
        this.flashLlm = flashLlm;
        this.buildToolService = buildToolService;
        this.fileRepo = fileRepo;
        this.gitService = gitService;
    }

    @Override
    public void execute(WorkerContext ctx) {
        Path workspace = ctx.getWorkspaceDir();
        Path frontendDir = workspace.resolve("frontend");

        ArchitectureSpec architectureSpec = loadSpec(workspace);
        Map<String, FileSpec> specByPath = buildSpecByPath(architectureSpec);
        Map<String, FeatureSpec> featuresByName = buildFeaturesByName(architectureSpec);

        List<FileEntry> frontendFiles = ctx.getFileManifest().stream()
                .filter(e -> e.type() == FileType.FRONTEND)
                .sorted(Comparator.comparingInt(LayerOrderUtil::frontendPriority))
                .toList();

        log.info("[FrontendGeneratorNode] Processing {} frontend files in layer order", frontendFiles.size());

        if (frontendFiles.isEmpty()) {
            log.info("[FrontendGeneratorNode] No frontend files — skipping workspace setup");
            return;
        }

        // Full set of manifest paths — used by TypeScriptImportChecker to distinguish
        // "not generated yet" (pending) from "will never exist" (bad import).
        Set<String> manifestPaths = frontendFiles.stream()
                .map(FileEntry::path)
                .collect(Collectors.toCollection(java.util.HashSet::new));

        // Export registry built incrementally as layers complete — used by TypeScriptImportFixer.
        // Written sequentially (after each layer's parallel block) to avoid concurrent modification.
        TypeScriptExportRegistry exportRegistry = new TypeScriptExportRegistry(workspace);

        boolean requestedChangesMode = ctx.getBriefCtx().requestedChanges() != null
                && !ctx.getBriefCtx().requestedChanges().isBlank();

        if (!Files.exists(frontendDir.resolve("node_modules"))) {
            log.info("[FrontendGeneratorNode] node_modules missing — running npm install");
            BuildToolService.BuildResult install = buildToolService.runNpmInstall(frontendDir);
            if (!install.success()) {
                throw new WorkerException(FailureType.CODE,
                        "npm install failed before generation:\n" + install.output());
            }
            log.info("[FrontendGeneratorNode] npm install complete");
        }

        try {
            ensureFrontendWorkspace(frontendDir, workspace);

            // Canonical commerce spine (CartContext + AddToCartButton + CartItem) — the universal
            // product→cart→order producer the LLM drops across features. Written after shadcn so its
            // button/sonner deps resolve, before generation so consumers see it; fenced from
            // regeneration and auto-mounted by App.tsx provider discovery.
            com.business.discovery.worker.util.CartSpineScaffold.write(frontendDir.resolve("src"));

            // Seed the export registry from ALL foundation files already on disk before generation
            // starts. Without this, TypeScriptImportFixer cannot resolve imports from foundation
            // files that are not in the spec (e.g. AuthContext, context shims, api/client) because
            // the registry only learns about files as they are generated or skipped during this run.
            seedExportRegistryFromDisk(frontendDir.resolve("src"), workspace, exportRegistry);

            // Ground-truth UI inventory: what @radix-ui packages and shadcn ui files ACTUALLY
            // export, enumerated from the installed workspace. Injected into every generation
            // prompt and consumed by UiImportRewriter — replaces hardcoded component knowledge.
            this.uiInventory = com.business.discovery.worker.util.UiComponentInventory.build(frontendDir);
            this.uiInventory.writeJson(workspace);
            log.info("[FrontendGeneratorNode] UI inventory: {} radix packages, {} shadcn ui files",
                    uiInventory.radixExports().size(), uiInventory.shadcnUiExports().size());

            // Ground-truth API contract: the types + SDK ApiArtifactGeneratorNode derived from
            // the compiled backend, read back off disk and injected into EVERY generation prompt.
            // Not routed through the spec's imports_from — the planner left that empty for 31 of
            // 81 frontend files (circuit-house), including the very component whose job was to
            // render an order, which then invented three fields OrderDto does not have.
            this.contractCard = com.business.discovery.worker.util.ApiContractCard.build(workspace);
            if (contractCard.isEmpty()) {
                log.warn("[FrontendGeneratorNode] No derived API contract on disk — generation will "
                        + "run without wire ground truth (did ApiArtifactGeneratorNode extract?)");
            } else {
                log.info("[FrontendGeneratorNode] API contract card: {} type file(s), {} route(s)",
                        contractCard.typeFileCount(), contractCard.routeCount());
            }

            // Ground-truth navigation contract: routes.ts + App.tsx derived from the plan's
            // PAGE entries BEFORE any layer generates, so every component is shown the legal
            // link destinations (circuit-house: Flash-written App.tsx registered 1 admin route
            // of 7 — six compiled pages were unreachable blank screens).
            synthesizeRouteRegistry(ctx, workspace, frontendDir, frontendFiles, manifestPaths);

            // Group files by layer priority — within a layer, files don't depend on each other
            // and can be generated concurrently. TreeMap gives sorted layer iteration.
            TreeMap<Integer, List<FileEntry>> filesByLayer = frontendFiles.stream()
                    .collect(Collectors.groupingBy(LayerOrderUtil::frontendPriority,
                            TreeMap::new, Collectors.toList()));

            int filesProcessed = 0;

            for (Map.Entry<Integer, List<FileEntry>> layerEntry : filesByLayer.entrySet()) {
                List<FileEntry> layerFiles = layerEntry.getValue();
                String layerLabel = layerFiles.isEmpty() ? "?" : layerName(layerFiles.get(0));

                // Register skipped files' exports BEFORE parallel generation so TypeScriptImportFixer
                // can resolve imports from already-generated files in subsequent layers.
                for (FileEntry entry : layerFiles) {
                    FileSpec spec = specByPath.get(entry.path());
                    FeatureSpec feature = spec != null && spec.getFeatureName() != null
                            ? featuresByName.get(spec.getFeatureName()) : null;
                    Path filePath = workspace.resolve(entry.path());
                    if (isFenced(entry.path(), spec, workspace)
                            || shouldSkip(spec, feature, requestedChangesMode, Files.exists(filePath))) {
                        if (Files.exists(filePath)) {
                            String skippedContent = Files.readString(filePath);
                            exportRegistry.register(filePath, skippedContent);
                            frontendContractCard.register(filePath, skippedContent);
                        }
                    }
                }

                List<FileEntry> toGenerate = layerFiles.stream()
                        .filter(e -> {
                            // Fence wins over shouldSkip and applies in BOTH modes —
                            // requestedChangesMode's shouldSkip ignores VALIDATED status, which
                            // used to let Flash overwrite a derived service on update runs.
                            if (isFenced(e.path(), specByPath.get(e.path()), workspace)) {
                                log.warn("[FrontendGeneratorNode] FENCED — refusing to LLM-generate "
                                        + "derived surface {}", e.path());
                                return false;
                            }
                            FileSpec spec = specByPath.get(e.path());
                            FeatureSpec feature = spec != null && spec.getFeatureName() != null
                                    ? featuresByName.get(spec.getFeatureName()) : null;
                            boolean exists = Files.exists(workspace.resolve(e.path()));
                            return !shouldSkip(spec, feature, requestedChangesMode, exists);
                        })
                        .toList();

                filesProcessed += layerFiles.size();

                if (toGenerate.isEmpty()) {
                    log.info("[FrontendGeneratorNode] Layer [{}] — all {} file(s) already done, skipping",
                            layerLabel, layerFiles.size());
                    continue;
                }

                int skipped = layerFiles.size() - toGenerate.size();
                log.info("[FrontendGeneratorNode] Layer [{}] — generating {} file(s) in parallel (skipping {})",
                        layerLabel, toGenerate.size(), skipped);

                int parallelism = Math.min(toGenerate.size(), MAX_PARALLEL_PER_LAYER);
                ExecutorService executor = Executors.newFixedThreadPool(parallelism);
                List<CompletableFuture<Void>> futures = toGenerate.stream()
                        .map(entry -> CompletableFuture.runAsync(() -> {
                            FileSpec spec = specByPath.get(entry.path());
                            FeatureSpec feature = spec != null && spec.getFeatureName() != null
                                    ? featuresByName.get(spec.getFeatureName()) : null;
                            Path filePath = workspace.resolve(entry.path());
                            // On update runs this appends the change directive — the request's
                            // only route into the generation prompt (enrichment doesn't re-run).
                            String featureInstruction = feature != null
                                    ? feature.effectiveInstruction(requestedChangesMode) : null;
                            String fileRole = spec != null ? spec.getFileRole() : null;

                            if (featureInstruction == null || featureInstruction.isBlank()) {
                                log.warn("[FrontendGeneratorNode] No featureInstruction for '{}' (file: {}) — skipping",
                                        spec != null ? spec.getFeatureName() : "unknown", entry.path());
                                return;
                            }
                            try {
                                runGenerateStage(ctx, workspace, entry, filePath, spec,
                                        featureInstruction, fileRole, requestedChangesMode, feature,
                                        manifestPaths, exportRegistry);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        }, executor))
                        .toList();

                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                } catch (CompletionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof WorkerException we) throw we;
                    if (cause instanceof UncheckedIOException uioe) {
                        throw new WorkerException(FailureType.INFRA,
                                "File I/O error during frontend generation: " + uioe.getMessage(), uioe);
                    }
                    throw new WorkerException(FailureType.INFRA,
                            "Parallel generation failed in layer [" + layerLabel + "]: " + e.getMessage(), e);
                } finally {
                    executor.shutdownNow();
                }

                // Register newly generated files' exports after the parallel block so subsequent
                // layers can resolve imports from this layer (sequential, no concurrent access).
                for (FileEntry entry : toGenerate) {
                    Path filePath = workspace.resolve(entry.path());
                    if (Files.exists(filePath)) {
                        try {
                            String generatedContent = Files.readString(filePath);
                            exportRegistry.register(filePath, generatedContent);
                            frontendContractCard.register(filePath, generatedContent);
                        } catch (IOException e) {
                            log.warn("[FrontendGeneratorNode] Could not register exports for {}: {}",
                                    entry.path(), e.getMessage());
                        }
                    }
                }

                // Checkpoint after each layer — work survives a container kill between layers
                gitService.commitAndPushCheckpoint(workspace,
                        "chore: frontend wip — " + layerLabel + " layer, " + filesProcessed
                                + " files (attempt " + ctx.getAttemptNumber() + ")",
                        ctx.getGithubBranch());
            }

            // Post-generation: scan all generated files for shadcn usage and run ESLint.
            // These must run after generation so LLM-generated imports are visible.
            try {
                ensureShadcnComponents(frontendDir, workspace);
            } catch (IOException e) {
                log.warn("[FrontendGeneratorNode] Post-generation shadcn scan failed: {}", e.getMessage());
            }
            runEslintPass(frontendDir);
            enforceNoCycles(frontendDir);

        } catch (WorkerException e) {
            throw e;
        } catch (IOException e) {
            throw new WorkerException(FailureType.INFRA,
                    "Failed during frontend generation: " + e.getMessage(), e);
        }

        log.info("[FrontendGeneratorNode] Done — {} frontend files processed", frontendFiles.size());
    }

    // ── PLANNED → GENERATED ───────────────────────────────────────────────────

    // Set once per execute() before the parallel generation block; read-only afterwards.
    private volatile com.business.discovery.worker.util.UiComponentInventory uiInventory;
    private volatile com.business.discovery.worker.util.ApiContractCard contractCard;
    private volatile String routeCardSection;
    private com.business.discovery.worker.util.FrontendContractCard frontendContractCard =
            new com.business.discovery.worker.util.FrontendContractCard();

    /**
     * Derives routes.ts + App.tsx from the plan's PAGE entries before any layer generates.
     * Both are re-derived every attempt (idempotent) and marked VALIDATED so shouldSkip
     * excludes them from Flash; isFenced covers update runs where status is ignored.
     */
    private void synthesizeRouteRegistry(WorkerContext ctx, Path workspace, Path frontendDir,
                                         List<FileEntry> frontendFiles,
                                         Set<String> manifestPaths) throws IOException {
        com.business.discovery.worker.util.RouteManifest manifest =
                com.business.discovery.worker.util.RouteManifest.fromSpec(frontendFiles);
        if (manifest.isEmpty()) {
            log.warn("[FrontendGeneratorNode] No PAGE entries in the plan — route registry skipped");
            this.routeCardSection = null;
            return;
        }

        Path frontendSrc = frontendDir.resolve("src");
        boolean hasAuth = manifestPaths.contains("frontend/src/context/AuthContext.tsx")
                || Files.exists(frontendSrc.resolve("context/AuthContext.tsx"));
        boolean hasProtected = manifestPaths.contains("frontend/src/components/ProtectedRoute.tsx")
                || Files.exists(frontendSrc.resolve("components/ProtectedRoute.tsx"));
        boolean hasQuery = manifestPaths.contains("frontend/src/api/client.ts")
                || Files.exists(frontendSrc.resolve("api/client.ts"));
        var flags = new com.business.discovery.worker.util.RouteManifestGenerator.Flags(
                hasAuth, hasProtected, hasQuery,
                com.business.discovery.worker.util.RouteManifestGenerator.discoverContextProviders(frontendSrc));

        Files.createDirectories(frontendSrc);
        Files.writeString(frontendSrc.resolve("routes.ts"),
                com.business.discovery.worker.util.RouteManifestGenerator.emitRoutesTs(manifest));
        Files.writeString(frontendSrc.resolve("App.tsx"),
                com.business.discovery.worker.util.RouteManifestGenerator.emitAppTsx(manifest, flags));
        log.info("[FrontendGeneratorNode] Route registry derived — {} routes into routes.ts + App.tsx",
                manifest.entries().size());

        manifestPaths.add("frontend/src/routes.ts");
        markDerived(ctx, workspace, "frontend/src/routes.ts", true);
        markDerived(ctx, workspace, "frontend/src/App.tsx", false);

        this.routeCardSection = com.business.discovery.worker.util.RouteManifest.PROMPT_KEY
                + "\n" + manifest.toPromptSection();
    }

    /** VALIDATED in ARCHITECTURE.json (adding an entry if unplanned) + the DB record. */
    private void markDerived(WorkerContext ctx, Path workspace, String path, boolean addIfMissing) {
        archJsonLock.lock();
        try {
            if (ArchitectureJsonUtil.exists(workspace)) {
                if (ArchitectureJsonUtil.findByPath(workspace, path).isPresent()) {
                    ArchitectureJsonUtil.updateFileStatus(workspace, path, "VALIDATED");
                } else if (addIfMissing) {
                    ArchitectureJsonUtil.addFile(workspace, FileSpec.builder()
                            .fileName(path.substring(path.lastIndexOf('/') + 1))
                            .filePath(path)
                            .fileType("FRONTEND")
                            .layer("UTIL")
                            .status("VALIDATED")
                            .description("Derived route registry — regenerated every attempt, never LLM-written")
                            .build());
                }
            }
        } catch (IOException e) {
            log.warn("[FrontendGeneratorNode] Could not mark {} VALIDATED in spec: {}", path, e.getMessage());
        } finally {
            archJsonLock.unlock();
        }
        upsertRecord(ctx, path, GeneratedFile.FileType.FRONTEND, GeneratedFile.FileStatus.VALIDATED);
    }

    // ── Generator fence ───────────────────────────────────────────────────────

    /**
     * True when Flash must never write this path: the derived-surface fence. Wins over
     * shouldSkip in BOTH modes — on update runs shouldSkip ignores VALIDATED status, which
     * used to let a changed feature pull a derived service back into LLM generation.
     *
     * Covers: routes.ts + App.tsx (re-derived every attempt), frontend/src/services/**
     * outside services/local/ unless the spec says VALIDATED came from derivation, and any
     * on-disk file whose first line carries a generated marker.
     */
    private boolean isFenced(String path, FileSpec spec, Path workspace) {
        String p = path.replace('\\', '/');
        if (p.equals("frontend/src/routes.ts") || p.equals("frontend/src/App.tsx")) {
            // Only fenced once the registry exists — an empty-plan run leaves App.tsx to Flash.
            return routeCardSection != null;
        }
        boolean inServices = p.startsWith("frontend/src/services/")
                && !p.startsWith("frontend/src/services/local/") && p.endsWith(".ts");
        // Derivation owns services/ — but only once it has actually claimed the file
        // (VALIDATED or marker on disk). A parser-gap run where ApiArtifactGeneratorNode
        // extracted nothing deliberately falls back to LLM generation, so an unclaimed,
        // absent service stays generatable.
        if (inServices && spec != null && "VALIDATED".equalsIgnoreCase(spec.getStatus())) {
            return true;
        }
        Path onDisk = workspace.resolve(p);
        if (Files.exists(onDisk)) {
            try (var lines = Files.lines(onDisk)) {
                String first = lines.findFirst().orElse("");
                return first.contains("GENERATED from the backend API contract")
                        || first.contains("GENERATED from the architecture plan")
                        || first.contains(com.business.discovery.worker.util.CartSpineScaffold.FENCE_MARKER)
                        || first.contains(FOUNDATION_FENCE_MARKER);
            } catch (IOException ignored) {
                return false;
            }
        }
        return false;
    }

    private void runGenerateStage(WorkerContext ctx, Path workspace, FileEntry entry,
                                  Path filePath, FileSpec spec, String featureInstruction,
                                  String fileRole, boolean requestedChangesMode,
                                  FeatureSpec feature, Set<String> manifestPaths,
                                  TypeScriptExportRegistry exportRegistry) throws IOException {
        Map<String, String> depFiles = loadDependencyFiles(workspace, spec, exportRegistry);
        if (uiInventory != null && !uiInventory.isEmpty()) {
            depFiles.put("AVAILABLE UI IMPORTS (ground truth — import ONLY names listed here)",
                    uiInventory.toPromptSection());
        }
        // Closed-world catalog of every module generated by earlier layers. Layers run in dependency
        // order, so this file's components/hooks/types/services already exist and are registered —
        // hand Flash their real names/paths so it imports them instead of inventing modules the
        // post-hoc fixer (and then Sonnet) must repair. Rides the per-file user prompt, not the cached
        // system slot, because the catalog grows each layer and so cannot be byte-identical per call.
        if (exportRegistry != null && !exportRegistry.isEmpty()) {
            depFiles.put("MODULES THAT ALREADY EXIST (import ONLY these, by these exact names/paths — "
                            + "anything not listed here does not exist yet)",
                    exportRegistry.toImportCatalog());
        }
        String existingContent = (requestedChangesMode && feature != null && feature.isChangeRequired())
                ? readIfExists(filePath) : null;

        // Generate with same-attempt retry on truncation. A file cut mid-token (Flash hit its
        // output-token ceiling) is regenerated in place rather than committed. The old path wrote
        // the partial and deferred regeneration to the NEXT worker attempt — so the broken file was
        // committed, masked every other error from the validator (yeti: 7 visible vs 94 real), and
        // burned a 30-round fix session first. The root cause is also addressed upstream by disabling
        // Flash thinking (GeminiLlmGeneratorService), so a retry here almost always succeeds.
        String contractSection = (contractCard != null && !contractCard.isEmpty())
                ? ApiContractCard.PROMPT_KEY + "\n" + contractCard.toPromptSection()
                : null;
        // Route card rides the same cacheable system-prompt slot — built once per run,
        // byte-identical across calls, so it prefix-caches exactly like the contract card.
        if (routeCardSection != null) {
            contractSection = contractSection == null
                    ? routeCardSection : contractSection + "\n\n" + routeCardSection;
        }
        // Frontend contract card: hook return types, context signatures, local type field shapes
        // extracted from all layers completed before this file's layer. Empty for the first layer;
        // grows incrementally so each layer sees contracts from all prior layers.
        if (!frontendContractCard.isEmpty()) {
            String fcSection = "FRONTEND MODULE CONTRACTS\n" + frontendContractCard.toPromptSection();
            contractSection = contractSection == null ? fcSection : contractSection + "\n\n" + fcSection;
        }

        String content = null;
        for (int genAttempt = 1; genAttempt <= MAX_GEN_ATTEMPTS; genAttempt++) {
            String candidate = flashLlm.generateFileContent(entry.path(), featureInstruction,
                    fileRole != null ? fileRole : "", depFiles, existingContent, contractSection);
            if (!TruncationDetector.looksTruncated(candidate)) {
                content = candidate;
                break;
            }
            log.warn("[FrontendGeneratorNode] Truncated output for {} (attempt {}/{}) — regenerating",
                    entry.path(), genAttempt, MAX_GEN_ATTEMPTS);
        }

        Files.createDirectories(filePath.getParent());

        if (content == null) {
            // Every attempt truncated. Do NOT write the partial — an incomplete file masks errors
            // and wastes fix-LLM rounds. Discard it and leave the path absent + GENERATION_FAILED so
            // a later worker attempt regenerates it (shouldSkip does not skip GENERATION_FAILED).
            log.warn("[FrontendGeneratorNode] {} still truncated after {} attempts — GENERATION_FAILED, "
                    + "partial discarded (not written)", entry.path(), MAX_GEN_ATTEMPTS);
            Files.deleteIfExists(filePath);
            archJsonLock.lock();
            try {
                ArchitectureJsonUtil.updateFileStatus(workspace, entry.path(), "GENERATION_FAILED");
            } finally {
                archJsonLock.unlock();
            }
            upsertRecord(ctx, entry.path(), GeneratedFile.FileType.FRONTEND, GeneratedFile.FileStatus.GENERATION_FAILED);
            return;
        }

        Files.writeString(filePath, content);
        // Fix broken @/ and relative import paths using the export registry (zero LLM).
        // exportRegistry is read-only at this point — writes happen after the parallel block.
        TypeScriptImportFixer.fix(filePath, workspace, exportRegistry);
        // Types files must export every declaration — LLM often forgets 'export' on some of them.
        if (entry.path().contains("/types/")) {
            ensureAllTypesExported(filePath);
        }
        TypeScriptImportChecker.check(filePath, workspace, manifestPaths);

        archJsonLock.lock();
        try {
            ArchitectureJsonUtil.updateFileStatus(workspace, entry.path(), "GENERATED");
        } finally {
            archJsonLock.unlock();
        }
        upsertRecord(ctx, entry.path(), GeneratedFile.FileType.FRONTEND, GeneratedFile.FileStatus.GENERATED);
        // exportRegistry.register() is intentionally NOT called here — done after the parallel block
        // in execute() to avoid concurrent modification of the shared registry.
    }

    // ── Frontend workspace setup (runs once per attempt before generation) ────

    // Matches both canonical @/ alias and relative ../ui/ imports generated by LLMs
    // Case-insensitive on the component stem — the LLM sometimes writes @/components/ui/Select.
    // The captured name is lowercased by detectShadcnComponents so the (lowercase) file gets installed,
    // and UiImportRewriter separately normalizes the import path to the canonical lowercase form.
    private static final Pattern SHADCN_IMPORT =
            Pattern.compile("from\\s+['\"](?:@/components/ui|[./]+(?:components/)?ui)/([A-Za-z][A-Za-z0-9-]*)");

    // Parsed from `npx shadcn@latest search @shadcn` at first use — cached for the container lifetime.
    // null = not yet fetched. Empty set = fetch failed (fall back to allowing all).
    private static volatile java.util.Set<String> cachedValidShadcnComponents = null;
    private static final Pattern SHADCN_REGISTRY_LINE =
            Pattern.compile("^-\\s+@shadcn/([a-z][a-z0-9-]+)\\s+\\(ui\\)");
    // Deprecated components the CLI refuses to install — map to their replacement
    private static final Map<String, String> SHADCN_DEPRECATED = Map.of(
            "toast",     "sonner",
            "use-toast", "sonner"
    );

    /**
     * Pre-seeds the export registry from every .ts/.tsx file already on disk in the frontend/src
     * tree before generation starts. This covers foundation files that are not in the spec and
     * therefore never go through the normal skipped-file registration path:
     * AuthContext, context shims (CartContext re-export, CheckoutContext), api/client, etc.
     * Without this, TypeScriptImportFixer cannot resolve imports like
     * {@code import { useAuth } from '@/context/AuthContext'} because the registry is empty
     * at the start of generation and only learns about files as they are processed.
     * Idempotent — a file registered twice simply overwrites its entry with the same data.
     */
    private void seedExportRegistryFromDisk(Path frontendSrc, Path workspace,
                                            TypeScriptExportRegistry exportRegistry) {
        if (!Files.isDirectory(frontendSrc)) return;
        int seeded = 0;
        try (var walk = Files.walk(frontendSrc)) {
            for (Path p : (Iterable<Path>) walk.filter(f ->
                    f.toString().endsWith(".tsx") || f.toString().endsWith(".ts"))
                    .filter(f -> !f.toString().contains("node_modules"))
                    .filter(f -> !f.toString().contains("/components/ui/")) // shadcn handled by UiInventory
                    ::iterator) {
                try {
                    String content = Files.readString(p);
                    exportRegistry.register(p, content);
                    frontendContractCard.register(p, content);
                    seeded++;
                } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            log.warn("[FrontendGeneratorNode] Could not seed export registry from disk: {}", e.getMessage());
        }
        if (seeded > 0) log.info("[FrontendGeneratorNode] Seeded export registry from {} pre-existing files", seeded);
    }

    private void ensureFrontendWorkspace(Path frontendDir, Path workspace) throws IOException {
        writeCanonicalConfigs(frontendDir);
        ensurePlaywrightScaffold(frontendDir);
        ensureTailwind(frontendDir);
        ensureHookformResolvers(frontendDir);
        ensureRadixUiPackages(frontendDir);
        preinstallShadcnBaseSet(frontendDir);
        ensureShadcnComponents(frontendDir, workspace);
        ensureEslintSetup(frontendDir);
        runEslintPass(frontendDir);
    }

    // Canonical playwright.config.ts — deterministic scaffold (never LLM-authored). The e2e spec
    // SCRIPTS under frontend/e2e/ are generated separately by PlaywrightSpecGenerator; this writes
    // only config + the dev dependency, exactly like CANONICAL_VITE_CONFIG / tsconfig above.
    private static final String CANONICAL_PLAYWRIGHT_CONFIG = """
            import { defineConfig, devices } from '@playwright/test'

            // Scaffolded — do not edit. Feature e2e specs live in ./e2e (one per feature).
            // baseURL is injected by the pipeline runner via the BASE_URL env var.
            export default defineConfig({
              testDir: './e2e',
              timeout: 30_000,
              expect: { timeout: 7_000 },
              fullyParallel: false,
              retries: 0,
              reporter: [['list'], ['json', { outputFile: 'e2e-results.json' }]],
              use: {
                baseURL: process.env.BASE_URL || 'http://localhost:8080',
                trace: 'off',
                screenshot: 'only-on-failure',
              },
              projects: [
                { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
              ],
            })
            """;

    /**
     * Deterministic Playwright setup: writes the canonical config + the e2e/ dir and adds
     * {@code @playwright/test} as a dev dependency. No test content — that is authored later.
     * Guarded by worker.e2e.enabled so a disabled pipeline ships no Playwright footprint.
     */
    private void ensurePlaywrightScaffold(Path frontendDir) throws IOException {
        if (!e2eEnabled) return;
        Files.writeString(frontendDir.resolve("playwright.config.ts"), CANONICAL_PLAYWRIGHT_CONFIG);
        Files.createDirectories(frontendDir.resolve("e2e"));
        log.info("[FrontendGeneratorNode] Wrote canonical playwright.config.ts");

        Path pkgPath = frontendDir.resolve("package.json");
        if (Files.exists(pkgPath) && !Files.readString(pkgPath).contains("@playwright/test")) {
            BuildToolService.BuildResult r = buildToolService.runNpmInstallDevPackages(
                    frontendDir, "@playwright/test@" + playwrightVersion);
            if (r.success()) log.info("[FrontendGeneratorNode] Installed @playwright/test@{} (dev)", playwrightVersion);
            else log.warn("[FrontendGeneratorNode] @playwright/test install failed: {}", r.output());
        }
    }

    private void writeCanonicalConfigs(Path frontendDir) throws IOException {
        Path viteConfig = frontendDir.resolve("vite.config.ts");
        if (Files.exists(viteConfig)) {
            Files.writeString(viteConfig, CANONICAL_VITE_CONFIG);
            log.info("[FrontendGeneratorNode] Wrote canonical vite.config.ts");
        }
        Path tsconfigApp = frontendDir.resolve("tsconfig.app.json");
        if (Files.exists(tsconfigApp)) {
            Files.writeString(tsconfigApp, CANONICAL_TSCONFIG_APP);
            log.info("[FrontendGeneratorNode] Wrote canonical tsconfig.app.json");
        }
        Path tsconfigRoot = frontendDir.resolve("tsconfig.json");
        if (Files.exists(tsconfigRoot)) {
            Files.writeString(tsconfigRoot, CANONICAL_TSCONFIG_ROOT);
            log.info("[FrontendGeneratorNode] Wrote canonical tsconfig.json");
        }
        // vite-env.d.ts — declares CSS/SVG/PNG module types so tsc accepts Vite asset imports.
        Path srcDir = frontendDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("vite-env.d.ts"), "/// <reference types=\"vite/client\" />\n");
        log.info("[FrontendGeneratorNode] Wrote src/vite-env.d.ts");

        // App.tsx — clean shell with no Vite demo imports. Write-if-missing ONLY: the route
        // registry re-derives the real App.tsx right after this, and an unconditional write
        // would re-blank a synthesized App.tsx on any path that skips synthesis.
        Path appTsx = srcDir.resolve("App.tsx");
        if (!Files.exists(appTsx)) {
            Files.writeString(appTsx, CANONICAL_APP_TSX);
            log.info("[FrontendGeneratorNode] Wrote canonical App.tsx (was missing)");
        }

        Path mainTsx = srcDir.resolve("main.tsx");
        Files.writeString(mainTsx, CANONICAL_MAIN_TSX);
        log.info("[FrontendGeneratorNode] Wrote canonical main.tsx");
    }

    private static final String CANONICAL_APP_TSX = """
            import './index.css'

            export default function App() {
              return <div />
            }
            """;

    private static final String CANONICAL_MAIN_TSX = """
            import { StrictMode } from 'react'
            import { createRoot } from 'react-dom/client'
            import './index.css'
            import App from './App'

            createRoot(document.getElementById('root')!).render(
              <StrictMode>
                <App />
              </StrictMode>
            )
            """;

    private static final String POSTCSS_CONFIG = """
            export default {
              plugins: {
                tailwindcss: {},
                autoprefixer: {},
              },
            }
            """;

    private static final String TAILWIND_CONFIG = """
            import type { Config } from "tailwindcss"
            const config: Config = {
              darkMode: ["class"],
              content: ["./index.html", "./src/**/*.{ts,tsx,js,jsx}"],
              theme: {
                extend: {
                  colors: {
                    border: "hsl(var(--border))",
                    input: "hsl(var(--input))",
                    ring: "hsl(var(--ring))",
                    background: "hsl(var(--background))",
                    foreground: "hsl(var(--foreground))",
                    primary: { DEFAULT: "hsl(var(--primary))", foreground: "hsl(var(--primary-foreground))" },
                    secondary: { DEFAULT: "hsl(var(--secondary))", foreground: "hsl(var(--secondary-foreground))" },
                    destructive: { DEFAULT: "hsl(var(--destructive))", foreground: "hsl(var(--destructive-foreground))" },
                    muted: { DEFAULT: "hsl(var(--muted))", foreground: "hsl(var(--muted-foreground))" },
                    accent: { DEFAULT: "hsl(var(--accent))", foreground: "hsl(var(--accent-foreground))" },
                    popover: { DEFAULT: "hsl(var(--popover))", foreground: "hsl(var(--popover-foreground))" },
                    card: { DEFAULT: "hsl(var(--card))", foreground: "hsl(var(--card-foreground))" },
                  },
                  borderRadius: {
                    lg: "var(--radius)",
                    md: "calc(var(--radius) - 2px)",
                    sm: "calc(var(--radius) - 4px)",
                  },
                },
              },
              plugins: [],
            }
            export default config
            """;

    private static final String TAILWIND_INDEX_CSS = """
            @tailwind base;
            @tailwind components;
            @tailwind utilities;

            @layer base {
              :root {
                --background: 0 0% 100%;
                --foreground: 222.2 47.4% 11.2%;
                --card: 0 0% 100%;
                --card-foreground: 222.2 47.4% 11.2%;
                --popover: 0 0% 100%;
                --popover-foreground: 222.2 47.4% 11.2%;
                --primary: 222.2 47.4% 11.2%;
                --primary-foreground: 210 40% 98%;
                --secondary: 210 40% 96.1%;
                --secondary-foreground: 222.2 47.4% 11.2%;
                --muted: 210 40% 96.1%;
                --muted-foreground: 215.4 16.3% 46.9%;
                --accent: 210 40% 96.1%;
                --accent-foreground: 222.2 47.4% 11.2%;
                --destructive: 0 100% 50%;
                --destructive-foreground: 210 40% 98%;
                --border: 214.3 31.8% 91.4%;
                --input: 214.3 31.8% 91.4%;
                --ring: 215 20.2% 65.1%;
                --radius: 0.5rem;
              }
              .dark {
                --background: 224 71% 4%;
                --foreground: 213 31% 91%;
                --card: 224 71% 4%;
                --card-foreground: 213 31% 91%;
                --popover: 224 71% 4%;
                --popover-foreground: 215 20.2% 65.1%;
                --primary: 210 40% 98%;
                --primary-foreground: 222.2 47.4% 1.2%;
                --secondary: 222.2 47.4% 11.2%;
                --secondary-foreground: 210 40% 98%;
                --muted: 223 47% 11%;
                --muted-foreground: 215.4 16.3% 56.9%;
                --accent: 216 34% 17%;
                --accent-foreground: 210 40% 98%;
                --destructive: 0 63% 31%;
                --destructive-foreground: 210 40% 98%;
                --border: 216 34% 17%;
                --input: 216 34% 17%;
                --ring: 216 34% 17%;
              }
            }

            @layer base {
              * { @apply border-border; }
              body { @apply bg-background text-foreground; margin: 0; }
            }
            """;

    private void ensureTailwind(Path frontendDir) throws IOException {
        Path pkgPath = frontendDir.resolve("package.json");
        if (!Files.exists(pkgPath)) return;

        if (!Files.readString(pkgPath).contains("\"tailwindcss\"")) {
            log.info("[FrontendGeneratorNode] Installing tailwindcss v3 + postcss + autoprefixer");
            BuildToolService.BuildResult r = buildToolService.runNpmInstallDevPackages(
                    frontendDir, "tailwindcss@^3.4.1", "postcss@^8.4.33", "autoprefixer@^10.4.17");
            if (r.success()) log.info("[FrontendGeneratorNode] Tailwind stack installed");
            else log.warn("[FrontendGeneratorNode] Tailwind install failed (exit={}): {}", r.exitCode(), r.output());
        } else {
            log.info("[FrontendGeneratorNode] tailwindcss already present");
        }

        Path postcssConfig = frontendDir.resolve("postcss.config.js");
        if (!Files.exists(postcssConfig)) {
            Files.writeString(postcssConfig, POSTCSS_CONFIG);
            log.info("[FrontendGeneratorNode] Wrote postcss.config.js");
        }

        Path tailwindConfig = frontendDir.resolve("tailwind.config.ts");
        if (!Files.exists(tailwindConfig)) {
            Files.writeString(tailwindConfig, TAILWIND_CONFIG);
            log.info("[FrontendGeneratorNode] Wrote tailwind.config.ts");
        }

        Path indexCss = frontendDir.resolve("src/index.css");
        if (!Files.exists(indexCss) || !Files.readString(indexCss).contains("@tailwind")) {
            Files.writeString(indexCss, TAILWIND_INDEX_CSS);
            log.info("[FrontendGeneratorNode] Wrote src/index.css with Tailwind directives + shadcn CSS variables");
        }
    }

    private void ensureHookformResolvers(Path frontendDir) throws IOException {
        Path pkgPath = frontendDir.resolve("package.json");
        if (!Files.exists(pkgPath)) return;
        if (Files.readString(pkgPath).contains("@hookform/resolvers")) {
            log.info("[FrontendGeneratorNode] @hookform/resolvers already in package.json");
            return;
        }
        log.info("[FrontendGeneratorNode] Installing @hookform/resolvers (always required)");
        BuildToolService.BuildResult r = buildToolService.runNpmInstallPackage(
                frontendDir, "@hookform/resolvers");
        if (r.success()) log.info("[FrontendGeneratorNode] Installed @hookform/resolvers");
        else log.warn("[FrontendGeneratorNode] @hookform/resolvers install failed: {}", r.output());
    }

    private static final List<String> RADIX_CORE_PACKAGES = List.of(
            "@radix-ui/react-select", "@radix-ui/react-dialog",
            "@radix-ui/react-label", "@radix-ui/react-checkbox",
            "@radix-ui/react-alert-dialog", "clsx"
    );

    private void ensureRadixUiPackages(Path frontendDir) throws IOException {
        Path pkgPath = frontendDir.resolve("package.json");
        if (!Files.exists(pkgPath)) return;
        String pkgJson = Files.readString(pkgPath);
        List<String> missing = RADIX_CORE_PACKAGES.stream()
                .filter(pkg -> !pkgJson.contains("\"" + pkg + "\""))
                .toList();
        if (missing.isEmpty()) {
            log.info("[FrontendGeneratorNode] All Radix UI core packages present");
            return;
        }
        log.info("[FrontendGeneratorNode] Installing missing Radix UI packages: {}", missing);
        for (String pkg : missing) {
            BuildToolService.BuildResult r = buildToolService.runNpmInstallPackage(frontendDir, pkg);
            if (r.success()) log.info("[FrontendGeneratorNode] Installed {}", pkg);
            else log.warn("[FrontendGeneratorNode] Failed to install {} (exit={}): {}", pkg, r.exitCode(), r.output());
        }
    }

    /**
     * Curated shadcn component set pre-installed at scaffold time (before the UI inventory is built
     * and before generation). shadcn is preferred over raw @radix-ui/* because it wraps radix with
     * consistent styling and a stable API; installing these also pulls the needed radix peers plus
     * lucide-react (icons), @hookform/resolvers (via form), sonner (toast) and date-fns (via
     * calendar) transitively — removing several missing-package error classes at once.
     */
    private static final List<String> SHADCN_BASE_SET = List.of(
            "button", "input", "textarea", "label", "select", "checkbox", "radio-group", "form", "switch",
            "dialog", "alert-dialog", "dropdown-menu", "popover", "tooltip", "sheet",
            "table", "card", "badge", "separator", "tabs", "accordion", "avatar", "skeleton",
            "sonner", "calendar");

    /** Proactively install the base set so src/components/ui/ is fully populated before generation. */
    private void preinstallShadcnBaseSet(Path frontendDir) throws IOException {
        installShadcnComponents(frontendDir, new TreeSet<>(SHADCN_BASE_SET));
    }

    /** Post-generation top-up: install any shadcn component the generator imported beyond the base set. */
    private void ensureShadcnComponents(Path frontendDir, Path workspace) throws IOException {
        installShadcnComponents(frontendDir, detectShadcnComponents(workspace));
    }

    /**
     * Installs the requested shadcn components not already on disk, setting up components.json /
     * tsconfig paths / lib utils as needed. Shared by the base-set pre-install and the post-gen top-up.
     */
    private void installShadcnComponents(Path frontendDir, Set<String> requested) throws IOException {
        if (requested.isEmpty()) return;
        // shadcn's CLI needs an initialized npm project; without package.json there is nothing to
        // add to (vite always creates one in production — this guards test/edge workspaces).
        if (!Files.exists(frontendDir.resolve("package.json"))) {
            log.info("[FrontendGeneratorNode] No package.json in {} — skipping shadcn install", frontendDir);
            return;
        }

        Path uiDir = frontendDir.resolve("src/components/ui");
        Set<String> missing = requested.stream()
                .filter(c -> !Files.exists(uiDir.resolve(c + ".tsx")))
                .collect(Collectors.toCollection(TreeSet::new));
        if (missing.isEmpty()) {
            log.info("[FrontendGeneratorNode] All requested shadcn components already present");
            return;
        }

        log.info("[FrontendGeneratorNode] shadcn components to install: {}", missing);

        Path componentsJson = frontendDir.resolve("components.json");
        boolean needsComponentsJson = !Files.exists(componentsJson)
                || Files.readString(componentsJson).contains("tailwind.config.js");
        if (needsComponentsJson) {
            Files.writeString(componentsJson, """
                    {
                      "$schema": "https://ui.shadcn.com/schema.json",
                      "style": "default",
                      "rsc": false,
                      "tsx": true,
                      "tailwind": {
                        "config": "",
                        "css": "src/index.css",
                        "baseColor": "slate",
                        "cssVariables": true,
                        "prefix": ""
                      },
                      "aliases": {
                        "components": "@/components",
                        "utils": "@/lib/utils",
                        "ui": "@/components/ui",
                        "lib": "@/lib",
                        "hooks": "@/hooks"
                      },
                      "iconLibrary": "lucide"
                    }
                    """);
            log.info("[FrontendGeneratorNode] Wrote components.json for shadcn (Tailwind v4 compatible)");
        }

        ensureRootTsconfigPaths(frontendDir);

        BuildToolService.BuildResult result = buildToolService.runShadcnAdd(frontendDir, missing);
        if (result.success()) {
            log.info("[FrontendGeneratorNode] shadcn add succeeded for: {}\nOutput:\n{}", missing, result.output());
        } else {
            log.warn("[FrontendGeneratorNode] shadcn add failed (exit={}) for: {}\nOutput:\n{}",
                    result.exitCode(), missing, result.output());
        }

        ensureLibUtils(frontendDir);
    }

    private void ensureRootTsconfigPaths(Path frontendDir) throws IOException {
        Path rootTsconfig = frontendDir.resolve("tsconfig.json");
        if (!Files.exists(rootTsconfig)) return;
        String content = Files.readString(rootTsconfig);
        if (content.contains("\"paths\"")) {
            log.info("[FrontendGeneratorNode] tsconfig.json already has @/* paths");
            return;
        }
        Files.writeString(rootTsconfig, CANONICAL_TSCONFIG_ROOT);
        log.info("[FrontendGeneratorNode] Re-wrote tsconfig.json with canonical template (paths were missing)");
    }

    private static final String LIB_UTILS_CONTENT = """
            import { clsx, type ClassValue } from "clsx"
            import { twMerge } from "tailwind-merge"

            export function cn(...inputs: ClassValue[]) {
              return twMerge(clsx(inputs))
            }
            """;

    private void ensureLibUtils(Path frontendDir) throws IOException {
        Path libDir = frontendDir.resolve("src/lib");
        Path utilsFile = libDir.resolve("utils.ts");
        if (Files.exists(utilsFile)) {
            log.info("[FrontendGeneratorNode] src/lib/utils.ts already present");
            return;
        }
        for (String pkg : List.of("clsx", "tailwind-merge", "class-variance-authority")) {
            String pkgJson = Files.readString(frontendDir.resolve("package.json"));
            if (!pkgJson.contains("\"" + pkg + "\"")) {
                BuildToolService.BuildResult r = buildToolService.runNpmInstallPackage(frontendDir, pkg);
                if (r.success()) log.info("[FrontendGeneratorNode] Installed {}", pkg);
                else log.warn("[FrontendGeneratorNode] Failed to install {} (exit={}): {}", pkg, r.exitCode(), r.output());
            }
        }
        Files.createDirectories(libDir);
        Files.writeString(utilsFile, LIB_UTILS_CONTENT);
        log.info("[FrontendGeneratorNode] Created src/lib/utils.ts");
    }

    private void ensureEslintSetup(Path frontendDir) throws IOException {
        Path pkgPath = frontendDir.resolve("package.json");
        if (Files.exists(pkgPath) && !Files.readString(pkgPath).contains("eslint-plugin-unused-imports")) {
            log.info("[FrontendGeneratorNode] Installing eslint plugins (unused-imports, import-x, ts parser + resolver)");
            BuildToolService.BuildResult r = buildToolService.runNpmInstallDevPackages(
                    frontendDir, "eslint-plugin-unused-imports", "@typescript-eslint/parser",
                    "@typescript-eslint/eslint-plugin", "eslint-plugin-import-x", "eslint-import-resolver-typescript");
            if (r.success()) {
                log.info("[FrontendGeneratorNode] Installed eslint plugins + import-x cycle detection");
            } else {
                log.warn("[FrontendGeneratorNode] eslint plugin install failed (exit={}):\n{}",
                        r.exitCode(), r.output());
            }
        }

        Path fixConfig = frontendDir.resolve("eslint.fix.config.mjs");
        Files.writeString(fixConfig, ESLINT_FIX_CONFIG);
        log.info("[FrontendGeneratorNode] Wrote eslint.fix.config.mjs");
    }

    private void runEslintPass(Path frontendDir) {
        BuildToolService.BuildResult result = buildToolService.runEslintFix(frontendDir);
        if (result.exitCode() == 2) {
            log.warn("[FrontendGeneratorNode] eslint --fix crashed (exit=2) — skipping auto-fix pass:\n{}",
                    result.output());
        } else if (result.output().isBlank()) {
            log.info("[FrontendGeneratorNode] eslint --fix: no auto-fixable issues");
        } else {
            log.info("[FrontendGeneratorNode] eslint --fix (exit={}) — applied fixes:\n{}",
                    result.exitCode(), result.output());
        }
    }

    /**
     * Fails the frontend stage when an ES-module import cycle is present. Cycles build clean under
     * tsc+vite and crash only in the client's browser (TDZ "Cannot access X before initialization"),
     * so this is the sole gate that catches them — deliberately loud rather than silent-to-client.
     *
     * <p>Only an actual PARSED cycle throws. If eslint itself crashed (missing plugin, bad config →
     * unparseable output / exit 2), the check degrades to a warning and the build proceeds: a
     * tooling problem must not fail every build, and a false hard-fail is worse than an occasional
     * missed cycle that the layer-direction prompt rule is separately guarding against.
     */
    private void enforceNoCycles(Path frontendDir) throws WorkerException {
        BuildToolService.BuildResult result = buildToolService.runEslintCycleCheck(frontendDir);
        List<EslintCycleParser.Cycle> cycles = EslintCycleParser.parse(result.output());

        if (cycles.isEmpty()) {
            if (result.exitCode() == 2 || (result.exitCode() != 0 && result.output().isBlank())) {
                log.warn("[FrontendGeneratorNode] Cycle check inconclusive (eslint exit={}, no parseable "
                        + "report) — skipping. Output:\n{}", result.exitCode(), result.output());
            } else {
                log.info("[FrontendGeneratorNode] No import cycles detected");
            }
            return;
        }

        StringBuilder detail = new StringBuilder();
        for (EslintCycleParser.Cycle c : cycles) {
            String rel = frontendDir.relativize(Path.of(c.filePath())).toString();
            detail.append("\n  - ").append(rel).append(':').append(c.line()).append(" — ").append(c.message());
        }
        log.error("[FrontendGeneratorNode] {} import cycle(s) detected — failing stage:{}", cycles.size(), detail);
        throw new WorkerException(FailureType.CODE,
                "Frontend has " + cycles.size() + " ES-module import cycle(s) that build clean but crash "
                        + "at runtime:" + detail);
    }

    private Set<String> detectShadcnComponents(Path workspace) {
        Path srcDir = workspace.resolve("frontend/src");
        if (!Files.exists(srcDir)) return Set.of();

        Set<String> validRegistry = getValidShadcnComponents(workspace.resolve("frontend"));
        Set<String> components = new TreeSet<>();
        Set<String> skipped = new TreeSet<>();

        try (var stream = Files.walk(srcDir)) {
            stream.filter(p -> p.toString().endsWith(".ts") || p.toString().endsWith(".tsx"))
                    .forEach(p -> {
                        try {
                            Matcher m = SHADCN_IMPORT.matcher(Files.readString(p));
                            while (m.find()) {
                                String name = m.group(1).toLowerCase();
                                // Replace deprecated component names with their successors
                                if (SHADCN_DEPRECATED.containsKey(name)) {
                                    components.add(SHADCN_DEPRECATED.get(name));
                                    continue;
                                }
                                // Validate against live registry — skip hallucinated names
                                if (validRegistry.isEmpty() || validRegistry.contains(name)) {
                                    components.add(name); // empty = fetch failed, allow all
                                } else {
                                    skipped.add(name);
                                }
                            }
                        } catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            log.warn("[FrontendGeneratorNode] Could not scan for shadcn components: {}", e.getMessage());
        }
        if (!skipped.isEmpty()) {
            log.warn("[FrontendGeneratorNode] Skipped non-registry shadcn imports (not in @shadcn registry): {}", skipped);
        }
        return components;
    }

    /**
     * Runs `npx shadcn@latest search @shadcn` and parses the (ui) component lines.
     * Result is cached for the container lifetime — the registry doesn't change mid-run.
     * Retries up to 3 times on failure. Falls back to empty set (= allow all) if all
     * attempts fail (e.g. network unavailable inside the container).
     */
    private Set<String> getValidShadcnComponents(Path frontendDir) {
        if (cachedValidShadcnComponents != null) return cachedValidShadcnComponents;
        synchronized (FrontendGeneratorNode.class) {
            if (cachedValidShadcnComponents != null) return cachedValidShadcnComponents;

            int maxAttempts = 3;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    ProcessBuilder pb = new ProcessBuilder("npx", "shadcn@latest", "search", "@shadcn")
                            .directory(frontendDir.toFile())
                            .redirectErrorStream(true);
                    Process proc = pb.start();
                    String output = new String(proc.getInputStream().readAllBytes());
                    proc.waitFor();

                    Set<String> valid = new java.util.LinkedHashSet<>();
                    for (String line : output.split("\n")) {
                        Matcher m = SHADCN_REGISTRY_LINE.matcher(line.trim());
                        if (m.find()) valid.add(m.group(1));
                    }

                    if (valid.isEmpty()) {
                        log.warn("[FrontendGeneratorNode] shadcn registry fetch attempt {}/{} returned no (ui) components — retrying",
                                attempt, maxAttempts);
                        continue;
                    }

                    log.info("[FrontendGeneratorNode] Fetched {} valid shadcn components from live registry (attempt {})",
                            valid.size(), attempt);
                    cachedValidShadcnComponents = valid;
                    return cachedValidShadcnComponents;

                } catch (Exception e) {
                    log.warn("[FrontendGeneratorNode] shadcn registry fetch attempt {}/{} failed: {}",
                            attempt, maxAttempts, e.getMessage());
                }
            }

            log.warn("[FrontendGeneratorNode] All {} shadcn registry fetch attempts failed — allowing all components", maxAttempts);
            cachedValidShadcnComponents = Set.of(); // empty = allow all
            return cachedValidShadcnComponents;
        }
    }

    // ── shouldSkip ────────────────────────────────────────────────────────────

    private boolean shouldSkip(FileSpec spec, FeatureSpec feature, boolean requestedChangesMode,
                               boolean existsOnDisk) {
        if (spec == null) return false;
        if (requestedChangesMode) {
            if (feature == null || !feature.isChangeRequired()) return true;
            // File-grain narrowing: within a changed feature, regenerate only the files the
            // targeting pass marked. A null flag (old spec / file omitted by targeting) falls
            // back to the feature decision — the pre-file-grain behavior.
            return spec.getChangeRequired() != null && !spec.getChangeRequired();
        }
        String status = spec.getStatus();
        // GENERATION_FAILED is NOT skipped — falls through to runGenerateStage for a fresh attempt.
        // GENERATED + present on disk IS skipped: after generation the fix loop owns the
        // file. Regenerating on retry destroyed the previous attempt's ErrorFixAgent
        // patches (Sisyphus loop, multifit-aundh 2026-07-04) — retries never converged.
        return "SPEC_COMPLIANT".equalsIgnoreCase(status)
                || "VALIDATED".equalsIgnoreCase(status)
                || ("GENERATED".equalsIgnoreCase(status) && existsOnDisk);
    }

    // ── Spec loading ──────────────────────────────────────────────────────────

    private ArchitectureSpec loadSpec(Path workspace) {
        if (!ArchitectureJsonUtil.exists(workspace)) return new ArchitectureSpec();
        try {
            return ArchitectureJsonUtil.read(workspace);
        } catch (IOException e) {
            log.warn("[FrontendGeneratorNode] Could not load spec: {}", e.getMessage());
            return new ArchitectureSpec();
        }
    }

    private Map<String, FileSpec> buildSpecByPath(ArchitectureSpec spec) {
        if (spec.getFiles() == null) return Map.of();
        return spec.getFiles().stream()
                .filter(f -> f.getFilePath() != null)
                .collect(Collectors.toMap(FileSpec::getFilePath, f -> f, (a, b) -> a));
    }

    private Map<String, FeatureSpec> buildFeaturesByName(ArchitectureSpec spec) {
        if (spec.getFeatures() == null) return Map.of();
        return spec.getFeatures().stream()
                .filter(f -> f.getFeatureName() != null)
                .collect(Collectors.toMap(FeatureSpec::getFeatureName, f -> f, (a, b) -> a));
    }

    // Matches use* hook names in feature instructions — used to auto-resolve their source files.
    private static final java.util.regex.Pattern USE_HOOK_NAME =
            java.util.regex.Pattern.compile("\\b(use[A-Z]\\w+)\\b");

    private Map<String, String> loadDependencyFiles(Path workspace, FileSpec spec,
                                                     TypeScriptExportRegistry exportRegistry) {
        java.util.LinkedHashMap<String, String> deps = new java.util.LinkedHashMap<>();
        if (spec == null) return deps;

        // 1. Spec-declared dependencies (importsFrom / dependsOn)
        List<String> depPaths = spec.getImportsFrom();
        if (depPaths == null || depPaths.isEmpty()) depPaths = spec.getDependsOn();
        if (depPaths != null) {
            for (String depPath : depPaths) {
                Path file = workspace.resolve(depPath);
                if (Files.exists(file)) {
                    try { deps.put(depPath, Files.readString(file)); } catch (IOException ignored) {}
                }
            }
        }

        // 2. Auto-resolve use* hook names mentioned in the file's spec description.
        // The planner frequently leaves imports_from empty even when a component clearly
        // depends on a hook — this fills the gap so Flash gets the actual hook source
        // (return type, field names) rather than only the compact contract card summary.
        if (exportRegistry != null && spec.getFileRole() != null) {
            String combined = (spec.getFileRole() != null ? spec.getFileRole() : "")
                    + " " + (spec.getDescription() != null ? spec.getDescription() : "");
            java.util.regex.Matcher m = USE_HOOK_NAME.matcher(combined);
            java.util.Set<String> resolved = new java.util.HashSet<>();
            while (m.find()) {
                String hookName = m.group(1);
                exportRegistry.resolveSpecifier(hookName).ifPresent(relNoExt -> {
                    if (resolved.add(relNoExt) && !deps.containsKey(relNoExt)) {
                        for (String ext : List.of(".ts", ".tsx")) {
                            Path file = workspace.resolve(relNoExt + ext);
                            if (Files.exists(file)) {
                                try { deps.put(relNoExt + ext, Files.readString(file)); }
                                catch (IOException ignored) {}
                                break;
                            }
                        }
                    }
                });
            }
        }

        return deps;
    }

    // Matches interface/type/enum/class declarations that are NOT already exported.
    private static final Pattern UNEXPORTED_DECL =
            Pattern.compile("^(interface|type|enum|class)\\s", Pattern.MULTILINE);

    private void ensureAllTypesExported(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        String fixed = UNEXPORTED_DECL.matcher(content).replaceAll("export $1 ");
        if (!fixed.equals(content)) {
            Files.writeString(filePath, fixed);
            log.info("[FrontendGeneratorNode] Ensured exports on declarations in {}", filePath.getFileName());
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String readIfExists(Path filePath) {
        if (!Files.exists(filePath)) return null;
        try {
            return Files.readString(filePath);
        } catch (IOException e) {
            log.warn("[FrontendGeneratorNode] Could not read {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    private String layerName(FileEntry entry) {
        return LayerOrderUtil.frontendLayerName(entry);
    }


    // ── DB record ─────────────────────────────────────────────────────────────

    private void upsertRecord(WorkerContext ctx, String filePath,
                              GeneratedFile.FileType fileType, GeneratedFile.FileStatus status) {
        fileRepo.findByTaskIdAndFilePath(ctx.getTaskId(), filePath).ifPresentOrElse(
                existing -> {
                    existing.setStatus(status);
                    existing.setAttemptNumber(ctx.getAttemptNumber());
                    if (status != GeneratedFile.FileStatus.GENERATION_FAILED) {
                        existing.setErrorMessage(null);
                    }
                    fileRepo.save(existing);
                },
                () -> fileRepo.save(GeneratedFile.builder()
                        .taskId(ctx.getTaskId())
                        .filePath(filePath)
                        .fileType(fileType)
                        .status(status)
                        .attemptNumber(ctx.getAttemptNumber())
                        .build())
        );
    }
}
