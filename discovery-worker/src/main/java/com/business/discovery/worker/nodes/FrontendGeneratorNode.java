package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.model.GeneratedFile;
import com.business.discovery.worker.repository.GeneratedFileRepository;
import com.business.discovery.worker.service.BuildToolService;
import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.ComplianceResult;
import com.business.discovery.worker.service.llm.FeatureSpec;
import com.business.discovery.worker.service.llm.FileEntry;
import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.business.discovery.worker.util.ArchitectureJsonUtil;
import com.business.discovery.worker.util.LayerOrderUtil;
import com.business.discovery.worker.util.TypeScriptExportRegistry;
import com.business.discovery.worker.util.TypeScriptImportChecker;
import com.business.discovery.worker.util.TypeScriptImportFixer;
import com.business.discovery.worker.service.GitService;
import com.business.discovery.worker.util.WorkspaceReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Order(8)
@Slf4j
public class FrontendGeneratorNode implements WorkerNode {

    private static final int MAX_COMPILE_FIX_ATTEMPTS = 3;
    private static final int MAX_SPEC_FIX_ROUNDS = 2;

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
                "paths": { "@/*": ["./src/*"] }
              },
              "include": ["src"]
            }
            """;

    // Canonical vite.config.ts — written at scaffold time and again at each ensureFrontendWorkspace call.
    // Always overwrites LLM-generated or patched versions.
    // Includes: @/ alias, /api proxy to localhost:8080, standard dist output.
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
    // Written at scaffold time and again at each ensureFrontendWorkspace call.
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

    // Minimal ESLint flat config used only for our --fix pass.
    // Ignores shadcn/ui components (generated by CLI, not by us).
    // Removes entire unused import lines (plugin handles what tsc TS6133 cannot auto-fix).
    private static final String ESLINT_FIX_CONFIG = """
            import unusedImports from 'eslint-plugin-unused-imports';
            import tsParser from '@typescript-eslint/parser';

            export default [
              { ignores: ['dist', 'src/components/ui'] },
              {
                files: ['**/*.{ts,tsx}'],
                languageOptions: { parser: tsParser },
                plugins: { 'unused-imports': unusedImports },
                rules: {
                  'no-unused-vars': 'off',
                  'unused-imports/no-unused-imports': 'error',
                  'unused-imports/no-unused-vars': [
                    'warn',
                    { vars: 'all', varsIgnorePattern: '^_', args: 'after-used', argsIgnorePattern: '^_' }
                  ],
                },
              },
            ];
            """;

    // Layers that are purely mechanical — skip Pro spec compliance.
    // Threshold: frontendPriority ≤ 30 = TYPE, CONSTANT, UTIL
    private static final int MECHANICAL_LAYER_THRESHOLD = 30;

    private static final int CHECKPOINT_EVERY_N_FILES = 3;

    private final LlmGeneratorService flashLlm;
    private final LlmGeneratorService fixLlm;
    private final BuildToolService buildToolService;
    private final GeneratedFileRepository fileRepo;
    private final GitService gitService;

    public FrontendGeneratorNode(@Qualifier("geminiFlash") LlmGeneratorService flashLlm,
                                 @Qualifier("geminiPro") LlmGeneratorService ignoredProLlm,
                                 @Qualifier("geminiFlash") LlmGeneratorService fixLlm,
                                 BuildToolService buildToolService,
                                 GeneratedFileRepository fileRepo,
                                 GitService gitService) {
        this.flashLlm = flashLlm;
        this.fixLlm = fixLlm;
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
                .collect(Collectors.toSet());

        // Export registry built incrementally as files are generated — used by TypeScriptImportFixer
        TypeScriptExportRegistry exportRegistry = new TypeScriptExportRegistry(workspace);

        boolean requestedChangesMode = ctx.getBriefCtx().requestedChanges() != null
                && !ctx.getBriefCtx().requestedChanges().isBlank();

        if (!Files.exists(frontendDir.resolve("node_modules"))) {
            log.info("[FrontendGeneratorNode] node_modules missing — running npm install");
            BuildToolService.BuildResult install = buildToolService.runNpmInstall(frontendDir);
            if (!install.success()) {
                throw new WorkerException(FailureType.CODE,
                        "npm install failed before tsc checks:\n" + install.output());
            }
            log.info("[FrontendGeneratorNode] npm install complete");
        }

        int filesProcessed = 0;

        try {
            ensureFrontendWorkspace(frontendDir, workspace);

            for (FileEntry entry : frontendFiles) {
                FileSpec spec = specByPath.get(entry.path());
                FeatureSpec feature = spec != null && spec.getFeatureName() != null
                        ? featuresByName.get(spec.getFeatureName()) : null;
                Path filePath = workspace.resolve(entry.path());

                if (shouldSkip(spec, feature, requestedChangesMode)) {
                    log.info("[FrontendGeneratorNode] Skipping [{}]: {}", layerName(entry), entry.path());
                    // Still register existing file's exports so TypeScriptImportFixer can resolve
                    // cross-file imports in files generated later in this same run.
                    if (Files.exists(filePath)) {
                        exportRegistry.register(filePath, Files.readString(filePath));
                    }
                    continue;
                }

                String featureInstruction = feature != null ? feature.getFeatureInstruction() : null;
                String fileRole = spec != null ? spec.getFileRole() : null;
                if (featureInstruction == null || featureInstruction.isBlank()) {
                    log.warn("[FrontendGeneratorNode] No featureInstruction for '{}' (file: {}) — skipping",
                            spec != null ? spec.getFeatureName() : "unknown", entry.path());
                    continue;
                }

                String status = spec != null ? spec.getStatus() : "PLANNED";

                // Stage 3: PLANNED → GENERATED
                // GENERATION_FAILED is also regenerated from scratch — never skip it permanently.
                boolean needsGeneration = "PLANNED".equalsIgnoreCase(status)
                        || "GENERATION_FAILED".equalsIgnoreCase(status)
                        || (requestedChangesMode && feature != null && feature.isChangeRequired());
                if (needsGeneration) {
                    log.info("[FrontendGeneratorNode] Stage 3 — generating [{}]: {}", layerName(entry), entry.path());
                    status = runGenerateStage(ctx, workspace, entry, filePath, spec,
                            featureInstruction, fileRole, requestedChangesMode, feature,
                            manifestPaths, exportRegistry);
                }

                // Stage 1: GENERATED → VALIDATED
                if ("GENERATED".equalsIgnoreCase(status)) {
                    log.info("[FrontendGeneratorNode] Stage 1 — tsc check [{}]: {}", layerName(entry), entry.path());
                    status = runTscStage(ctx, workspace, frontendDir, entry, filePath, spec, specByPath);
                }

                // Stage 2: VALIDATED → SPEC_COMPLIANT
                if ("VALIDATED".equalsIgnoreCase(status)) {
                    log.info("[FrontendGeneratorNode] Stage 2 — spec check [{}]: {}", layerName(entry), entry.path());
                    runSpecCheckStage(ctx, workspace, frontendDir, entry, filePath, spec,
                            featureInstruction, fileRole, specByPath);
                }

                // Push a WIP checkpoint every 3 files so work survives a container kill.
                if (++filesProcessed % CHECKPOINT_EVERY_N_FILES == 0) {
                    gitService.commitAndPushCheckpoint(workspace,
                            "chore: frontend wip — " + filesProcessed + " files (attempt " + ctx.getAttemptNumber() + ")",
                            ctx.getGithubBranch());
                }
            }
        } catch (WorkerException e) {
            throw e;
        } catch (IOException e) {
            throw new WorkerException(FailureType.INFRA,
                    "Failed during frontend generation: " + e.getMessage(), e);
        }

        log.info("[FrontendGeneratorNode] Done — {} frontend files processed", frontendFiles.size());
    }

    // ── Stage 3: PLANNED → GENERATED ─────────────────────────────────────────

    private String runGenerateStage(WorkerContext ctx, Path workspace, FileEntry entry,
                                    Path filePath, FileSpec spec, String featureInstruction,
                                    String fileRole, boolean requestedChangesMode,
                                    FeatureSpec feature, Set<String> manifestPaths,
                                    TypeScriptExportRegistry exportRegistry) throws IOException {
        Map<String, String> depFiles = loadDependencyFiles(workspace, spec);
        String existingContent = (requestedChangesMode && feature != null && feature.isChangeRequired())
                ? readIfExists(filePath) : null;

        String content = flashLlm.generateFileContent(entry.path(), featureInstruction,
                fileRole != null ? fileRole : "", depFiles, existingContent);
        Files.createDirectories(filePath.getParent());

        // Truncation guard: unbalanced braces means Flash hit its output token limit.
        // Skip the entire tsc fix loop — patching an incomplete file wastes LLM tokens.
        if (isBraceTruncated(content)) {
            log.warn("[FrontendGeneratorNode] Truncated output detected for {} — marking GENERATION_FAILED (saves fix-LLM calls)", entry.path());
            Files.writeString(filePath, content);
            ArchitectureJsonUtil.updateFileStatus(workspace, entry.path(), "GENERATION_FAILED");
            upsertRecord(ctx, entry.path(), GeneratedFile.FileType.FRONTEND, GeneratedFile.FileStatus.GENERATION_FAILED);
            return "GENERATION_FAILED";
        }

        Files.writeString(filePath, content);
        // Fix broken @/ and relative import paths using the export registry (zero LLM)
        TypeScriptImportFixer.fix(filePath, workspace, exportRegistry);
        // Types files must export every declaration — LLM often forgets 'export' on some of them.
        // Fixing here prevents TS2459 cascade in every service that imports from these files.
        if (entry.path().contains("/types/")) {
            ensureAllTypesExported(filePath);
        }
        // Register this file's exports so subsequent files can resolve imports from it
        exportRegistry.register(filePath, Files.readString(filePath));
        TypeScriptImportChecker.check(filePath, workspace, manifestPaths);

        ArchitectureJsonUtil.updateFileStatus(workspace, entry.path(), "GENERATED");
        upsertRecord(ctx, entry.path(), GeneratedFile.FileType.FRONTEND, GeneratedFile.FileStatus.GENERATED);
        return "GENERATED";
    }

    // ── Stage 1: GENERATED → VALIDATED ───────────────────────────────────────

    private String runTscStage(WorkerContext ctx, Path workspace, Path frontendDir,
                               FileEntry entry, Path filePath,
                               FileSpec spec, Map<String, FileSpec> specByPath) throws IOException {
        WorkspaceReader reader = new WorkspaceReader(workspace);

        // Re-scan shadcn components now that this file has been generated — catches
        // components referenced by newly-written files that weren't present at workspace setup.
        try {
            ensureShadcnComponents(frontendDir, workspace);
        } catch (IOException e) {
            log.warn("[FrontendGeneratorNode] shadcn component re-scan failed: {}", e.getMessage());
        }

        String lastTscOutput = "";
        for (int attempt = 0; attempt <= MAX_COMPILE_FIX_ATTEMPTS; attempt++) {
            BuildToolService.BuildResult result = buildToolService.runTscCheck(frontendDir);
            lastTscOutput = result.output();
            if (result.success()) {
                ArchitectureJsonUtil.updateFileStatus(workspace, entry.path(), "VALIDATED");
                upsertRecord(ctx, entry.path(), GeneratedFile.FileType.FRONTEND, GeneratedFile.FileStatus.VALIDATED);
                log.info("[FrontendGeneratorNode] tsc OK: {}", entry.path());
                return "VALIDATED";
            }

            // On the first failure, auto-install any npm packages missing from node_modules.
            // Handles LLM-generated imports like 'date-fns' that aren't in the scaffold's
            // package.json — saves 3 wasted fix-LLM attempts on an unfixable TS2307 error.
            if (attempt == 0) {
                boolean installed = tryInstallMissingPackages(frontendDir, result.output());
                if (installed) {
                    BuildToolService.BuildResult afterInstall = buildToolService.runTscCheck(frontendDir);
                    if (afterInstall.success()) {
                        ArchitectureJsonUtil.updateFileStatus(workspace, entry.path(), "VALIDATED");
                        upsertRecord(ctx, entry.path(), GeneratedFile.FileType.FRONTEND, GeneratedFile.FileStatus.VALIDATED);
                        log.info("[FrontendGeneratorNode] tsc OK after package install: {}", entry.path());
                        return "VALIDATED";
                    }
                    result = afterInstall;
                    lastTscOutput = result.output();
                }
            }

            if (attempt < MAX_COMPILE_FIX_ATTEMPTS) {
                // TS2459: "Module declares X locally, but it is not exported" — fix the types
                // file (zero LLM). The tsc error points at the importing service, but the root
                // cause is a missing 'export' in the types file it imports from.
                if (result.output().contains("TS2459")) {
                    boolean anyFixed = fixTs2459TypesFiles(result.output(), workspace);
                    if (anyFixed) continue;
                }

                List<String> toFix = parseFailingFiles(result.output(), workspace);
                if (toFix.isEmpty()) toFix = List.of(entry.path());

                log.warn("[FrontendGeneratorNode] tsc failed (attempt {}/{}) — fixing {} file(s): {}\ntsc output:\n{}",
                        attempt + 1, MAX_COMPILE_FIX_ATTEMPTS, toFix.size(), toFix, result.output());

                for (String failingPath : toFix) {
                    if (!reader.exists(failingPath)) continue;
                    FileSpec failingSpec = specByPath.get(failingPath);
                    String triggerFilePath = failingPath.equals(entry.path()) ? null : entry.path();
                    Map<String, String> fixCtx = buildFixContext(reader, failingSpec, failingPath, triggerFilePath, workspace);
                    String content = reader.readFile(failingPath);
                    String fixed = fixLlm.fixFileContent(failingPath, content, result.output(), fixCtx);
                    if (looksLikeCode(fixed, failingPath)) {
                        log.info("[FrontendGeneratorNode] fix-LLM patched {} ({} bytes)", failingPath, fixed.length());
                        Files.writeString(workspace.resolve(failingPath), fixed);
                    } else {
                        log.warn("[FrontendGeneratorNode] fix-LLM returned prose for {} — original file preserved. Prose preview: {}",
                                failingPath, fixed.substring(0, Math.min(300, fixed.length())).replace("\n", " "));
                    }
                }
                // After fix-LLM writes, run eslint to auto-remove unused imports and
                // other mechanical issues before the next tsc attempt.
                log.info("[FrontendGeneratorNode] Running eslint --fix after fix-LLM pass (attempt {})", attempt + 1);
                runEslintPass(frontendDir);
            }
        }

        log.error("[FrontendGeneratorNode] tsc still failing for {} after {} attempts — GENERATION_FAILED\nFinal tsc output:\n{}",
                entry.path(), MAX_COMPILE_FIX_ATTEMPTS, lastTscOutput);
        ArchitectureJsonUtil.updateFileStatus(workspace, entry.path(), "GENERATION_FAILED");
        upsertRecord(ctx, entry.path(), GeneratedFile.FileType.FRONTEND, GeneratedFile.FileStatus.GENERATION_FAILED);
        return "GENERATION_FAILED";
    }

    // ── Stage 2: VALIDATED → SPEC_COMPLIANT ──────────────────────────────────

    private void runSpecCheckStage(WorkerContext ctx, Path workspace, Path frontendDir,
                                   FileEntry entry, Path filePath, FileSpec spec,
                                   String featureInstruction, String fileRole,
                                   Map<String, FileSpec> specByPath) throws IOException {
        // Mechanical layers (TYPE, CONSTANT, UTIL) have no business logic —
        // if they compile they are correct. Skip the LLM compliance check entirely.
        if (LayerOrderUtil.frontendPriority(entry) <= MECHANICAL_LAYER_THRESHOLD) {
            ArchitectureJsonUtil.updateFileStatus(workspace, entry.path(), "SPEC_COMPLIANT");
            upsertRecord(ctx, entry.path(), GeneratedFile.FileType.FRONTEND, GeneratedFile.FileStatus.SPEC_COMPLIANT);
            log.info("[FrontendGeneratorNode] Mechanical layer — auto-compliant: {}", entry.path());
            return;
        }

        // Combine fileRole + featureInstruction so the compliance check has full per-file context
        String combinedSpec = "FILE ROLE: " + (fileRole != null ? fileRole : "")
                + "\n\nFEATURE INSTRUCTION:\n" + featureInstruction;

        for (int round = 0; round < MAX_SPEC_FIX_ROUNDS; round++) {
            String fileContent = Files.readString(filePath);
            ComplianceResult compliance = flashLlm.checkSpecCompliance(entry.path(), fileContent, combinedSpec);

            if (compliance.compliant()) {
                ArchitectureJsonUtil.updateFileStatus(workspace, entry.path(), "SPEC_COMPLIANT");
                upsertRecord(ctx, entry.path(), GeneratedFile.FileType.FRONTEND, GeneratedFile.FileStatus.SPEC_COMPLIANT);
                log.info("[FrontendGeneratorNode] Spec compliant: {}", entry.path());
                return;
            }

            log.warn("[FrontendGeneratorNode] Spec drift for {} (round {}/{}) — issues: {}",
                    entry.path(), round + 1, MAX_SPEC_FIX_ROUNDS, compliance.issues());

            if (round == MAX_SPEC_FIX_ROUNDS - 1) {
                log.warn("[FrontendGeneratorNode] Max spec-fix rounds exhausted for {} — leaving as VALIDATED",
                        entry.path());
                return;
            }

            // Keep featureInstruction intact; append corrections to fileRole only
            String correctedRole = (fileRole != null ? fileRole : "")
                    + "\n\nFix these spec deviations:\n- "
                    + String.join("\n- ", compliance.issues());
            Map<String, String> depFiles = loadDependencyFiles(workspace, spec);
            String corrected = flashLlm.generateFileContent(entry.path(), featureInstruction, correctedRole, depFiles, null);
            Files.writeString(filePath, corrected);
            ArchitectureJsonUtil.updateFileStatus(workspace, entry.path(), "GENERATED");

            // Re-run Stage 1 on the corrected file
            String newStatus = runTscStage(ctx, workspace, frontendDir, entry, filePath, spec, specByPath);
            if (!"VALIDATED".equalsIgnoreCase(newStatus)) return; // GENERATION_FAILED — stop
        }
    }

    // ── Frontend workspace setup (runs once per attempt before generation) ────

    // Matches both canonical @/ alias and relative ../ui/ imports generated by LLMs
    private static final Pattern SHADCN_IMPORT =
            Pattern.compile("from\\s+['\"](?:@/components/ui|[./]+(?:components/)?ui)/([a-z][a-z0-9-]*)");

    private void ensureFrontendWorkspace(Path frontendDir, Path workspace) throws IOException {
        // Always overwrite config files with canonical templates — never patch
        writeCanonicalConfigs(frontendDir);
        ensureTailwind(frontendDir);
        ensureHookformResolvers(frontendDir);
        ensureRadixUiPackages(frontendDir);
        ensureShadcnComponents(frontendDir, workspace);
        ensureEslintSetup(frontendDir);
        runEslintPass(frontendDir);
    }

    private void writeCanonicalConfigs(Path frontendDir) throws IOException {
        // vite.config.ts — fixed template with @/ alias + /api proxy
        Path viteConfig = frontendDir.resolve("vite.config.ts");
        if (Files.exists(viteConfig)) {
            Files.writeString(viteConfig, CANONICAL_VITE_CONFIG);
            log.info("[FrontendGeneratorNode] Wrote canonical vite.config.ts");
        }
        // tsconfig.app.json — fixed template without verbatimModuleSyntax, with paths
        Path tsconfigApp = frontendDir.resolve("tsconfig.app.json");
        if (Files.exists(tsconfigApp)) {
            Files.writeString(tsconfigApp, CANONICAL_TSCONFIG_APP);
            log.info("[FrontendGeneratorNode] Wrote canonical tsconfig.app.json");
        }
        // tsconfig.json (root) — includes compilerOptions.paths for shadcn CLI alias resolution
        Path tsconfigRoot = frontendDir.resolve("tsconfig.json");
        if (Files.exists(tsconfigRoot)) {
            Files.writeString(tsconfigRoot, CANONICAL_TSCONFIG_ROOT);
            log.info("[FrontendGeneratorNode] Wrote canonical tsconfig.json");
        }
        // vite-env.d.ts — declares CSS/SVG/PNG module types so tsc accepts Vite asset imports.
        // Without this, `import './index.css'` causes TS2882 on every file.
        Path srcDir = frontendDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("vite-env.d.ts"), "/// <reference types=\"vite/client\" />\n");
        log.info("[FrontendGeneratorNode] Wrote src/vite-env.d.ts");

        // App.tsx — clean shell with no Vite demo imports (react.svg / hero.png / App.css).
        // If the spec's manifest includes App.tsx, the LLM will overwrite this later.
        // Overwrite unconditionally — the Vite scaffold default always breaks tsc.
        Path appTsx = srcDir.resolve("App.tsx");
        Files.writeString(appTsx, CANONICAL_APP_TSX);
        log.info("[FrontendGeneratorNode] Wrote canonical App.tsx");

        // main.tsx — clean mount point, no demo imports.
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

    private void ensureShadcnComponents(Path frontendDir, Path workspace) throws IOException {
        Set<String> needed = detectShadcnComponents(workspace);
        if (needed.isEmpty()) return;

        Path uiDir = frontendDir.resolve("src/components/ui");
        Set<String> missing = needed.stream()
                .filter(c -> !Files.exists(uiDir.resolve(c + ".tsx")))
                .collect(Collectors.toCollection(TreeSet::new));
        if (missing.isEmpty()) {
            log.info("[FrontendGeneratorNode] All shadcn components already present");
            return;
        }

        log.info("[FrontendGeneratorNode] shadcn components needed: {}", missing);

        // Write components.json if not present, or fix it if it references tailwind.config.js
        // (wrong for Tailwind v4 — shadcn add silently skips all components when this is set).
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

        // shadcn CLI resolves @/ via tsconfig.json (root) compilerOptions.paths, not tsconfig.app.json.
        // Patch root tsconfig.json to add paths if not present.
        ensureRootTsconfigPaths(frontendDir);

        BuildToolService.BuildResult result = buildToolService.runShadcnAdd(frontendDir, missing);
        if (result.success()) {
            log.info("[FrontendGeneratorNode] shadcn add succeeded for: {}\nOutput:\n{}", missing, result.output());
        } else {
            log.warn("[FrontendGeneratorNode] shadcn add failed (exit={}) for: {}\nOutput:\n{}",
                    result.exitCode(), missing, result.output());
        }

        // shadcn components all import cn from @/lib/utils — create it and install its deps.
        ensureLibUtils(frontendDir);
    }

    private void ensureRootTsconfigPaths(Path frontendDir) throws IOException {
        Path rootTsconfig = frontendDir.resolve("tsconfig.json");
        if (!Files.exists(rootTsconfig)) return;
        // writeCanonicalConfigs() already wrote CANONICAL_TSCONFIG_ROOT which includes paths.
        // This method is now a safety net only — verifies paths are present, writes if missing.
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
        // Install deps first — shadcn components import from both packages.
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

    private static final Pattern MISSING_MODULE_PATTERN =
            Pattern.compile("Cannot find module '([^']+)'");

    private boolean tryInstallMissingPackages(Path frontendDir, String tscOutput) {
        Matcher m = MISSING_MODULE_PATTERN.matcher(tscOutput);
        Set<String> toInstall = new LinkedHashSet<>();
        while (m.find()) {
            String specifier = m.group(1);
            // Skip relative imports (our files) and @/ alias (our files via tsconfig paths)
            if (specifier.startsWith(".") || specifier.startsWith("@/")) continue;
            String[] parts = specifier.split("/");
            String pkg = specifier.startsWith("@") && parts.length >= 2
                    ? parts[0] + "/" + parts[1]
                    : parts[0];
            if (!pkg.isBlank() && !Files.exists(frontendDir.resolve("node_modules").resolve(pkg))) {
                toInstall.add(pkg);
            }
        }
        if (toInstall.isEmpty()) return false;
        log.info("[FrontendGeneratorNode] Auto-installing npm packages missing from node_modules: {}", toInstall);
        for (String pkg : toInstall) {
            BuildToolService.BuildResult r = buildToolService.runNpmInstallPackage(frontendDir, pkg);
            if (r.success()) log.info("[FrontendGeneratorNode] Installed {}", pkg);
            else log.warn("[FrontendGeneratorNode] Failed to install {} (exit={}): {}", pkg, r.exitCode(), r.output());
        }
        return true;
    }

    private void ensureEslintSetup(Path frontendDir) throws IOException {
        Path pkgPath = frontendDir.resolve("package.json");
        if (Files.exists(pkgPath) && !Files.readString(pkgPath).contains("eslint-plugin-unused-imports")) {
            log.info("[FrontendGeneratorNode] Installing eslint-plugin-unused-imports + @typescript-eslint/parser");
            // Install as separate args via runNpmInstallDevPackages — runNpmInstallPackage takes
            // a single string which would be treated as one package name with spaces in it.
            BuildToolService.BuildResult r = buildToolService.runNpmInstallDevPackages(
                    frontendDir, "eslint-plugin-unused-imports", "@typescript-eslint/parser", "@typescript-eslint/eslint-plugin");
            if (r.success()) {
                log.info("[FrontendGeneratorNode] Installed eslint-plugin-unused-imports + typescript-eslint");
            } else {
                log.warn("[FrontendGeneratorNode] eslint-plugin-unused-imports install failed (exit={}):\n{}",
                        r.exitCode(), r.output());
            }
        }

        // Always overwrite — ensures the worker's canonical config is used even on reruns.
        Path fixConfig = frontendDir.resolve("eslint.fix.config.mjs");
        Files.writeString(fixConfig, ESLINT_FIX_CONFIG);
        log.info("[FrontendGeneratorNode] Wrote eslint.fix.config.mjs");
    }

    private void runEslintPass(Path frontendDir) {
        BuildToolService.BuildResult result = buildToolService.runEslintFix(frontendDir);
        if (result.exitCode() == 2) {
            // Exit 2 = eslint itself crashed (bad config, missing plugin)
            log.warn("[FrontendGeneratorNode] eslint --fix crashed (exit=2) — skipping auto-fix pass:\n{}",
                    result.output());
        } else if (result.output().isBlank()) {
            log.info("[FrontendGeneratorNode] eslint --fix: no auto-fixable issues");
        } else {
            // Exit 1 = unfixable issues remain — that's expected, we just want the fixes applied
            log.info("[FrontendGeneratorNode] eslint --fix (exit={}) — applied fixes:\n{}",
                    result.exitCode(), result.output());
        }
    }

    private Set<String> detectShadcnComponents(Path workspace) {
        Path srcDir = workspace.resolve("frontend/src");
        if (!Files.exists(srcDir)) return Set.of();
        Set<String> components = new TreeSet<>();
        try (var stream = Files.walk(srcDir)) {
            stream.filter(p -> p.toString().endsWith(".ts") || p.toString().endsWith(".tsx"))
                    .forEach(p -> {
                        try {
                            Matcher m = SHADCN_IMPORT.matcher(Files.readString(p));
                            while (m.find()) components.add(m.group(1));
                        } catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            log.warn("[FrontendGeneratorNode] Could not scan for shadcn components: {}", e.getMessage());
        }
        return components;
    }

    // ── shouldSkip ────────────────────────────────────────────────────────────

    private boolean shouldSkip(FileSpec spec, FeatureSpec feature, boolean requestedChangesMode) {
        if (spec == null) return false;
        if (requestedChangesMode) return feature == null || !feature.isChangeRequired();
        String status = spec.getStatus();
        // GENERATION_FAILED is NOT skipped — falls through to needsGeneration for a fresh attempt.
        return "SPEC_COMPLIANT".equalsIgnoreCase(status);
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

    private Map<String, String> loadDependencyFiles(Path workspace, FileSpec spec) {
        Map<String, String> deps = new LinkedHashMap<>();
        if (spec == null) return deps;

        List<String> depPaths = spec.getImportsFrom();
        if (depPaths == null || depPaths.isEmpty()) depPaths = spec.getDependsOn();
        if (depPaths == null) return deps;

        for (String depPath : depPaths) {
            Path file = workspace.resolve(depPath);
            if (Files.exists(file)) {
                try {
                    deps.put(depPath, Files.readString(file));
                } catch (IOException ignored) {}
            }
        }
        return deps;
    }

    // Matches interface/type/enum/class declarations that are NOT already exported.
    // ^ in MULTILINE mode anchors to line start — indented declarations won't match.
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

    // TS2459: Module '"../types/foo"' declares 'Bar' locally, but it is not exported.
    // The module in the error is the types file — resolve it and add 'export' (zero LLM).
    private static final Pattern TS2459_MODULE =
            Pattern.compile("error TS2459: Module '\"([^\"]+)\"'");

    private boolean fixTs2459TypesFiles(String tscOutput, Path workspace) {
        Matcher m2459 = TS2459_MODULE.matcher(tscOutput);
        boolean anyFixed = false;
        Set<String> visited = new LinkedHashSet<>();
        while (m2459.find()) {
            String specifier = m2459.group(1); // e.g. "../types/order" or "@/types/menu"
            String resolved;
            if (specifier.startsWith("@/")) {
                resolved = "frontend/src/" + specifier.substring(2);
            } else {
                // Relative import — extract the last path segment and look in frontend/src/types/
                String fname = specifier.replaceAll(".*/", "");
                resolved = "frontend/src/types/" + fname;
            }
            if (visited.add(resolved)) {
                for (String ext : List.of(".ts", ".tsx", "")) {
                    Path typesFile = workspace.resolve(resolved + ext);
                    if (Files.exists(typesFile)) {
                        try {
                            ensureAllTypesExported(typesFile);
                            log.info("[FrontendGeneratorNode] TS2459 fix: added exports in {}", typesFile.getFileName());
                            anyFixed = true;
                        } catch (IOException e) {
                            log.warn("[FrontendGeneratorNode] TS2459 fix failed for {}: {}", typesFile, e.getMessage());
                        }
                        break;
                    }
                }
            }
        }
        return anyFixed;
    }

    // tsc error lines (tsconfig.app.json, run from frontendDir):
    //   src/context/AuthContext.tsx(1,1): error TS1434: ...
    private static final Pattern TSC_ERROR_PATH =
            Pattern.compile("(src/\\S+\\.tsx?)\\(\\d+,\\d+\\)");

    // TypeScript import specifier: from './foo' | from '../bar' | from '@/types/auth'
    private static final Pattern TS_IMPORT_PATTERN =
            Pattern.compile("from\\s+['\"]([^'\"]+)['\"]", Pattern.MULTILINE);

    private List<String> parseFailingFiles(String output, Path workspace) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        Matcher m = TSC_ERROR_PATH.matcher(output);
        while (m.find() && paths.size() < 3) {
            String rel = "frontend/" + m.group(1);
            if (Files.exists(workspace.resolve(rel))) paths.add(rel);
        }
        return new ArrayList<>(paths);
    }

    /**
     * Builds the fix context from four sources:
     * 1. Dep files declared in the failing file's spec (imports_from / depends_on)
     * 2. Auto-resolved imports: parse actual `from '...'` statements in the failing file
     *    and load the matching workspace files — so the fix-LLM always sees the real type
     *    definitions, even when the spec's importsFrom list is incomplete.
     * 3. tsconfig.json + package.json — compiler settings and installed packages
     * 4. triggerFilePath — the file we just wrote that caused the other file to break
     *    (null when the failing file IS the current file)
     */
    private Map<String, String> buildFixContext(WorkspaceReader reader, FileSpec spec,
                                                String failingPath, String triggerFilePath,
                                                Path workspace) {
        Map<String, String> ctx = new LinkedHashMap<>();
        if (spec != null) {
            List<String> deps = spec.getImportsFrom();
            if (deps == null || deps.isEmpty()) deps = spec.getDependsOn();
            if (deps != null) deps.forEach(dep -> addIfFound(ctx, reader, dep));
        }
        resolveTypeScriptImports(ctx, reader, failingPath, workspace);
        if (triggerFilePath != null) resolveTypeScriptImports(ctx, reader, triggerFilePath, workspace);
        // Directory listing tells the fix-LLM what files actually exist in the same folder —
        // prevents hallucinated subdirectory imports like './Header/Header' when the file is 'Header.tsx'.
        addDirectoryListing(ctx, failingPath, workspace);
        addIfFound(ctx, reader, "frontend/tsconfig.json");
        addIfFound(ctx, reader, "frontend/package.json");
        if (triggerFilePath != null) addIfFound(ctx, reader, triggerFilePath);
        return capContext(ctx);
    }

    // ~30KB is comfortably within Gemini Flash's context but prevents timeout on large projects.
    private static final int MAX_FIX_CONTEXT_CHARS = 30_000;

    private Map<String, String> capContext(Map<String, String> ctx) {
        int total = ctx.values().stream().mapToInt(String::length).sum();
        if (total <= MAX_FIX_CONTEXT_CHARS) return ctx;
        log.warn("[FrontendGeneratorNode] Fix context {} chars — trimming largest non-essential entries", total);
        int[] remaining = {total};
        new ArrayList<>(ctx.entrySet()).stream()
                .filter(e -> !e.getKey().startsWith("[")          // keep directory listings
                          && !e.getKey().endsWith("tsconfig.json")
                          && !e.getKey().endsWith("package.json"))
                .sorted((a, b) -> b.getValue().length() - a.getValue().length()) // largest first
                .forEach(e -> {
                    if (remaining[0] > MAX_FIX_CONTEXT_CHARS) {
                        ctx.remove(e.getKey());
                        remaining[0] -= e.getValue().length();
                    }
                });
        return ctx;
    }

    /**
     * Parses `from '...'` statements in a TypeScript file and loads the referenced
     * workspace files into ctx. Handles relative imports (./foo, ../bar) and the
     * @/ alias (maps to frontend/src/). Skips node_modules imports.
     */
    private void resolveTypeScriptImports(Map<String, String> ctx, WorkspaceReader reader,
                                           String filePath, Path workspace) {
        String content = reader.readFile(filePath);
        if (content.startsWith("FILE_NOT_FOUND:")) return;
        Path fileDir = workspace.resolve(filePath).getParent();
        Matcher m = TS_IMPORT_PATTERN.matcher(content);
        while (m.find()) {
            String specifier = m.group(1);
            String base;
            if (specifier.startsWith("./") || specifier.startsWith("../")) {
                base = workspace.relativize(fileDir.resolve(specifier).normalize()).toString();
            } else if (specifier.startsWith("@/")) {
                base = "frontend/src/" + specifier.substring(2);
                // Skip shadcn UI components — they're generated code, not user-written.
                // Including them bloats the fix context by 30-50KB and causes Gemini timeouts.
                if (base.startsWith("frontend/src/components/ui/")) continue;
            } else {
                continue; // node_modules import — skip
            }
            for (String ext : List.of("", ".ts", ".tsx", "/index.ts", "/index.tsx")) {
                String candidate = base + ext;
                if (!ctx.containsKey(candidate)) {
                    addIfFound(ctx, reader, candidate);
                    if (ctx.containsKey(candidate)) break;
                }
            }
        }
    }

    /**
     * Returns false when the fix-LLM returned a prose explanation instead of code.
     * Prose starts with natural-language words; TypeScript files must start with a
     * recognised code token. Prevents corrupted files from ever reaching disk.
     */
    private boolean looksLikeCode(String content, String filePath) {
        if (content == null || content.isBlank()) return false;
        String firstToken = content.stripLeading().split("[\\s({]")[0];
        if (filePath.endsWith(".ts") || filePath.endsWith(".tsx")) {
            return firstToken.matches(
                    "import|export|const|let|var|function|class|interface|type|enum|//|/\\*|<!--|@");
        }
        if (filePath.endsWith(".java")) {
            return firstToken.matches("package|import|//|/\\*|@|public|private|protected|class|enum|record");
        }
        return true;
    }

    private void addDirectoryListing(Map<String, String> ctx, String filePath, Path workspace) {
        Path fileDir = workspace.resolve(filePath).getParent();
        if (!Files.exists(fileDir)) return;
        try (var stream = Files.list(fileDir)) {
            List<String> files = stream.map(p -> p.getFileName().toString()).sorted().toList();
            if (!files.isEmpty()) {
                ctx.put("[files in " + workspace.relativize(fileDir) + "/]", String.join("\n", files));
            }
        } catch (IOException ignored) {}
    }

    private void addIfFound(Map<String, String> ctx, WorkspaceReader reader, String path) {
        String content = reader.readFile(path);
        if (!content.startsWith("FILE_NOT_FOUND:")) ctx.put(path, content);
    }

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

    // Open-brace count > close-brace count means the LLM stopped mid-output (token limit hit).
    private static boolean isBraceTruncated(String content) {
        if (content == null || content.isBlank()) return true;
        int depth = 0;
        for (char c : content.toCharArray()) {
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return depth > 0;
    }

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
