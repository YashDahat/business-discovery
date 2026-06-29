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
import com.business.discovery.worker.util.CompileErrorClassifier;
import com.business.discovery.worker.util.EnvVarScanner;
import com.business.discovery.worker.util.JavaClassRegistry;
import com.business.discovery.worker.util.JavaFileTemplater;
import com.business.discovery.worker.util.JavaImportResolver;
import com.business.discovery.worker.util.JavaImportSanitizer;
import com.business.discovery.worker.util.JavaPackageSanitizer;
import com.business.discovery.worker.util.LayerOrderUtil;
import com.business.discovery.worker.service.GitService;
import com.business.discovery.worker.util.WorkspaceReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Order(7)
@Slf4j
public class BackendGeneratorNode implements WorkerNode {

    private static final int MAX_COMPILE_FIX_ATTEMPTS = 3;
    private static final int MAX_SPEC_FIX_ROUNDS = 2;

    // Matches any Java class name (UpperCamelCase) in free text — used to auto-resolve
    // class references in featureInstruction/fileRole to actual workspace files.
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile("\\b([A-Z][A-Za-z0-9]+)\\b");

    // Matches project import statements in generated Java files — used to auto-resolve
    // dependencies during the fix stage without relying solely on the spec's importsFrom.
    private static final Pattern JAVA_IMPORT_PATTERN =
            Pattern.compile("^import\\s+([\\w.]+);", Pattern.MULTILINE);

    // Layers that are purely mechanical (no business logic) — skip Pro spec compliance,
    // mark SPEC_COMPLIANT immediately after compile passes.
    // Threshold: backendPriority ≤ 40 = ENUM, ENTITY, DTO-ITEM, DTO, REPOSITORY
    private static final int MECHANICAL_LAYER_THRESHOLD = 40;

    private static final int CHECKPOINT_EVERY_N_FILES = 3;

    private final LlmGeneratorService flashLlm;
    private final LlmGeneratorService fixLlm;
    private final BuildToolService buildToolService;
    private final GeneratedFileRepository fileRepo;
    private final GitService gitService;

    public BackendGeneratorNode(@Qualifier("geminiFlash") LlmGeneratorService flashLlm,
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
        Path backendDir = workspace.resolve("backend");

        ArchitectureSpec architectureSpec = loadSpec(workspace);
        Map<String, FileSpec> specByPath = buildSpecByPath(architectureSpec);
        Map<String, FeatureSpec> featuresByName = buildFeaturesByName(architectureSpec);
        // className → exact import statement for every standalone spec file —
        // used to detect and strip nested enum/record declarations that the LLM puts inside
        // entity/DTO files instead of generating standalone files
        Map<String, String> standaloneClassImports = buildStandaloneClassImports(architectureSpec);

        // Build class registry for deterministic import resolution (no LLM)
        JavaClassRegistry classRegistry = JavaClassRegistry.buildFromSpec(architectureSpec);

        // Proactively inject known third-party deps based on spec keywords — prevents reactive
        // injection cycles where missing deps cause compile failures and waste fix-LLM attempts
        preInjectDependencies(backendDir.resolve("pom.xml"), architectureSpec);

        List<FileEntry> backendFiles = ctx.getFileManifest().stream()
                .filter(e -> e.type() == FileType.BACKEND)
                .sorted(Comparator.comparingInt(LayerOrderUtil::backendPriority))
                .toList();

        boolean requestedChangesMode = ctx.getBriefCtx().requestedChanges() != null
                && !ctx.getBriefCtx().requestedChanges().isBlank();

        log.info("[BackendGeneratorNode] Processing {} backend files in layer order", backendFiles.size());

        // Tracks files that accumulated GENERATION_FAILED during this run.
        // Passed to runCompileStage so broken files can be hidden before mvn compile,
        // preventing one failing file from cascading to poison all downstream files.
        Set<Path> generationFailedFiles = new LinkedHashSet<>();
        int filesProcessed = 0;

        try {
            for (FileEntry entry : backendFiles) {
                FileSpec spec = specByPath.get(entry.path());
                FeatureSpec feature = spec != null && spec.getFeatureName() != null
                        ? featuresByName.get(spec.getFeatureName()) : null;
                Path filePath = workspace.resolve(entry.path());

                if (shouldSkip(spec, feature, requestedChangesMode)) {
                    log.info("[BackendGeneratorNode] Skipping [{}]: {}", layerName(entry), entry.path());
                    continue;
                }

                String featureInstruction = feature != null ? feature.getFeatureInstruction() : null;
                String fileRole = spec != null ? spec.getFileRole() : null;
                if (featureInstruction == null || featureInstruction.isBlank()) {
                    log.warn("[BackendGeneratorNode] No featureInstruction for '{}' (file: {}) — skipping",
                            spec != null ? spec.getFeatureName() : "unknown", entry.path());
                    continue;
                }

                String status = spec != null ? spec.getStatus() : "PLANNED";

                // Stage 3: PLANNED → GENERATED
                // GENERATION_FAILED is also regenerated on retry runs — give a fresh LLM attempt
                boolean needsGeneration = "PLANNED".equalsIgnoreCase(status)
                        || "GENERATION_FAILED".equalsIgnoreCase(status)
                        || (requestedChangesMode && feature != null && feature.isChangeRequired());
                if (needsGeneration) {
                    log.info("[BackendGeneratorNode] Stage 3 — generating [{}]: {}", layerName(entry), entry.path());
                    status = runGenerateStage(ctx, workspace, entry, filePath, spec,
                            featureInstruction, fileRole, requestedChangesMode, feature,
                            standaloneClassImports, classRegistry);
                }

                // Stage 1: GENERATED → VALIDATED
                if ("GENERATED".equalsIgnoreCase(status)) {
                    log.info("[BackendGeneratorNode] Stage 1 — compiling [{}]: {}", layerName(entry), entry.path());
                    status = runCompileStage(ctx, workspace, backendDir, entry, filePath, spec, specByPath, generationFailedFiles);
                }

                // Track files that accumulated failures this run — used to prevent cascade poisoning
                if ("GENERATION_FAILED".equalsIgnoreCase(status)) {
                    generationFailedFiles.add(workspace.resolve(entry.path()));
                }

                // Stage 2: VALIDATED → SPEC_COMPLIANT
                if ("VALIDATED".equalsIgnoreCase(status)) {
                    log.info("[BackendGeneratorNode] Stage 2 — spec check [{}]: {}", layerName(entry), entry.path());
                    runSpecCheckStage(ctx, workspace, backendDir, entry, filePath, spec,
                            featureInstruction, fileRole, specByPath, standaloneClassImports, generationFailedFiles);
                }

                // Push a WIP checkpoint every 3 files so work survives a container kill.
                if (++filesProcessed % CHECKPOINT_EVERY_N_FILES == 0) {
                    gitService.commitAndPushCheckpoint(workspace,
                            "chore: backend wip — " + filesProcessed + " files (attempt " + ctx.getAttemptNumber() + ")",
                            ctx.getGithubBranch());
                }
            }
        } catch (WorkerException e) {
            throw e;
        } catch (IOException e) {
            throw new WorkerException(FailureType.INFRA,
                    "Failed during backend generation: " + e.getMessage(), e);
        }

        try {
            runRecoveryPass(ctx, workspace, backendDir, backendFiles, specByPath, standaloneClassImports);
        } catch (IOException e) {
            log.warn("[BackendGeneratorNode] Recovery pass failed: {}", e.getMessage());
        }

        // Scan all generated @Value annotations and add any missing keys to application.properties.
        // Prevents Spring Boot startup failures from missing property bindings.
        Path backendSrc = backendDir.resolve("src/main/java");
        Path propsFile  = backendDir.resolve("src/main/resources/application.properties");
        Set<String> valueKeys = EnvVarScanner.scanJavaFiles(backendSrc);
        EnvVarScanner.augmentApplicationProperties(propsFile, valueKeys);
        EnvVarScanner.augmentDotEnvExample(workspace, valueKeys);

        log.info("[BackendGeneratorNode] Done — {} backend files processed", backendFiles.size());
    }

