 package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.model.GeneratedFile;
import com.business.discovery.worker.repository.GeneratedFileRepository;
import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FeatureCard;
import com.business.discovery.worker.service.llm.FeatureSpec;
import com.business.discovery.worker.service.llm.FileEntry;
import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.business.discovery.worker.util.ArchitectureJsonUtil;
import com.business.discovery.worker.util.EnrichmentCardUtil;
import com.business.discovery.worker.util.EnvVarScanner;
import com.business.discovery.worker.util.FileContractCard;
import com.business.discovery.worker.util.JavaClassRegistry;
import com.business.discovery.worker.util.JavaFileTemplater;
import com.business.discovery.worker.util.JavaImportResolver;
import com.business.discovery.worker.util.JavaImportSanitizer;
import com.business.discovery.worker.util.JavaPackageSanitizer;
import com.business.discovery.worker.util.LayerOrderUtil;
import com.business.discovery.worker.util.TruncationDetector;
import com.business.discovery.worker.service.GitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Order(7)
@Slf4j
public class BackendGeneratorNode implements WorkerNode {

    // Max concurrent Flash LLM calls per layer — limits Gemini API rate pressure.
    private static final int MAX_PARALLEL_PER_LAYER = 5;

    // Same-attempt regeneration budget for a file that comes back truncated (mid-token).
    private static final int MAX_GEN_ATTEMPTS = 3;

    // Matches any Java class name (UpperCamelCase) in free text — used to auto-resolve
    // class references in featureInstruction/fileRole to actual workspace files.
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile("\\b([A-Z][A-Za-z0-9]+)\\b");

    private final LlmGeneratorService flashLlm;

    /** Fenced foundation contract (User/Role/PaymentService/exceptions), loaded once per run; "" when absent. */
    private volatile String foundationContractSection = "";
    private volatile String backendContractSection = "";   // reconciled backend interfaces (ground truth)

    /** docs/ENRICHMENT.json feature cards, keyed by featureName; loaded once per run, empty when absent. */
    private volatile Map<String, FeatureCard> enrichmentCards = Map.of();

    /** All FileSpecs by path; set once per run — used to stamp a dependency's reconciled interface onto its body. */
    private volatile Map<String, FileSpec> allSpecsByPath = Map.of();
    private final GeneratedFileRepository fileRepo;
    private final GitService gitService;

    // Guards concurrent writes to ARCHITECTURE.json from parallel Flash LLM threads within a layer.
    private final ReentrantLock archJsonLock = new ReentrantLock();

    public BackendGeneratorNode(@Qualifier("geminiFlash") LlmGeneratorService flashLlm,
                                GeneratedFileRepository fileRepo,
                                GitService gitService) {
        this.flashLlm = flashLlm;
        this.fileRepo = fileRepo;
        this.gitService = gitService;
    }

