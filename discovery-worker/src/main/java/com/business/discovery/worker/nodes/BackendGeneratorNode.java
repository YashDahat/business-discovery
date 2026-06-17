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

    private final LlmGeneratorService flashLlm;
    private final LlmGeneratorService proLlm;
    private final LlmGeneratorService fixLlm;
    private final BuildToolService buildToolService;
    private final GeneratedFileRepository fileRepo;

    public BackendGeneratorNode(@Qualifier("geminiFlash") LlmGeneratorService flashLlm,
                                @Qualifier("geminiPro") LlmGeneratorService proLlm,
                                @Qualifier("geminiFlash") LlmGeneratorService fixLlm,
                                BuildToolService buildToolService,
                                GeneratedFileRepository fileRepo) {
        this.flashLlm = flashLlm;
        this.proLlm = proLlm;
        this.fixLlm = fixLlm;
        this.buildToolService = buildToolService;
        this.fileRepo = fileRepo;
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

        List<FileEntry> backendFiles = ctx.getFileManifest().stream()
                .filter(e -> e.type() == FileType.BACKEND)
                .sorted(Comparator.comparingInt(LayerOrderUtil::backendPriority))
                .toList();

        boolean requestedChangesMode = ctx.getBriefCtx().requestedChanges() != null
                && !ctx.getBriefCtx().requestedChanges().isBlank();

        log.info("[BackendGeneratorNode] Processing {} backend files in layer order", backendFiles.size());

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

                // Stage 3: PLANNED → GENERATED (also forced in requestedChangesMode for changeRequired features)
                boolean needsGeneration = "PLANNED".equalsIgnoreCase(status)
                        || (requestedChangesMode && feature != null && feature.isChangeRequired());
                if (needsGeneration) {
                    log.info("[BackendGeneratorNode] Stage 3 — generating [{}]: {}", layerName(entry), entry.path());
                    status = runGenerateStage(ctx, workspace, entry, filePath, spec,
                            featureInstruction, fileRole, requestedChangesMode, feature,
                            standaloneClassImports);
                }

                // Stage 1: GENERATED → VALIDATED
                if ("GENERATED".equalsIgnoreCase(status)) {
                    log.info("[BackendGeneratorNode] Stage 1 — compiling [{}]: {}", layerName(entry), entry.path());
                    status = runCompileStage(ctx, workspace, backendDir, entry, filePath, spec, specByPath);
                }

                // Stage 2: VALIDATED → SPEC_COMPLIANT
                if ("VALIDATED".equalsIgnoreCase(status)) {
                    log.info("[BackendGeneratorNode] Stage 2 — spec check [{}]: {}", layerName(entry), entry.path());
                    runSpecCheckStage(ctx, workspace, backendDir, entry, filePath, spec,
                            featureInstruction, fileRole, specByPath, standaloneClassImports);
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

        log.info("[BackendGeneratorNode] Done — {} backend files processed", backendFiles.size());
    }

    // ── Stage 3: PLANNED → GENERATED ─────────────────────────────────────────

    private String runGenerateStage(WorkerContext ctx, Path workspace, FileEntry entry,
                                    Path filePath, FileSpec spec, String featureInstruction,
                                    String fileRole, boolean requestedChangesMode,
                                    FeatureSpec feature, Map<String, String> standaloneClassImports) throws IOException {
        Map<String, String> depFiles = loadDependencyFiles(workspace, spec,
                featureInstruction, fileRole, standaloneClassImports);
        String existingContent = (requestedChangesMode && feature != null && feature.isChangeRequired())
                ? readIfExists(filePath) : null;

        String content = flashLlm.generateFileContent(entry.path(), featureInstruction,
                fileRole != null ? fileRole : "", depFiles, existingContent);
        content = stripNestedEnums(content, standaloneClassImports, entry.path());
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content);

        ArchitectureJsonUtil.updateFileStatus(workspace, entry.path(), "GENERATED");
        upsertRecord(ctx, entry.path(), GeneratedFile.FileType.BACKEND, GeneratedFile.FileStatus.GENERATED);
        return "GENERATED";
    }

    // ── Stage 1: GENERATED → VALIDATED ───────────────────────────────────────

    private String runCompileStage(WorkerContext ctx, Path workspace, Path backendDir,
                                   FileEntry entry, Path filePath,
                                   FileSpec spec, Map<String, FileSpec> specByPath) throws IOException {
        WorkspaceReader reader = new WorkspaceReader(workspace);
        for (int attempt = 0; attempt <= MAX_COMPILE_FIX_ATTEMPTS; attempt++) {
            BuildToolService.BuildResult result = buildToolService.runMvnCompile(backendDir);
            if (result.success()) {
                ArchitectureJsonUtil.updateFileStatus(workspace, entry.path(), "VALIDATED");
                upsertRecord(ctx, entry.path(), GeneratedFile.FileType.BACKEND, GeneratedFile.FileStatus.VALIDATED);
                log.info("[BackendGeneratorNode] Compiled OK: {}", entry.path());
                return "VALIDATED";
            }

            if (attempt < MAX_COMPILE_FIX_ATTEMPTS) {
                // Parse which files are actually failing — may not be the file we just wrote
                List<String> toFix = parseFailingFiles(result.output(), workspace);
                if (toFix.isEmpty()) toFix = List.of(entry.path());

                log.warn("[BackendGeneratorNode] Compile failed (attempt {}/{}) — fixing {} file(s): {}",
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
                    Files.writeString(workspace.resolve(failingPath), fixed);
                }
            }
        }

        log.error("[BackendGeneratorNode] Compile still failing for {} after {} attempts — GENERATION_FAILED",
                entry.path(), MAX_COMPILE_FIX_ATTEMPTS);
        ArchitectureJsonUtil.updateFileStatus(workspace, entry.path(), "GENERATION_FAILED");
        upsertRecord(ctx, entry.path(), GeneratedFile.FileType.BACKEND, GeneratedFile.FileStatus.GENERATION_FAILED);
        return "GENERATION_FAILED";
    }

    // ── Stage 2: VALIDATED → SPEC_COMPLIANT ──────────────────────────────────

    private void runSpecCheckStage(WorkerContext ctx, Path workspace, Path backendDir,
                                   FileEntry entry, Path filePath, FileSpec spec,
                                   String featureInstruction, String fileRole,
                                   Map<String, FileSpec> specByPath,
                                   Map<String, String> standaloneClassImports) throws IOException {
        // Combine fileRole + featureInstruction so the compliance check has full per-file context
        String combinedSpec = "FILE ROLE: " + (fileRole != null ? fileRole : "")
                + "\n\nFEATURE INSTRUCTION:\n" + featureInstruction;

        for (int round = 0; round < MAX_SPEC_FIX_ROUNDS; round++) {
            String fileContent = Files.readString(filePath);
            ComplianceResult compliance = proLlm.checkSpecCompliance(entry.path(), fileContent, combinedSpec);

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
            String newStatus = runCompileStage(ctx, workspace, backendDir, entry, filePath, spec, specByPath);
            if (!"VALIDATED".equalsIgnoreCase(newStatus)) return; // GENERATION_FAILED — stop
        }
    }

    // ── shouldSkip ────────────────────────────────────────────────────────────

    private boolean shouldSkip(FileSpec spec, FeatureSpec feature, boolean requestedChangesMode) {
        if (spec == null) return false;
        if (requestedChangesMode) return feature == null || !feature.isChangeRequired();
        String status = spec.getStatus();
        return "SPEC_COMPLIANT".equalsIgnoreCase(status)
                || "GENERATION_FAILED".equalsIgnoreCase(status);
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

    private String layerName(FileEntry entry) {
        return LayerOrderUtil.backendLayerName(entry);
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