    // ── Stage 3: PLANNED → GENERATED ─────────────────────────────────────────

    private String runGenerateStage(WorkerContext ctx, Path workspace, FileEntry entry,
                                    Path filePath, FileSpec spec, String featureInstruction,
                                    String fileRole, boolean requestedChangesMode,
                                    FeatureSpec feature, Map<String, String> standaloneClassImports,
                                    JavaClassRegistry classRegistry) throws IOException {
        Files.createDirectories(filePath.getParent());

        int layerPriority = LayerOrderUtil.backendPriority(entry);
        JavaFileTemplater.TemplateType templateType = JavaFileTemplater.classify(spec, layerPriority);

        String content;
        if (templateType != JavaFileTemplater.TemplateType.NONE) {
            // Mechanical layer — generate from template, no LLM call
            content = JavaFileTemplater.generate(spec, classRegistry.getBasePackage(), templateType);
            if (content == null) {
                // Template returned null for some reason — fall back to LLM
                log.warn("[BackendGeneratorNode] Template returned null for {} — falling back to LLM", entry.path());
                content = generateWithLlm(workspace, entry, spec, featureInstruction, fileRole,
                        requestedChangesMode, feature, standaloneClassImports, existingContent(filePath, requestedChangesMode, feature));
            }
        } else {
            content = generateWithLlm(workspace, entry, spec, featureInstruction, fileRole,
                    requestedChangesMode, feature, standaloneClassImports, existingContent(filePath, requestedChangesMode, feature));
        }

        content = stripNestedEnums(content, standaloneClassImports, entry.path());
        // Fix Spring Boot 4 / Jackson 3.x package rename before any other sanitizers run
        content = fixSpringBoot4Imports(content);

        // Truncation guard: if Flash hit its output token limit the file has unbalanced braces.
        // A patch cannot fix a structurally incomplete file — skip all fix-LLM calls immediately.
        if (isBraceTruncated(content)) {
            log.warn("[BackendGeneratorNode] Truncated output detected for {} — marking GENERATION_FAILED (saves fix-LLM calls)", entry.path());
            Files.writeString(filePath, content);
            ArchitectureJsonUtil.updateFileStatus(workspace, entry.path(), "GENERATION_FAILED");
            upsertRecord(ctx, entry.path(), GeneratedFile.FileType.BACKEND, GeneratedFile.FileStatus.GENERATION_FAILED);
            return "GENERATION_FAILED";
        }

        Files.writeString(filePath, content);
        JavaPackageSanitizer.sanitize(filePath);
        JavaImportSanitizer.sanitize(filePath);
        // Deterministic import resolution — fixes wrong package prefixes and adds missing project imports
        JavaImportResolver.resolve(filePath, classRegistry);

        ArchitectureJsonUtil.updateFileStatus(workspace, entry.path(), "GENERATED");
        upsertRecord(ctx, entry.path(), GeneratedFile.FileType.BACKEND, GeneratedFile.FileStatus.GENERATED);
        return "GENERATED";
    }

    private String generateWithLlm(Path workspace, FileEntry entry, FileSpec spec,
                                    String featureInstruction, String fileRole,
                                    boolean requestedChangesMode, FeatureSpec feature,
                                    Map<String, String> standaloneClassImports, String existingContent) {
        Map<String, String> depFiles = loadDependencyFiles(workspace, spec,
                featureInstruction, fileRole, standaloneClassImports);
        return flashLlm.generateFileContent(entry.path(), featureInstruction,
                fileRole != null ? fileRole : "", depFiles, existingContent);
    }