    @Override
    public void execute(WorkerContext ctx) {
        Path workspace = ctx.getWorkspaceDir();
        Path backendDir = workspace.resolve("backend");

        ArchitectureSpec architectureSpec = loadSpec(workspace);
        Map<String, FileSpec> specByPath = buildSpecByPath(architectureSpec);
        this.allSpecsByPath = specByPath;   // for stamping reconciled interfaces onto dependency bodies
        Map<String, FeatureSpec> featuresByName = buildFeaturesByName(architectureSpec);
        Map<String, String> standaloneClassImports = buildStandaloneClassImports(architectureSpec);
        JavaClassRegistry classRegistry = JavaClassRegistry.buildFromSpec(architectureSpec);
        // Merge files already on disk (from previous attempts, exception classes not in spec, etc.)
        // so JavaImportResolver never strips their imports as "ghost imports"
        classRegistry.mergeFromFilesystem(backendDir.resolve("src/main/java"));

        // Fenced foundation contract (User/Role/PaymentService/exceptions) — ground truth for the
        // immutable Java spine, appended to the (cached) system prompt of every backend generation call.
        this.foundationContractSection =
                com.business.discovery.worker.util.FoundationContractCard.backendSection(workspace);
        if (foundationContractSection.isEmpty()) {
            log.warn("[BackendGeneratorNode] No backend/FOUNDATION_CONTRACT.md in workspace — "
                    + "generating without the fenced foundation contract");
        } else {
            log.info("[BackendGeneratorNode] Loaded fenced foundation contract ({} chars)",
                    foundationContractSection.length());
        }

        // Reconciled backend contracts (from ContractReconciler @ planning) — the whole feature's
        // class interfaces as one dedicated ground-truth section, so a consumer (service→repo→DTO)
        // binds to exactly what its producer exposes. Backend twin of PlannedComponentPropsCard;
        // appended to the cached system prompt alongside the foundation contract.
        var backendCard =
                com.business.discovery.worker.util.BackendContractCard.build(architectureSpec.getFiles());
        this.backendContractSection = backendCard.toPromptSection();
        log.info("[BackendGeneratorNode] Backend contract card: {} class interface(s)", backendCard.classCount());

        // Per-feature context cards (docs/ENRICHMENT.json) — each file's generation prompt gets its
        // whole feature's identity + sibling files/roles, keyed by FileSpec.featureName. Empty when
        // ENRICHMENT.json is absent; the effective instruction still flows via buildFeatureContext.
        try {
            this.enrichmentCards = EnrichmentCardUtil.read(workspace);
            log.info("[BackendGeneratorNode] Loaded {} enrichment feature card(s)", enrichmentCards.size());
        } catch (IOException e) {
            log.warn("[BackendGeneratorNode] Could not read ENRICHMENT.json — generating without feature "
                    + "cards: {}", e.getMessage());
            this.enrichmentCards = Map.of();
        }

        preInjectDependencies(backendDir.resolve("pom.xml"), architectureSpec);

        List<FileEntry> backendFiles = ctx.getFileManifest().stream()
                .filter(e -> e.type() == FileType.BACKEND)
                .sorted(Comparator.comparingInt(LayerOrderUtil::backendPriority))
                .toList();

        boolean requestedChangesMode = ctx.getBriefCtx().requestedChanges() != null
                && !ctx.getBriefCtx().requestedChanges().isBlank();

        long layerCount = backendFiles.stream().map(LayerOrderUtil::backendPriority).distinct().count();
        log.info("[BackendGeneratorNode] Processing {} backend files across {} layers", backendFiles.size(), layerCount);

        // Group files by layer priority — within a layer, files don't depend on each other
        // and can be generated concurrently. TreeMap gives sorted layer iteration.
        TreeMap<Integer, List<FileEntry>> filesByLayer = backendFiles.stream()
                .collect(Collectors.groupingBy(LayerOrderUtil::backendPriority,
                        TreeMap::new, Collectors.toList()));

        int filesProcessed = 0;

        for (Map.Entry<Integer, List<FileEntry>> layerEntry : filesByLayer.entrySet()) {
            List<FileEntry> layerFiles = layerEntry.getValue();
            String layerLabel = layerFiles.isEmpty() ? "?" : layerName(layerFiles.get(0));

            List<FileEntry> toGenerate = layerFiles.stream()
                    .filter(e -> {
                        FileSpec spec = specByPath.get(e.path());
                        FeatureSpec feature = spec != null && spec.getFeatureName() != null
                                ? featuresByName.get(spec.getFeatureName()) : null;
                        boolean exists = Files.exists(workspace.resolve(e.path()));
                        return !shouldSkip(spec, feature, requestedChangesMode, exists);
                    })
                    .toList();

            filesProcessed += layerFiles.size();

            if (toGenerate.isEmpty()) {
                log.info("[BackendGeneratorNode] Layer [{}] — all {} file(s) already done, skipping",
                        layerLabel, layerFiles.size());
                continue;
            }

            int skipped = layerFiles.size() - toGenerate.size();
            log.info("[BackendGeneratorNode] Layer [{}] — generating {} file(s) in parallel (skipping {})",
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
                            log.warn("[BackendGeneratorNode] No featureInstruction for '{}' (file: {}) — skipping",
                                    spec != null ? spec.getFeatureName() : "unknown", entry.path());
                            return;
                        }
                        try {
                            runGenerateStage(ctx, workspace, entry, filePath, spec,
                                    featureInstruction, fileRole, requestedChangesMode, feature,
                                    standaloneClassImports, classRegistry);
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
                            "File I/O error during backend generation: " + uioe.getMessage(), uioe);
                }
                throw new WorkerException(FailureType.INFRA,
                        "Parallel generation failed in layer [" + layerLabel + "]: " + e.getMessage(), e);
            } finally {
                executor.shutdownNow();
            }

            // Refresh registry from filesystem so the next layer sees classes just generated
            // (e.g. SERVICE layer can correctly resolve EXCEPTION layer imports)
            classRegistry.mergeFromFilesystem(backendDir.resolve("src/main/java"));

            // Checkpoint after each layer — work survives a container kill between layers
            gitService.commitAndPushCheckpoint(workspace,
                    "chore: backend wip — " + layerLabel + " layer, " + filesProcessed
                            + " files (attempt " + ctx.getAttemptNumber() + ")",
                    ctx.getGithubBranch());
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

    // ── PLANNED → GENERATED ───────────────────────────────────────────────────