    private String existingContent(Path filePath, boolean requestedChangesMode, FeatureSpec feature) {
        return (requestedChangesMode && feature != null && feature.isChangeRequired())
                ? readIfExists(filePath) : null;
    }

    // ── Stage 1: GENERATED → VALIDATED ───────────────────────────────────────

    private String runCompileStage(WorkerContext ctx, Path workspace, Path backendDir,
                                   FileEntry entry, Path filePath,
                                   FileSpec spec, Map<String, FileSpec> specByPath,
                                   Set<Path> generationFailedFiles) throws IOException {
        WorkspaceReader reader = new WorkspaceReader(workspace);
        // Build a minimal registry for error classification — uses spec already loaded in execute()
        JavaClassRegistry classRegistry = JavaClassRegistry.buildFromSpec(loadSpec(workspace));

        // Temporarily hide GENERATION_FAILED files from previous iterations so they cannot
        // cascade their compile errors into the file currently being validated.
        Map<Path, Path> hiddenFiles = hideGenerationFailedFiles(generationFailedFiles, filePath);
        try {
        boolean prevHadWrongImportPath = false;
        for (int attempt = 0; attempt <= MAX_COMPILE_FIX_ATTEMPTS; attempt++) {
            BuildToolService.BuildResult result = buildToolService.runMvnCompile(backendDir);
            if (result.success()) {
                ArchitectureJsonUtil.updateFileStatus(workspace, entry.path(), "VALIDATED");
                upsertRecord(ctx, entry.path(), GeneratedFile.FileType.BACKEND, GeneratedFile.FileStatus.VALIDATED);
                log.info("[BackendGeneratorNode] Compiled OK: {}", entry.path());
                return "VALIDATED";
            }

            // Javac-level truncation: "reached end of file while parsing" means the file is
            // structurally incomplete — a patch cannot fix it, only regeneration can.
            // Break immediately to skip all fix-LLM calls and go straight to GENERATION_FAILED.
            if (isJavaTruncation(result.output())) {
                log.warn("[BackendGeneratorNode] Java truncation error for {} — skipping all fix attempts", entry.path());
                break;
            }

            // Classify errors to decide fix strategy
            Set<CompileErrorClassifier.ErrorCategory> categories =
                    CompileErrorClassifier.classify(result.output(), classRegistry);
            log.info("[BackendGeneratorNode] Compile error categories (attempt {}): {}", attempt + 1, categories);

            // WRONG_IMPORT_PATH persisting after a deterministic fix means the LLM introduced
            // hallucinated sub-packages (e.g. com.biz.service.impl) that JavaImportResolver cannot
            // fix — it only knows correct FQNs from the spec. Escalate to LLM after one failed
            // deterministic attempt so the LLM can correct the package path itself.
            boolean hasWrongImportPath = categories.contains(CompileErrorClassifier.ErrorCategory.WRONG_IMPORT_PATH);
            if (hasWrongImportPath && prevHadWrongImportPath) {
                log.warn("[BackendGeneratorNode] WRONG_IMPORT_PATH persists after deterministic fix — escalating to LLM");
                categories.add(CompileErrorClassifier.ErrorCategory.LOGIC_ERROR);
            }
            prevHadWrongImportPath = hasWrongImportPath;

            // Deterministic fixes first — these don't consume LLM tokens
            if (CompileErrorClassifier.needsDeterministicFix(categories)) {
                injectMissingDependencies(backendDir.resolve("pom.xml"), result.output());
                // Re-run import resolver on all failing files; also apply SB4 Jackson migration
                List<String> failingPaths = parseFailingFiles(result.output(), workspace);
                for (String fp : failingPaths) {
                    Path p = workspace.resolve(fp);
                    if (Files.exists(p) && fp.endsWith(".java")) {
                        String jContent = Files.readString(p);
                        String jFixed = fixSpringBoot4Imports(jContent);
                        if (!jFixed.equals(jContent)) {
                            Files.writeString(p, jFixed);
                            log.info("[BackendGeneratorNode] Fixed Spring Boot 4 Jackson imports in {}", fp);
                        }
                        JavaImportResolver.resolve(p, classRegistry);
                    }
                }
                // If ONLY deterministic errors — don't count this as an LLM fix attempt
                if (!CompileErrorClassifier.needsLlmFix(categories)) continue;
            }

            if (attempt < MAX_COMPILE_FIX_ATTEMPTS && CompileErrorClassifier.needsLlmFix(categories)) {
                // Parse which files are actually failing — may not be the file we just wrote
                List<String> toFix = parseFailingFiles(result.output(), workspace);
                if (toFix.isEmpty()) toFix = List.of(entry.path());

                log.warn("[BackendGeneratorNode] Compile failed (attempt {}/{}) — LLM fix for {} file(s): {}",
                        attempt + 1, MAX_COMPILE_FIX_ATTEMPTS, toFix.size(), toFix);

                for (String failingPath : toFix) {
                    if (!reader.exists(failingPath)) continue;
                    FileSpec failingSpec = specByPath.get(failingPath);
                    // triggerFilePath: the file we just wrote that caused the conflict — null when
                    // the failing file is the current file (no separate trigger to include)
                    String triggerFilePath = failingPath.equals(entry.path()) ? null : entry.path();
                    Map<String, String> fixCtx = buildFixContext(reader, failingSpec, triggerFilePath);
                    String content = reader.readFile(failingPath);
                    String fixed = fixLlm.fixFileContent(failingPath, content, result.output(), fixCtx);
                    // Apply SB4 Jackson migration after LLM fix — LLM may re-introduce old imports
                    fixed = fixSpringBoot4Imports(fixed);
                    Files.writeString(workspace.resolve(failingPath), fixed);
                }
            } else if (attempt >= MAX_COMPILE_FIX_ATTEMPTS) {
                break;
            }
        }

        log.error("[BackendGeneratorNode] Compile still failing for {} after {} attempts — GENERATION_FAILED",
                entry.path(), MAX_COMPILE_FIX_ATTEMPTS);
        ArchitectureJsonUtil.updateFileStatus(workspace, entry.path(), "GENERATION_FAILED");
        upsertRecord(ctx, entry.path(), GeneratedFile.FileType.BACKEND, GeneratedFile.FileStatus.GENERATION_FAILED);
        return "GENERATION_FAILED";
        } finally {
            restoreHiddenFiles(hiddenFiles);
        }
    }