    private void runGenerateStage(WorkerContext ctx, Path workspace, FileEntry entry,
                                  Path filePath, FileSpec spec, String featureInstruction,
                                  String fileRole, boolean requestedChangesMode,
                                  FeatureSpec feature, Map<String, String> standaloneClassImports,
                                  JavaClassRegistry classRegistry) throws IOException {
        Files.createDirectories(filePath.getParent());

        int layerPriority = LayerOrderUtil.backendPriority(entry);
        JavaFileTemplater.TemplateType templateType = JavaFileTemplater.classify(spec, layerPriority);

        // Template output is deterministic and never truncates — only the LLM path needs a
        // regeneration budget.
        String content;
        if (templateType != JavaFileTemplater.TemplateType.NONE) {
            content = JavaFileTemplater.generate(spec, classRegistry.getBasePackage(), templateType);
            if (content == null) {
                log.warn("[BackendGeneratorNode] Template returned null for {} — falling back to LLM", entry.path());
                content = generateWithLlmRetrying(workspace, entry, spec, featureInstruction, fileRole,
                        requestedChangesMode, feature, standaloneClassImports, filePath);
            }
        } else {
            content = generateWithLlmRetrying(workspace, entry, spec, featureInstruction, fileRole,
                    requestedChangesMode, feature, standaloneClassImports, filePath);
        }

        if (content == null) {
            // Every attempt truncated. Do NOT write the partial — an incomplete file masks errors
            // and wastes fix-LLM rounds. Discard it and leave the path absent + GENERATION_FAILED so
            // a later worker attempt regenerates it (shouldSkip does not skip GENERATION_FAILED).
            // deleteIfExists also clears a partial left behind by an earlier run of the old code.
            log.warn("[BackendGeneratorNode] {} still truncated after {} attempts — GENERATION_FAILED, "
                    + "partial discarded (not written)", entry.path(), MAX_GEN_ATTEMPTS);
            Files.deleteIfExists(filePath);
            archJsonLock.lock();
            try {
                ArchitectureJsonUtil.updateFileStatus(workspace, entry.path(), "GENERATION_FAILED");
            } finally {
                archJsonLock.unlock();
            }
            upsertRecord(ctx, entry.path(), GeneratedFile.FileType.BACKEND, GeneratedFile.FileStatus.GENERATION_FAILED);
            return;
        }

        content = stripNestedEnums(content, standaloneClassImports, entry.path());
        content = fixSpringBoot4Imports(content);

        Files.writeString(filePath, content);
        JavaPackageSanitizer.sanitize(filePath);
        JavaImportSanitizer.sanitize(filePath);
        // Deterministic import resolution — fixes wrong package prefixes and adds missing project imports
        JavaImportResolver.resolve(filePath, classRegistry);

        archJsonLock.lock();
        try {
            ArchitectureJsonUtil.updateFileStatus(workspace, entry.path(), "GENERATED");
        } finally {
            archJsonLock.unlock();
        }
        upsertRecord(ctx, entry.path(), GeneratedFile.FileType.BACKEND, GeneratedFile.FileStatus.GENERATED);
    }

    /**
     * Generates via LLM, regenerating in place when the output comes back cut mid-token.
     * Returns null when every attempt truncated — the caller must then discard, never write.
     * The old path wrote the partial and deferred regeneration to the NEXT worker attempt, so the
     * broken file was committed, masked every other error from the validator, and burned a fix
     * session first. The root cause is also addressed upstream by disabling Flash thinking
     * (GeminiLlmGeneratorService), so a retry here almost always succeeds.
     */
    private String generateWithLlmRetrying(Path workspace, FileEntry entry, FileSpec spec,
                                           String featureInstruction, String fileRole,
                                           boolean requestedChangesMode, FeatureSpec feature,
                                           Map<String, String> standaloneClassImports, Path filePath) {
        String existingContent = existingContent(filePath, requestedChangesMode, feature);
        for (int genAttempt = 1; genAttempt <= MAX_GEN_ATTEMPTS; genAttempt++) {
            String candidate = generateWithLlm(workspace, entry, spec, featureInstruction, fileRole,
                    requestedChangesMode, feature, standaloneClassImports, existingContent);
            if (!TruncationDetector.looksTruncated(candidate)) return candidate;
            log.warn("[BackendGeneratorNode] Truncated output for {} (attempt {}/{}) — regenerating",
                    entry.path(), genAttempt, MAX_GEN_ATTEMPTS);
        }
        return null;
    }

    private String generateWithLlm(Path workspace, FileEntry entry, FileSpec spec,
                                    String featureInstruction, String fileRole,
                                    boolean requestedChangesMode, FeatureSpec feature,
                                    Map<String, String> standaloneClassImports, String existingContent) {
        Map<String, String> depFiles = loadDependencyFiles(workspace, spec,
                featureInstruction, fileRole, standaloneClassImports);
        // Whole-feature context: identity + sibling map (from the card) + the effective instruction.
        FeatureCard card = spec != null && spec.getFeatureName() != null
                ? enrichmentCards.get(spec.getFeatureName()) : null;
        String featureContext = FeatureCard.buildFeatureContext(card, entry.path(), featureInstruction);
        // This file's OWN exact contract (purpose + fields + method sigs + endpoints) appended to the role.
        String fileContract = FileContractCard.render(spec, fileRole);
        return flashLlm.generateFileContent(entry.path(),
                fileContract, depFiles, existingContent,
                sharedContext(), featureContext);
    }

    /**
     * The run-constant ground truth appended to the cached system prompt: the fenced foundation
     * contract + the reconciled backend contract card. Both are byte-identical across the run's
     * generation calls, so they extend the shared prefix instead of being re-billed per call.
     */
    private String sharedContext() {
        if (backendContractSection.isEmpty()) return foundationContractSection;
        if (foundationContractSection.isEmpty()) return backendContractSection;
        return foundationContractSection + "\n\n" + backendContractSection;
    }

    private String existingContent(Path filePath, boolean requestedChangesMode, FeatureSpec feature) {
        return (requestedChangesMode && feature != null && feature.isChangeRequired())
                ? readIfExists(filePath) : null;
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
        // GENERATION_FAILED must NOT be skipped — re-generate on retry runs.
        // GENERATED + present on disk IS skipped: after generation the fix loop owns the
        // file. Regenerating it on retry destroyed the previous attempt's ErrorFixAgent
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
                    try { deps.put(depPath, stampReconciledInterface(depPath, Files.readString(file))); }
                    catch (IOException ignored) {}
                }
            }
        }

        // 2. Auto-resolve class names mentioned in featureInstruction / fileRole to workspace files.
        // Prevents the LLM from guessing field names on types it has never seen.
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
                    try { deps.put(relPath, stampReconciledInterface(relPath, Files.readString(file))); }
                    catch (IOException ignored) {}
                }
            }
        }

        return deps;
    }

    /**
     * Prepends a dependency's RECONCILED interface (when it has one) to its body, marked authoritative,
     * so a stale/divergent body can't silently override the reconciled contract. Bodies are kept intact
     * (field-level ground truth preserved) — only annotated. No-op when the dep isn't reconciled.
     */
    private String stampReconciledInterface(String depPath, String body) {
        FileSpec depSpec = allSpecsByPath.get(depPath);
        if (depSpec == null || !Boolean.TRUE.equals(depSpec.getContractReconciled())) return body;
        String iface = FileContractCard.renderInterfaceOnly(depSpec);
        if (iface.isBlank()) return body;
        return "// RECONCILED INTERFACE (authoritative — bind to THIS; body below is reference only, may lag):\n"
                + "// " + iface + "\n" + body;
    }

    // ── Dependency injection (keyword-based, runs once before generation) ─────

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

    private void preInjectDependencies(Path pomPath, ArchitectureSpec spec) {
        if (!Files.exists(pomPath)) return;
        if (spec.getFiles() == null) return;
        StringBuilder text = new StringBuilder();
        for (FileSpec f : spec.getFiles()) {
            if (f.getDescription() != null) text.append(f.getDescription().toLowerCase()).append(' ');
            if (f.getFileRole() != null) text.append(f.getFileRole().toLowerCase()).append(' ');
        }
        if (spec.getFeatures() != null) {
            for (var feat : spec.getFeatures()) {
                if (feat.getFeatureInstruction() != null)
                    text.append(feat.getFeatureInstruction().toLowerCase()).append(' ');
            }
        }
        String specText = text.toString();

        Set<String> neededPackagePrefixes = new LinkedHashSet<>();
        for (Map.Entry<String, String> kwEntry : KEYWORD_TO_PACKAGE.entrySet()) {
            if (specText.contains(kwEntry.getKey())) {
                neededPackagePrefixes.add(kwEntry.getValue());
            }
        }

        if (neededPackagePrefixes.isEmpty()) return;
        log.info("[BackendGeneratorNode] Pre-injecting deps for detected keywords: {}", neededPackagePrefixes);
        injectPackageDeps(pomPath, neededPackagePrefixes);

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

    // ── Utilities ─────────────────────────────────────────────────────────────

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
     * on SB2/3 code consistently generate the old imports.
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

    // ── Nested type safety net (enums + records) ─────────────────────────────

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