    // ── Stage 2: VALIDATED → SPEC_COMPLIANT ──────────────────────────────────

    private void runSpecCheckStage(WorkerContext ctx, Path workspace, Path backendDir,
                                   FileEntry entry, Path filePath, FileSpec spec,
                                   String featureInstruction, String fileRole,
                                   Map<String, FileSpec> specByPath,
                                   Map<String, String> standaloneClassImports,
                                   Set<Path> generationFailedFiles) throws IOException {
        // Mechanical layers (ENUM, ENTITY, DTO, REPOSITORY) have no business logic —
        // if they compile they are correct. Skip the LLM compliance check entirely.
        if (LayerOrderUtil.backendPriority(entry) <= MECHANICAL_LAYER_THRESHOLD) {
            ArchitectureJsonUtil.updateFileStatus(workspace, entry.path(), "SPEC_COMPLIANT");
            upsertRecord(ctx, entry.path(), GeneratedFile.FileType.BACKEND, GeneratedFile.FileStatus.SPEC_COMPLIANT);
            log.info("[BackendGeneratorNode] Mechanical layer — auto-compliant: {}", entry.path());
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
                upsertRecord(ctx, entry.path(), GeneratedFile.FileType.BACKEND, GeneratedFile.FileStatus.SPEC_COMPLIANT);
                log.info("[BackendGeneratorNode] Spec compliant: {}", entry.path());
                return;
            }

            log.warn("[BackendGeneratorNode] Spec drift for {} (round {}/{}) — issues: {}",
                    entry.path(), round + 1, MAX_SPEC_FIX_ROUNDS, compliance.issues());

            if (round == MAX_SPEC_FIX_ROUNDS - 1) {
                log.warn("[BackendGeneratorNode] Max spec-fix rounds exhausted for {} — leaving as VALIDATED",
                        entry.path());
                return;
            }

            // Keep featureInstruction intact; append corrections to fileRole only
            String correctedRole = (fileRole != null ? fileRole : "")
                    + "\n\nFix these spec deviations:\n- "
                    + String.join("\n- ", compliance.issues());
            Map<String, String> depFiles = loadDependencyFiles(workspace, spec,
                    featureInstruction, fileRole, standaloneClassImports);
            String corrected = flashLlm.generateFileContent(entry.path(), featureInstruction, correctedRole, depFiles, null);
            Files.writeString(filePath, corrected);
            ArchitectureJsonUtil.updateFileStatus(workspace, entry.path(), "GENERATED");

            // Re-run Stage 1 on the corrected file
            String newStatus = runCompileStage(ctx, workspace, backendDir, entry, filePath, spec, specByPath, generationFailedFiles);
            if (!"VALIDATED".equalsIgnoreCase(newStatus)) return; // GENERATION_FAILED — stop
        }
    }

    // ── Cascade contamination prevention ─────────────────────────────────────

    /**
     * Moves GENERATION_FAILED files to a system temp directory so mvn compile cannot see them.
     * Using /tmp (not .bak in-place) keeps the workspace clean — git can never pick up
     * stray .java.bak files regardless of container crashes or unexpected exits.
     * If the container dies mid-compile the temp files stay in /tmp and are cleaned on reboot.
     *
     * Returns a map of original path → temp location for restoration in the finally block.
     */
    private Map<Path, Path> hideGenerationFailedFiles(Set<Path> failedFiles, Path excludeFile) {
        if (failedFiles.isEmpty()) return Map.of();
        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("worker-hide-");
        } catch (IOException e) {
            log.warn("[BackendGeneratorNode] Could not create temp dir for hiding files: {}", e.getMessage());
            return Map.of();
        }
        Map<Path, Path> hidden = new LinkedHashMap<>();
        for (Path f : failedFiles) {
            if (f.equals(excludeFile) || !Files.exists(f)) continue;
            Path temp = tempDir.resolve(f.getFileName());
            try {
                Files.move(f, temp, StandardCopyOption.REPLACE_EXISTING);
                hidden.put(f, temp);
            } catch (IOException e) {
                log.warn("[BackendGeneratorNode] Could not hide {}: {}", f.getFileName(), e.getMessage());
            }
        }
        if (!hidden.isEmpty()) {
            log.info("[BackendGeneratorNode] Hid {} GENERATION_FAILED file(s) to prevent cascade poisoning", hidden.size());
        }
        return hidden;
    }

    /** Restores files previously moved out of the workspace by hideGenerationFailedFiles. */
    private void restoreHiddenFiles(Map<Path, Path> hiddenFiles) {
        for (Map.Entry<Path, Path> e : hiddenFiles.entrySet()) {
            Path original = e.getKey();
            Path temp = e.getValue();
            if (Files.exists(temp)) {
                try {
                    Files.move(temp, original, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ex) {
                    log.warn("[BackendGeneratorNode] Could not restore {}: {}", original.getFileName(), ex.getMessage());
                }
            }
        }
        // Best-effort cleanup of temp dir(s)
        hiddenFiles.values().stream()
                .map(Path::getParent)
                .distinct()
                .forEach(dir -> { try { Files.deleteIfExists(dir); } catch (IOException ignored) {} });
    }

    // ── shouldSkip ────────────────────────────────────────────────────────────

    private boolean shouldSkip(FileSpec spec, FeatureSpec feature, boolean requestedChangesMode) {
        if (spec == null) return false;
        if (requestedChangesMode) return feature == null || !feature.isChangeRequired();
        String status = spec.getStatus();
        // GENERATION_FAILED must NOT be skipped — re-generate on retry runs
        return "SPEC_COMPLIANT".equalsIgnoreCase(status)
                || "VALIDATED".equalsIgnoreCase(status);
    }

    // ── Spec loading ──────────────────────────────────────────────────────────

    private ArchitectureSpec loadSpec(Path workspace) {
        if (!ArchitectureJsonUtil.exists(workspace)) return new ArchitectureSpec();
        try {
            return ArchitectureJsonUtil.read(workspace);
        } catch (IOException e) {
            log.warn("[BackendGeneratorNode] Could not load spec: {}", e.getMessage());
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

    private Map<String, String> loadDependencyFiles(Path workspace, FileSpec spec,
                                                     String featureInstruction, String fileRole,
                                                     Map<String, String> standaloneClassImports) {
        Map<String, String> deps = new LinkedHashMap<>();
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

        // 2. Auto-resolve any class name mentioned in featureInstruction / fileRole.
        // standaloneClassImports maps ClassName → "import com.x.y.ClassName;" so we can
        // derive the workspace path and load the real file as context. This prevents the
        // LLM from guessing field names on types it has never seen (e.g. OrderItemRequest).
        String combinedText = (featureInstruction != null ? featureInstruction : "")
                + " " + (fileRole != null ? fileRole : "");
        Matcher cm = CLASS_NAME_PATTERN.matcher(combinedText);
        while (cm.find()) {
            String className = cm.group(1);
            String importStmt = standaloneClassImports.get(className);
            if (importStmt == null) continue;
            String fqn = importStmt.replace("import ", "").replace(";", "").trim();
            String relPath = "backend/src/main/java/" + fqn.replace('.', '/') + ".java";
            if (!deps.containsKey(relPath)) {
                Path file = workspace.resolve(relPath);
                if (Files.exists(file)) {
                    try { deps.put(relPath, Files.readString(file)); } catch (IOException ignored) {}
                }
            }
        }

        return deps;
    }

    // Maven error lines: [ERROR] /absolute/path/to/File.java:[line,col] error: ...
    private static final Pattern MAVEN_ERROR_PATH =
            Pattern.compile("\\[ERROR\\] (/.+\\.java):\\[\\d+,\\d+\\]");

    // Javac "package X does not exist" — maps to Maven coordinates for injection.
    // Value is a list of {groupId, artifactId, version, scope?} arrays
    // (some packages like jjwt need multiple artifacts).
    private static final Pattern MISSING_PACKAGE_PATTERN =
            Pattern.compile("package ([\\w.]+) does not exist");

    // Natural-language keywords found in spec descriptions that hint at which packages are needed
    private static final Map<String, String> KEYWORD_TO_PACKAGE = Map.of(
            "razorpay",    "com.razorpay",
            "stripe",      "com.stripe",
            "twilio",      "com.twilio",
            "modelmapper", "org.modelmapper",
            "sendgrid",    "com.sendgrid",
            "cloudinary",  "com.cloudinary",
            "opencsv",     "com.opencsv",
            "gson",        "com.google.gson"
    );

    // Each entry: package prefix → 2-D array of Maven artifact rows: {groupId, artifactId, version, scope?}
    // Using String[][] avoids List.of(String[]) varargs ambiguity at the Java compiler level.
    private static final Map<String, String[][]> PACKAGE_TO_MAVEN_COORDS;
    static {
        Map<String, String[][]> m = new java.util.LinkedHashMap<>();
        m.put("org.modelmapper",          new String[][]{{"org.modelmapper",    "modelmapper",        "3.2.0",    null}});
        m.put("com.razorpay",             new String[][]{{"com.razorpay",       "razorpay-java",      "1.4.3",    null}});
        m.put("org.json",                 new String[][]{{"org.json",            "json",               "20240303", null}});
        m.put("com.stripe",               new String[][]{{"com.stripe",          "stripe-java",        "24.3.0",   null}});
        m.put("com.twilio",               new String[][]{{"com.twilio.sdk",      "twilio",             "9.14.0",   null}});
        m.put("com.sendgrid",             new String[][]{{"com.sendgrid",        "sendgrid-java",      "4.10.2",   null}});
        m.put("com.cloudinary",           new String[][]{{"com.cloudinary",      "cloudinary-http45",  "1.39.0",   null}});
        m.put("org.apache.commons.lang3", new String[][]{{"org.apache.commons",  "commons-lang3",      "3.14.0",   null}});
        m.put("org.apache.commons.io",    new String[][]{{"commons-io",          "commons-io",         "2.15.1",   null}});
        m.put("com.google.gson",          new String[][]{{"com.google.code.gson","gson",               "2.10.1",   null}});
        m.put("com.opencsv",              new String[][]{{"com.opencsv",          "opencsv",           "5.9",      null}});
        // jjwt is already injected by ProjectPlanningNode.injectJwtDependencies() at scaffold time;
        // this entry is a safety net for retry runs where pom.xml was reset from git.
        m.put("io.jsonwebtoken",          new String[][]{
                {"io.jsonwebtoken", "jjwt-api",     "0.11.5", null},
                {"io.jsonwebtoken", "jjwt-impl",    "0.11.5", "runtime"},
                {"io.jsonwebtoken", "jjwt-jackson", "0.11.5", "runtime"}
        });
        PACKAGE_TO_MAVEN_COORDS = java.util.Collections.unmodifiableMap(m);
    }

    private List<String> parseFailingFiles(String output, Path workspace) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        Matcher m = MAVEN_ERROR_PATH.matcher(output);
        while (m.find() && paths.size() < 3) {
            try {
                Path abs = Path.of(m.group(1));
                String rel = workspace.relativize(abs).toString();
                if (Files.exists(workspace.resolve(rel))) paths.add(rel);
            } catch (Exception ignored) {}
        }
        return new ArrayList<>(paths);
    }

    private void preInjectDependencies(Path pomPath, ArchitectureSpec spec) {
        if (!Files.exists(pomPath)) return;
        if (spec.getFiles() == null) return;
        // Collect all spec text that might hint at which packages are needed
        StringBuilder text = new StringBuilder();
        for (FileSpec f : spec.getFiles()) {
            if (f.getDescription() != null) text.append(f.getDescription().toLowerCase()).append(' ');
            if (f.getFileRole() != null) text.append(f.getFileRole().toLowerCase()).append(' ');
        }
        if (spec.getFeatures() != null) {
            for (var feat : spec.getFeatures()) {
                if (feat.getFeatureInstruction() != null) text.append(feat.getFeatureInstruction().toLowerCase()).append(' ');
            }
        }
        String specText = text.toString();

        // Find which package prefixes are hinted at by the spec
        Set<String> neededPackagePrefixes = new LinkedHashSet<>();
        for (Map.Entry<String, String> kwEntry : KEYWORD_TO_PACKAGE.entrySet()) {
            if (specText.contains(kwEntry.getKey())) {
                neededPackagePrefixes.add(kwEntry.getValue());
            }
        }

        if (neededPackagePrefixes.isEmpty()) return;
        log.info("[BackendGeneratorNode] Pre-injecting deps for detected keywords: {}", neededPackagePrefixes);
        injectPackageDeps(pomPath, neededPackagePrefixes);

        // Also inject spec-declared mavenDependencies if present
        if (spec.getProjectDependencies() != null
                && spec.getProjectDependencies().getMavenDependencies() != null) {
            injectSpecDeclaredDeps(pomPath, spec.getProjectDependencies().getMavenDependencies());
        }
    }

    private void injectPackageDeps(Path pomPath, Set<String> packagePrefixes) {
        try {
            String pom = Files.readString(pomPath);
            StringBuilder toAdd = new StringBuilder();
            for (String pkg : packagePrefixes) {
                String[][] artifacts = PACKAGE_TO_MAVEN_COORDS.get(pkg);
                if (artifacts == null) continue;
                for (String[] coords : artifacts) {
                    String artifactTag = "<artifactId>" + coords[1] + "</artifactId>";
                    if (pom.contains(artifactTag) || toAdd.toString().contains(artifactTag)) continue;
                    toAdd.append("\t\t<dependency>\n")
                            .append("\t\t\t<groupId>").append(coords[0]).append("</groupId>\n")
                            .append("\t\t\t<artifactId>").append(coords[1]).append("</artifactId>\n")
                            .append("\t\t\t<version>").append(coords[2]).append("</version>\n");
                    if (coords.length > 3 && coords[3] != null) {
                        toAdd.append("\t\t\t<scope>").append(coords[3]).append("</scope>\n");
                    }
                    toAdd.append("\t\t</dependency>\n");
                    log.info("[BackendGeneratorNode] Pre-injected dep: {}:{}", coords[0], coords[1]);
                }
            }
            if (toAdd.isEmpty()) return;
            int idx = pom.lastIndexOf("</dependencies>");
            if (idx == -1) return;
            Files.writeString(pomPath, pom.substring(0, idx) + toAdd + pom.substring(idx));
        } catch (IOException e) {
            log.warn("[BackendGeneratorNode] Failed to pre-inject deps: {}", e.getMessage());
        }
    }

    private void injectSpecDeclaredDeps(Path pomPath,
            List<com.business.discovery.worker.service.llm.MavenCoordinate> mavenDeps) {
        try {
            String pom = Files.readString(pomPath);
            StringBuilder toAdd = new StringBuilder();
            for (var dep : mavenDeps) {
                if (dep.getGroupId() == null || dep.getArtifactId() == null) continue;
                String artifactTag = "<artifactId>" + dep.getArtifactId() + "</artifactId>";
                if (pom.contains(artifactTag) || toAdd.toString().contains(artifactTag)) continue;
                toAdd.append("\t\t<dependency>\n")
                        .append("\t\t\t<groupId>").append(dep.getGroupId()).append("</groupId>\n")
                        .append("\t\t\t<artifactId>").append(dep.getArtifactId()).append("</artifactId>\n");
                if (dep.getVersion() != null) {
                    toAdd.append("\t\t\t<version>").append(dep.getVersion()).append("</version>\n");
                }
                if (dep.getScope() != null && !dep.getScope().isBlank()) {
                    toAdd.append("\t\t\t<scope>").append(dep.getScope()).append("</scope>\n");
                }
                toAdd.append("\t\t</dependency>\n");
                log.info("[BackendGeneratorNode] Injected spec-declared dep: {}:{}", dep.getGroupId(), dep.getArtifactId());
            }
            if (toAdd.isEmpty()) return;
            int idx = pom.lastIndexOf("</dependencies>");
            if (idx == -1) return;
            Files.writeString(pomPath, pom.substring(0, idx) + toAdd + pom.substring(idx));
        } catch (IOException e) {
            log.warn("[BackendGeneratorNode] Failed to inject spec-declared deps: {}", e.getMessage());
        }
    }

    private void injectMissingDependencies(Path pomPath, String compileOutput) {
        try {
            String pom = Files.readString(pomPath);
            Set<String> neededPkgs = new LinkedHashSet<>();
            Matcher m = MISSING_PACKAGE_PATTERN.matcher(compileOutput);
            while (m.find()) {
                String missingPkg = m.group(1);
                for (String pkgPrefix : PACKAGE_TO_MAVEN_COORDS.keySet()) {
                    if (missingPkg.startsWith(pkgPrefix)) {
                        neededPkgs.add(pkgPrefix);
                        break;
                    }
                }
            }
            if (!neededPkgs.isEmpty()) injectPackageDeps(pomPath, neededPkgs);
        } catch (IOException e) {
            log.warn("[BackendGeneratorNode] Failed to read pom for missing dep check: {}", e.getMessage());
        }
    }

    /**
     * Builds the fix-LLM context from four sources:
     * 1. Dep files declared in the failing file's spec (imports_from / depends_on)
     * 2. Auto-resolved imports parsed from the failing file itself — catches cross-feature
     *    dependencies missing from the spec (e.g. OrderItemRequest not listed in OrderService)
     * 3. pom.xml — Spring Boot / Jakarta version so the LLM picks the right annotation imports
     * 4. triggerFilePath — the file we just wrote that caused the other file to break
     */
    private Map<String, String> buildFixContext(WorkspaceReader reader, FileSpec spec,
                                                String triggerFilePath) {
        Map<String, String> ctx = new LinkedHashMap<>();
        if (spec != null) {
            List<String> deps = spec.getImportsFrom();
            if (deps == null || deps.isEmpty()) deps = spec.getDependsOn();
            if (deps != null) deps.forEach(dep -> addIfFound(ctx, reader, dep));
            // Auto-resolve from the failing file's own import statements
            if (spec.getFilePath() != null) resolveJavaImports(ctx, reader, spec.getFilePath());
        }
        addIfFound(ctx, reader, "backend/pom.xml");
        if (triggerFilePath != null) {
            addIfFound(ctx, reader, triggerFilePath);
            resolveJavaImports(ctx, reader, triggerFilePath);
        }
        return ctx;
    }

    /**
     * Parses every "import x.y.Z;" statement in a Java file and loads any that map to an
     * existing workspace file. Converts FQN → relative path under backend/src/main/java/.
     */
    private void resolveJavaImports(Map<String, String> ctx, WorkspaceReader reader, String filePath) {
        String content = reader.readFile(filePath);
        if (content.startsWith("FILE_NOT_FOUND:")) return;
        Matcher m = JAVA_IMPORT_PATTERN.matcher(content);
        while (m.find()) {
            String relPath = "backend/src/main/java/" + m.group(1).replace('.', '/') + ".java";
            if (!ctx.containsKey(relPath)) addIfFound(ctx, reader, relPath);
        }
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
            log.warn("[BackendGeneratorNode] Could not read {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    /**
     * Spring Boot 4 ships Jackson 3.x which changed the group ID from com.fasterxml.jackson
     * to tools.jackson, and renamed JsonProcessingException to JacksonException. LLMs trained
     * on SB2/3 code consistently generate the old imports. Apply this as a deterministic
     * post-generation step before any compilation attempt.
     */
    private static String fixSpringBoot4Imports(String content) {
        return content
                .replace("import com.fasterxml.jackson.databind.", "import tools.jackson.databind.")
                .replace("import com.fasterxml.jackson.core.", "import tools.jackson.core.")
                .replace("import com.fasterxml.jackson.annotation.", "import tools.jackson.annotation.")
                .replace("JsonProcessingException", "JacksonException");
    }

    private String layerName(FileEntry entry) {
        return LayerOrderUtil.backendLayerName(entry);
    }

    // Open-brace count > close-brace count means the LLM stopped mid-output (token limit hit).
    // Does not handle braces inside string literals, but false-positive risk is negligible:
    // a well-formed Java file always has balanced braces at EOF.
    private static boolean isBraceTruncated(String content) {
        if (content == null || content.isBlank()) return true;
        int depth = 0;
        for (char c : content.toCharArray()) {
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return depth > 0;
    }

    // Javac's specific error for a syntactically incomplete file (truncated mid-output).
    private static boolean isJavaTruncation(String output) {
        return output.contains("reached end of file while parsing");
    }

    // ── Nested type safety net (enums + records) ─────────────────────────────

    /**
     * Builds a map of className → correct import statement for every standalone Java
     * spec file. Derives the package from the file's path so the import is always exact
     * (e.g. "Role" → "import com.waydownsouth.model.Role;",
     *        "OrderItemRequest" → "import com.waydownsouth.dto.OrderItemRequest;").
     */
    private Map<String, String> buildStandaloneClassImports(ArchitectureSpec spec) {
        if (spec.getFiles() == null) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (FileSpec f : spec.getFiles()) {
            String name = f.getFileName();
            String path = f.getFilePath();
            if (name == null || !name.endsWith(".java") || path == null) continue;
            String pkg = extractJavaPackage(path);
            if (pkg == null) continue;
            String className = name.replace(".java", "");
            result.put(className, "import " + pkg + "." + className + ";");
        }
        return result;
    }

    private static String extractJavaPackage(String filePath) {
        int idx = filePath.indexOf("java/");
        if (idx < 0) return null;
        String after = filePath.substring(idx + 5);
        int lastSlash = after.lastIndexOf('/');
        return lastSlash < 0 ? null : after.substring(0, lastSlash).replace('/', '.');
    }

    /**
     * Strips nested enum and record declarations from generated Java source when the type
     * name matches a known standalone spec file. Adds a corrective import so the file
     * still compiles against the standalone version.
     *
     * Handles modifiers: public/private/protected/static/final/abstract (any combination).
     * Uses [^{]* to skip record params and implements clauses before the opening brace.
     */
    private static final Pattern NESTED_TYPE_HEADER =
            Pattern.compile("(?m)^([ \\t]+)(?:(?:public|private|protected|static|final|abstract)[ \\t]+)*(?:enum|record)[ \\t]+(\\w+)[^{]*\\{");

    private String stripNestedEnums(String content, Map<String, String> standaloneClassImports,
                                    String currentFilePath) {
        Matcher m = NESTED_TYPE_HEADER.matcher(content);
        List<int[]> toRemove = new ArrayList<>();
        List<String> removedNames = new ArrayList<>();

        while (m.find()) {
            String indentation = m.group(1);
            String typeName    = m.group(2);
            if (indentation.isEmpty()) continue; // top-level type — don't touch
            if (!standaloneClassImports.containsKey(typeName)) continue;

            int openBrace = m.end() - 1;
            int depth = 1;
            int i = openBrace + 1;
            while (i < content.length() && depth > 0) {
                char c = content.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                i++;
            }
            while (i < content.length() && content.charAt(i) == '\n') i++;

            toRemove.add(new int[]{m.start(), i});
            removedNames.add(typeName);
        }

        if (toRemove.isEmpty()) return content;

        StringBuilder sb = new StringBuilder(content);
        for (int idx = toRemove.size() - 1; idx >= 0; idx--) {
            sb.delete(toRemove.get(idx)[0], toRemove.get(idx)[1]);
        }
        String stripped = sb.toString();

        // Add exact import from the spec-derived map (skips if already present)
        StringBuilder imports = new StringBuilder();
        for (String name : removedNames) {
            String imp = standaloneClassImports.get(name);
            if (imp != null && !stripped.contains(imp)) {
                imports.append(imp).append("\n");
                log.info("[BackendGeneratorNode] Stripped nested '{}' from {} — added import",
                        name, currentFilePath);
            }
        }
        if (imports.length() > 0) {
            int lastImport = stripped.lastIndexOf("\nimport ");
            if (lastImport >= 0) {
                int eol = stripped.indexOf('\n', lastImport + 1);
                stripped = stripped.substring(0, eol + 1) + imports + stripped.substring(eol + 1);
            }
        }

        return stripped;
    }

    // ── Recovery pass ─────────────────────────────────────────────────────────

    /**
     * Run after the main loop. Re-applies the safety net on every backend file (fix LLM
     * may have reintroduced nested types) then does a batch compile for all
     * GENERATION_FAILED files that may now resolve (their dependencies were generated after them).
     */
    private void runRecoveryPass(WorkerContext ctx, Path workspace, Path backendDir,
                                 List<FileEntry> files, Map<String, FileSpec> specByPath,
                                 Map<String, String> standaloneClassImports) throws IOException {
        List<String> strippedFiles = new ArrayList<>();

        for (FileEntry entry : files) {
            Path filePath = workspace.resolve(entry.path());
            if (!Files.exists(filePath)) continue;
            String content = Files.readString(filePath);
            String cleaned = stripNestedEnums(content, standaloneClassImports, entry.path());
            if (!cleaned.equals(content)) {
                Files.writeString(filePath, cleaned);
                strippedFiles.add(entry.path());
            }
        }

        long failedCount = files.stream()
                .map(e -> specByPath.get(e.path()))
                .filter(s -> s != null && "GENERATION_FAILED".equalsIgnoreCase(s.getStatus()))
                .count();

        if (strippedFiles.isEmpty() && failedCount == 0) {
            log.info("[BackendGeneratorNode] Recovery pass: nothing to do");
            return;
        }

        log.info("[BackendGeneratorNode] Recovery pass: {} stripped, {} GENERATION_FAILED — batch compile",
                strippedFiles.size(), failedCount);

        WorkspaceReader reader = new WorkspaceReader(workspace);
        for (int attempt = 0; attempt <= MAX_COMPILE_FIX_ATTEMPTS; attempt++) {
            BuildToolService.BuildResult result = buildToolService.runMvnCompile(backendDir);
            if (result.success()) {
                for (FileEntry entry : files) {
                    FileSpec spec = specByPath.get(entry.path());
                    if (spec == null || !"GENERATION_FAILED".equalsIgnoreCase(spec.getStatus())) continue;
                    ArchitectureJsonUtil.updateFileStatus(workspace, entry.path(), "VALIDATED");
                    upsertRecord(ctx, entry.path(), GeneratedFile.FileType.BACKEND, GeneratedFile.FileStatus.VALIDATED);
                    log.info("[BackendGeneratorNode] Recovery: {} → VALIDATED", entry.path());
                }
                return;
            }

            if (attempt < MAX_COMPILE_FIX_ATTEMPTS) {
                List<String> toFix = parseFailingFiles(result.output(), workspace);
                if (toFix.isEmpty()) break;
                log.warn("[BackendGeneratorNode] Recovery compile attempt {}/{} — fixing: {}",
                        attempt + 1, MAX_COMPILE_FIX_ATTEMPTS, toFix);
                for (String failingPath : toFix) {
                    if (!reader.exists(failingPath)) continue;
                    Map<String, String> fixCtx = buildFixContext(reader, specByPath.get(failingPath), null);
                    String fixed = fixLlm.fixFileContent(failingPath, reader.readFile(failingPath),
                            result.output(), fixCtx);
                    Files.writeString(workspace.resolve(failingPath), fixed);
                }
            }
        }
        log.warn("[BackendGeneratorNode] Recovery pass batch compile still failing after {} attempts",
                MAX_COMPILE_FIX_ATTEMPTS);
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
