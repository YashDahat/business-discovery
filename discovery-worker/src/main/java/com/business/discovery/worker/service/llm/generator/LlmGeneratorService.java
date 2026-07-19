package com.business.discovery.worker.service.llm.generator;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.service.llm.ApiEndpoint;
import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.BriefContext;
import com.business.discovery.worker.service.llm.ComplianceResult;
import com.business.discovery.worker.service.llm.FeatureSpec;
import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.util.ChangeTargetingUtil;
import com.business.discovery.worker.util.LlmResponseParser;
import com.business.discovery.worker.util.PromptLoader;
import com.business.discovery.worker.util.PromptTemplate;
import com.business.discovery.worker.util.WorkspaceReader;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public abstract class LlmGeneratorService {

    protected static final int MAX_TOKENS = 8192;

    /** Shared SNAKE_CASE mapper for all spec serialization/deserialization. */
    protected static final ObjectMapper SPEC_MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Pro call 1: generates the architecture OUTLINE — the complete file manifest with
     * feature grouping, one-line descriptions, and imports_from/depends_on wiring, but
     * WITHOUT per-file heavy detail (public_functions, api_endpoints, file_role). The
     * per-feature enrichment pass fills those in afterwards.
     *
     * Kept deliberately small (~15-25K chars) so it can never hit the output-token
     * ceiling, and retried in-process because the single-shot call was observed to
     * intermittently degenerate to an empty "files": [] stub — a 20-second retry here
     * beats burning a ~5-minute container attempt.
     */
    public ArchitectureSpec generateArchitectureSpec(BriefContext brief, String slug) {
        boolean isUpdate = brief.requestedChanges() != null && !brief.requestedChanges().isBlank();

        String system = PromptLoader.load("system/arch_outline.txt");
        String user   = buildArchSpecUserPrompt(brief, slug, isUpdate);

        Exception lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            String json = stripMarkdown(callLlm(system, user));
            try {
                ArchitectureSpec spec = SPEC_MAPPER.readValue(json, ArchitectureSpec.class);
                int fileCount    = spec.getFiles()    == null ? 0 : spec.getFiles().size();
                int featureCount = spec.getFeatures() == null ? 0 : spec.getFeatures().size();
                if (fileCount == 0 || featureCount == 0) {
                    log.warn("[generateArchitectureSpec] Attempt {}/3 returned empty outline "
                            + "(files={} features={} responseChars={}) — retrying",
                            attempt, fileCount, featureCount, json == null ? 0 : json.length());
                    lastError = new WorkerException(FailureType.CODE,
                            "Architecture outline had " + fileCount + " files / " + featureCount + " features");
                    continue;
                }
                log.info("[generateArchitectureSpec] Outline parsed on attempt {}/3: files={} features={}",
                        attempt, fileCount, featureCount);
                return spec;
            } catch (WorkerException e) {
                throw e;
            } catch (Exception e) {
                log.warn("[generateArchitectureSpec] Attempt {}/3 parse failed (responseChars={}): {}",
                        attempt, json == null ? 0 : json.length(), e.getMessage());
                lastError = e;
            }
        }
        throw new WorkerException(FailureType.CODE,
                "Architecture outline empty/unparseable after 3 attempts: "
                + (lastError != null ? lastError.getMessage() : "unknown"), lastError);
    }

    /**
     * Pro call 2 (feature-level enrichment): produces a featureInstruction covering all
     * files in the feature holistically. One call per feature (~5-8K tokens vs old 57K).
     * Uses the read_file tool loop so the model can inspect workspace files.
     *
     * @param feature          the feature to enrich (featureInstruction is null on first call)
     * @param featureFiles     only the FileSpec entries belonging to this feature (4-10 entries)
     * @param peerApiSummaries other features' names + publicFunctions + apiEndpoints only
     *                         (~100 tokens per peer — not full specs)
     * @param brief            business and design context
     * @param workspace        WorkspaceReader for read_file tool calls during enrichment
     * @return the updated FeatureSpec with featureInstruction set
     */
    public FeatureSpec enrichFeature(FeatureSpec feature,
                                     List<FileSpec> featureFiles,
                                     Map<String, Object> peerApiSummaries,
                                     BriefContext brief,
                                     WorkspaceReader workspace) {
        return enrichFeature(feature, featureFiles, peerApiSummaries, brief, workspace, Set.of());
    }

    /**
     * @param dependentFeatures slugs of features already declaring a dependency on this one —
     *                          this feature must not wire back into them or the generated app dies
     *                          at boot on a Spring bean cycle. Empty for the first feature enriched.
     */
    public FeatureSpec enrichFeature(FeatureSpec feature,
                                     List<FileSpec> featureFiles,
                                     Map<String, Object> peerApiSummaries,
                                     BriefContext brief,
                                     WorkspaceReader workspace,
                                     Set<String> dependentFeatures) {
        String requestedChangesSection = (brief.requestedChanges() != null
                && !brief.requestedChanges().isBlank())
                ? "\n== REQUESTED CHANGES ==\n" + brief.requestedChanges() + "\n"
                : "";

        String featureFilesJson;
        String peerSummaryJson;
        try {
            featureFilesJson = SPEC_MAPPER.writeValueAsString(featureFiles);
            peerSummaryJson  = SPEC_MAPPER.writeValueAsString(peerApiSummaries);
        } catch (Exception e) {
            throw new WorkerException(FailureType.CODE,
                    "Failed to serialize enrichFeature input for "
                    + feature.getFeatureName() + ": " + e.getMessage(), e);
        }

        String system = PromptLoader.load("system/feature_enrichment.txt");
        String user = PromptTemplate.from(PromptLoader.load("user/feature_enrichment.txt"))
                .with("featureName",             feature.getFeatureName())
                .with("featureDisplayName",      feature.getFeatureDisplayName())
                .with("featureType",             feature.getFeatureType())
                .with("featureFiles",            featureFilesJson)
                .with("peerApiSummaries",        peerSummaryJson)
                .with("businessName",            brief.businessName())
                .with("category",                brief.category())
                .with("address",                 brief.address()         != null ? brief.address()         : "")
                .with("phone",                   brief.phone()           != null ? brief.phone()           : "")
                .with("latitude",                brief.latitude()        != null ? brief.latitude()        : "")
                .with("longitude",               brief.longitude()       != null ? brief.longitude()       : "")
                .with("openHours",               brief.openHours()       != null ? brief.openHours()       : "")
                .with("techStack",               brief.techStack().toString())
                .with("designDirection",         brief.designDirection() != null ? brief.designDirection() : "")
                .with("colorScheme",             brief.colorScheme()     != null ? brief.colorScheme()     : "")
                .with("tone",                    brief.tone()            != null ? brief.tone()            : "")
                .with("requestedChangesSection", requestedChangesSection)
                .with("dependencyDirectionSection", buildDependencyDirectionSection(dependentFeatures))
                .render();

        Exception lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String raw = callLlmWithTools(system, user, workspace);
                JsonNode result = LlmResponseParser.parseJsonObject(raw);
                String instruction = LlmResponseParser.getInstruction(result,
                        "feature_instruction", "featureInstruction");

                if (instruction == null) {
                    log.warn("[enrichFeature] Blank/short feature_instruction for '{}' (attempt {}/2)",
                            feature.getFeatureName(), attempt);
                    lastError = new WorkerException(FailureType.CODE,
                            "enrichFeature returned blank feature_instruction for: " + feature.getFeatureName());
                    continue;
                }

                feature.setFeatureInstruction(instruction);
                if (!requestedChangesSection.isBlank() && result.has("change_required")) {
                    feature.setChangeRequired(result.path("change_required").asBoolean(true));
                }
                feature.setDependsOnFeatures(parseDependsOnFeatures(result, feature, dependentFeatures));
                mergeFileDetail(feature, featureFiles, result);
                return feature;

            } catch (WorkerException e) {
                lastError = e;
                log.warn("[enrichFeature] Attempt {}/2 failed for '{}': {}",
                        attempt, feature.getFeatureName(), e.getMessage());
            } catch (LlmResponseParser.LlmParseException e) {
                lastError = new WorkerException(FailureType.CODE,
                        "enrichFeature parse failed for " + feature.getFeatureName() + ": " + e.getMessage(), e);
                log.warn("[enrichFeature] Attempt {}/2 parse error for '{}': {}",
                        attempt, feature.getFeatureName(), e.getMessage());
            }
        }
        throw (lastError instanceof WorkerException we)
                ? we
                : new WorkerException(FailureType.CODE,
                        "enrichFeature failed after 2 attempts for: " + feature.getFeatureName(), lastError);
    }

    /**
     * Hard constraint injected when earlier-enriched features already depend on this one. Without it
     * the model behaves correctly and still produces a cycle: those features are published in the
     * peer API contracts with real signatures, and rule 5 instructs it to bind to exactly those.
     * Nothing else in the prompt conveys that the edge is one-way.
     */
    private static String buildDependencyDirectionSection(Set<String> dependentFeatures) {
        if (dependentFeatures == null || dependentFeatures.isEmpty()) return "";
        return """

                == DEPENDENCY DIRECTION (HARD CONSTRAINT — overrides the peer contracts above) ==
                These features already declared a dependency on THIS feature: %s

                This feature MUST NOT inject, autowire, or call any @Service/@Component class owned by
                them. Doing so forms a Spring bean cycle and the generated app dies at startup with
                "The dependencies of some of the beans in the application context form a cycle".
                Their entries in the peer API contracts above are REFERENCE ONLY — do not wire to them,
                and do not list any of them in depends_on_features.

                When this feature's logic must cause an update inside one of them, use ONE of:
                  (a) CONTROLLER ORCHESTRATION (preferred) — this feature's service RETURNS the outcome,
                      and the controller owning the HTTP entry point injects both services and calls
                      them in sequence.
                  (b) SPRING EVENT — publish via ApplicationEventPublisher; the dependent feature
                      consumes it with @EventListener. Use only when no shared controller exists.

                Never resolve this with @Lazy, field @Autowired, or setter injection.
                """.formatted(String.join(", ", dependentFeatures));
    }

    /**
     * Reads the declared cross-feature edges. Declarations that violate the injected constraint are
     * kept rather than stripped — dropping the edge here would hide the cycle from the
     * post-enrichment gate while the cyclic wiring stayed in the instruction prose.
     */
    private static List<String> parseDependsOnFeatures(JsonNode result,
                                                       FeatureSpec feature,
                                                       Set<String> dependentFeatures) {
        JsonNode declared = result.path("depends_on_features");
        if (!declared.isArray()) {
            log.warn("[enrichFeature] No depends_on_features array for '{}' — treating as no cross-feature edges",
                    feature.getFeatureName());
            return List.of();
        }

        List<String> deps = new ArrayList<>();
        for (JsonNode entry : declared) {
            String value = entry.asText(null);
            if (value == null || value.isBlank()) continue;
            String trimmed = value.trim();
            if (!deps.contains(trimmed)) deps.add(trimmed);
        }

        List<String> violations = deps.stream().filter(dependentFeatures::contains).toList();
        if (!violations.isEmpty()) {
            log.warn("[enrichFeature] '{}' declared a back-dependency on {} despite the direction constraint"
                     + " — the cycle gate will reject this spec", feature.getFeatureName(), violations);
        }
        return deps;
    }

    /**
     * Update-run change targeting: ONE call over the whole manifest that resolves the client's
     * natural-language change request to (a) change_required per feature, (b) change_required per
     * file, and (c) a change_instruction per changed feature. This is the update path's ONLY entry
     * point for the request text — enrichment does not re-run on update runs (its per-feature
     * resume guard skips every already-enriched feature), so without this pass the request never
     * reaches the generation prompts and every feature keeps its stale change_required mark.
     *
     * <p>Fallback on failure: marks ALL non-INFRA features changed (full minimal-diff
     * regeneration — the de-facto pre-targeting behavior). Expensive but never drops a client's
     * requested change silently.
     */
    public void targetChanges(ArchitectureSpec spec, BriefContext brief) {
        String manifestJson;
        try {
            manifestJson = SPEC_MAPPER.writeValueAsString(buildTargetingManifest(spec));
        } catch (Exception e) {
            throw new WorkerException(FailureType.CODE,
                    "Failed to serialize targeting manifest: " + e.getMessage(), e);
        }

        String system = PromptLoader.load("system/change_targeting.txt");
        String user = PromptTemplate.from(PromptLoader.load("user/change_targeting.txt"))
                .with("businessName",     brief.businessName())
                .with("category",         brief.category())
                .with("requestedChanges", brief.requestedChanges())
                .with("manifestJson",     manifestJson)
                .render();

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                JsonNode result = LlmResponseParser.parseJsonObject(callLlm(system, user));
                if (!result.path("features").isArray() || result.path("features").isEmpty()) {
                    log.warn("[targetChanges] Attempt {}/2 returned no features array", attempt);
                    continue;
                }
                int changed = ChangeTargetingUtil.apply(spec, result);
                log.info("[targetChanges] {} feature(s) targeted by requested changes", changed);
                if (changed == 0) {
                    // Legal (request may be a no-op) but suspicious — surface it loudly.
                    log.warn("[targetChanges] Requested changes matched ZERO features — nothing will "
                            + "be regenerated. Request was:\n{}", brief.requestedChanges());
                }
                return;
            } catch (LlmResponseParser.LlmParseException e) {
                log.warn("[targetChanges] Attempt {}/2 parse error: {}", attempt, e.getMessage());
            }
        }
        log.warn("[targetChanges] Targeting failed after 2 attempts — falling back to marking ALL "
                + "features changed (full minimal-diff regeneration)");
        ChangeTargetingUtil.markAllChanged(spec);
    }

    /** Compact manifest for targeting: paths + descriptions + roles only — not full FileSpecs. */
    private static List<Map<String, Object>> buildTargetingManifest(ArchitectureSpec spec) {
        Map<String, List<FileSpec>> filesByFeature = (spec.getFiles() == null ? List.<FileSpec>of() : spec.getFiles())
                .stream()
                .filter(f -> f.getFeatureName() != null)
                .collect(Collectors.groupingBy(FileSpec::getFeatureName));

        List<Map<String, Object>> manifest = new ArrayList<>();
        for (FeatureSpec feature : spec.getFeatures() == null ? List.<FeatureSpec>of() : spec.getFeatures()) {
            if ("INFRA".equalsIgnoreCase(feature.getFeatureType())) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("feature_name", feature.getFeatureName());
            entry.put("feature_type", feature.getFeatureType());
            entry.put("files", filesByFeature.getOrDefault(feature.getFeatureName(), List.of()).stream()
                    .map(f -> {
                        Map<String, Object> fm = new LinkedHashMap<>();
                        fm.put("file_path", f.getFilePath());
                        fm.put("layer", f.getLayer());
                        fm.put("description", f.getDescription());
                        fm.put("file_role", f.getFileRole());
                        return fm;
                    })
                    .toList());
            manifest.add(entry);
        }
        return manifest;
    }

    /**
     * Flash call: writes a file from a feature-level instruction + per-file role.
     * featureInstruction is shared across all files in the feature.
     * fileRole is the structural description of this specific file within the feature.
     * filePath is the relative path of the file being generated (e.g.
     * "backend/src/main/java/com/waydownsouth/dto/Foo.java") — injected into the prompt
     * so the LLM derives the correct package/module path instead of guessing.
     */
    public String generateFileContent(String filePath,
                                      String featureInstruction,
                                      String fileRole,
                                      Map<String, String> dependencyFiles,
                                      String existingContent) {
        return generateFileContent(filePath, featureInstruction, fileRole,
                dependencyFiles, existingContent, null);
    }

    /**
     * As above, plus {@code sharedContext}: run-constant ground truth (the API contract card)
     * appended to the SYSTEM prompt rather than the user prompt.
     *
     * Placement is deliberate. The user prompt opens with filePath, so the per-call prefix
     * diverges on its first line — anything appended there is re-billed on every one of the
     * ~140 generation calls in a run. The system prompt is byte-identical across all of them,
     * so a suffix on it extends the shared prefix and rides Gemini's implicit prefix cache
     * instead. Same tokens delivered, a fraction of the cost.
     */
    public String generateFileContent(String filePath,
                                      String featureInstruction,
                                      String fileRole,
                                      Map<String, String> dependencyFiles,
                                      String existingContent,
                                      String sharedContext) {
        boolean isUpdate = existingContent != null;

        String system = PromptLoader.load(isUpdate ? "system/file_update.txt" : "system/file_generate.txt");
        if (sharedContext != null && !sharedContext.isBlank()) {
            system = system + "\n\n" + sharedContext;
        }

        String existingSection = isUpdate
                ? "\n== EXISTING FILE (apply instruction as minimal diff — preserve what the instruction doesn't touch) ==\n"
                  + existingContent + "\n"
                : "";

        String user = PromptTemplate.from(PromptLoader.load("user/file_content.txt"))
                .with("filePath",          filePath != null ? filePath : "")
                .with("featureInstruction", featureInstruction)
                .with("fileRole",           fileRole != null ? fileRole : "")
                .with("dependencySection",  formatFilesSection(dependencyFiles))
                .with("existingSection",    existingSection)
                .render();

        return stripMarkdown(callLlm(system, user));
    }

    /**
     * Pro call: compares generated file against its spec, returns deviations.
     * Pass "FEATURE ROLE: {fileRole}\n\nFEATURE INSTRUCTION:\n{featureInstruction}" as codingInstruction.
     * Parse failures are treated as compliant to avoid blocking the pipeline.
     */
    public ComplianceResult checkSpecCompliance(String filePath, String fileContent, String codingInstruction) {
        String system = PromptLoader.load("system/spec_compliance.txt");
        String user = PromptTemplate.from(PromptLoader.load("user/spec_compliance.txt"))
                .with("codingInstruction", codingInstruction)
                .with("fileContent", fileContent)
                .render();

        String json = stripMarkdown(callLlm(system, user));
        try {
            return SPEC_MAPPER.readValue(json, ComplianceResult.class);
        } catch (Exception e) {
            log.warn("ComplianceResult parse failed for {} — assuming compliant: {}", filePath, e.getMessage());
            return ComplianceResult.ok();
        }
    }

    /**
     * Claude Sonnet call: fixes a file given a compiler error and codebase context.
     * Used exclusively by ErrorFixNode.
     */
    public String fixFileContent(String filePath,
                                 String currentContent,
                                 String compilerError,
                                 Map<String, String> codebaseContext) {
        String system = PromptLoader.load("system/fix_file.txt");

        String user = PromptTemplate.from(PromptLoader.load("user/fix_file.txt"))
                .with("filePath",        filePath)
                .with("currentContent",  currentContent)
                .with("compilerError",   compilerError)
                .with("codebaseSection", formatFilesSection(codebaseContext))
                .render();

        return stripMarkdown(callLlm(system, user));
    }

    /**
     * Merges the per-file structural detail from an enrichment response into the outline's
     * FileSpec entries (matched by file_path). The outline OWNS the file list: entries for
     * unknown paths are dropped with a warning, never added. A response without a "files"
     * array degrades gracefully to instruction-only enrichment (pre-batching behavior).
     */
    private void mergeFileDetail(FeatureSpec feature, List<FileSpec> featureFiles, JsonNode result) {
        JsonNode filesNode = result.path("files");
        if (!filesNode.isArray() || filesNode.isEmpty()) {
            log.info("[enrichFeature] No per-file detail in response for '{}' — instruction-only enrichment",
                    feature.getFeatureName());
            return;
        }

        Map<String, FileSpec> byPath = featureFiles.stream()
                .filter(f -> f.getFilePath() != null)
                .collect(Collectors.toMap(FileSpec::getFilePath, f -> f, (a, b) -> a));

        int merged = 0;
        for (JsonNode entry : filesNode) {
            String path = entry.path("file_path").asText(null);
            FileSpec target = path == null ? null : byPath.get(path);
            if (target == null) {
                log.warn("[enrichFeature] Detail for unknown file '{}' in feature '{}' — dropped "
                        + "(the outline owns the file list)", path, feature.getFeatureName());
                continue;
            }
            try {
                FileSpec detail = SPEC_MAPPER.treeToValue(entry, FileSpec.class);
                if (detail.getFileRole() != null && !detail.getFileRole().isBlank()) {
                    target.setFileRole(detail.getFileRole());
                }
                if (detail.getDescription() != null && !detail.getDescription().isBlank()) {
                    target.setDescription(detail.getDescription());
                }
                if (detail.getPublicFunctions() != null)      target.setPublicFunctions(detail.getPublicFunctions());
                if (detail.getPublicVariables() != null)      target.setPublicVariables(detail.getPublicVariables());
                if (detail.getApiEndpoints() != null)         target.setApiEndpoints(detail.getApiEndpoints());
                if (detail.getApiEndpointsConsumed() != null) target.setApiEndpointsConsumed(detail.getApiEndpointsConsumed());
                merged++;
            } catch (Exception e) {
                log.warn("[enrichFeature] Could not parse detail for '{}' in feature '{}': {}",
                        path, feature.getFeatureName(), e.getMessage());
            }
        }
        log.info("[enrichFeature] Merged structural detail for {}/{} files in feature '{}'",
                merged, featureFiles.size(), feature.getFeatureName());
    }

    /**
     * Builds a lightweight API summary of a feature for use as peer context in enrichFeature.
     * Keeps only featureName + featureType + publicFunctions + apiEndpoints (~100 tokens per feature).
     */
    public static Map<String, Object> buildApiSummary(FeatureSpec feature, List<FileSpec> featureFiles) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("feature_name", feature.getFeatureName());
        summary.put("feature_type", feature.getFeatureType());

        List<Map<String, Object>> functions = new ArrayList<>();
        List<Map<String, Object>> endpoints = new ArrayList<>();
        Map<String, String> fieldShapes = new LinkedHashMap<>();

        for (FileSpec f : featureFiles) {
            if (f.getPublicFunctions() != null) {
                for (var fn : f.getPublicFunctions()) {
                    Map<String, Object> fnMap = new LinkedHashMap<>();
                    fnMap.put("file", f.getFileName());
                    fnMap.put("name", fn.getName());
                    fnMap.put("parameters", fn.getParameters());
                    fnMap.put("return_type", fn.getReturnType());
                    functions.add(fnMap);
                }
            }
            // Field shapes of DTOs/entities — the wire contract. Omitting these caused
            // frontend enrichment to INVENT field names (durationMonths vs the backend's
            // durationInMonths) because peers only ever saw functions and endpoints.
            if (f.getPublicVariables() != null && !f.getPublicVariables().isEmpty()
                    && ("DTO".equalsIgnoreCase(f.getLayer()) || "MODEL".equalsIgnoreCase(f.getLayer()))) {
                String typeName = f.getFileName() == null ? "?"
                        : f.getFileName().replaceAll("\\.(java|ts)$", "");
                String fields = f.getPublicVariables().stream()
                        .map(v -> v.getName() + ": " + v.getType())
                        .collect(Collectors.joining(", "));
                fieldShapes.put(typeName, fields);
            }
            if (f.getApiEndpoints() != null) {
                for (ApiEndpoint ep : f.getApiEndpoints()) {
                    Map<String, Object> epMap = new LinkedHashMap<>();
                    epMap.put("method", ep.getMethod());
                    epMap.put("path", ep.getPath());
                    epMap.put("response_body", ep.getResponseBody());
                    endpoints.add(epMap);
                }
            }
        }

        summary.put("public_functions", functions);
        summary.put("api_endpoints", endpoints);
        if (!fieldShapes.isEmpty()) {
            summary.put("data_shapes", fieldShapes);
        }

        // Outline-only fallback: before a feature is enriched its files carry no
        // publicFunctions/apiEndpoints — summarize from fileRole/description instead of
        // handing peers empty lists (which read as "this feature exposes nothing").
        if (functions.isEmpty() && endpoints.isEmpty()) {
            List<Map<String, Object>> fileRoles = new ArrayList<>();
            for (FileSpec f : featureFiles) {
                String role = f.getFileRole() != null && !f.getFileRole().isBlank()
                        ? f.getFileRole() : f.getDescription();
                if (role == null || role.isBlank()) continue;
                Map<String, Object> roleMap = new LinkedHashMap<>();
                roleMap.put("file", f.getFilePath());
                roleMap.put("role", role);
                fileRoles.add(roleMap);
            }
            summary.put("note", "feature not yet detailed — contracts below are outline-level roles");
            summary.put("file_roles", fileRoles);
        }
        return summary;
    }

    // ── Subclass contract ─────────────────────────────────────────────────────

    /** Optional interaction logger — set by LlmConfig; null in tests. */
    private com.business.discovery.worker.service.LlmInteractionLogger interactionLogger;

    public void setInteractionLogger(com.business.discovery.worker.service.LlmInteractionLogger logger) {
        this.interactionLogger = logger;
    }

    protected com.business.discovery.worker.service.LlmInteractionLogger interactionLogger() {
        return interactionLogger;
    }

    /** Model identifier for interaction logs; subclasses return their configured model id. */
    protected String modelName() {
        return getClass().getSimpleName();
    }

    /**
     * Single interception point for all single-turn calls: every prompt/response pair
     * is journaled to the workspace (docs/llm/) so failed runs carry their own evidence.
     */
    protected final String callLlm(String systemPrompt, String userPrompt) {
        String response = null;
        try {
            response = doCallLlm(systemPrompt, userPrompt);
            return response;
        } finally {
            if (interactionLogger != null) {
                interactionLogger.log(modelName(), "single-turn", systemPrompt, userPrompt, response);
            }
        }
    }

    protected abstract String doCallLlm(String systemPrompt, String userPrompt);

    /**
     * Override in implementations that support tool use (Gemini Pro).
     * Default throws — not all implementations need this.
     */
    protected String callLlmWithTools(String systemPrompt, String userPrompt,
                                      WorkspaceReader workspaceReader) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " does not support tool-use calls");
    }

    /**
     * Agentic fix loop: LLM calls tools to investigate and repair compilation errors.
     * Returns true if the LLM stopped voluntarily, false if maxRounds was exhausted.
     * Default throws — only Pro model beans implement this.
     *
     * @param postRoundHook called after each round's tools execute with the list of tool
     *                      names used; a non-null return is appended to the conversation as
     *                      extra context (e.g. auto-verification compile results). May be null.
     */
    public boolean runFixAgentLoop(String systemPrompt,
                                   String userTrigger,
                                   List<ToolSpecification> tools,
                                   Function<ToolExecutionRequest, String> toolHandler,
                                   Function<List<String>, String> postRoundHook,
                                   int maxRounds) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " does not support fix agent loop");
    }

    // ── Prompt builders ───────────────────────────────────────────────────────

    private String buildArchSpecUserPrompt(BriefContext b, String slug, boolean isUpdate) {
        String changesSection = isUpdate
                ? "\nClient-requested changes:\n" + b.requestedChanges()
                  + (b.projectHistory() != null
                        ? "\n\nPrevious project state:\n" + b.projectHistory()
                        : "")
                : "";

        String mustHaveList = b.mustHaveFeatures().stream()
                .map(f -> "- " + f)
                .collect(Collectors.joining("\n"));

        String recommendedPagesList = b.recommendedPages().stream()
                .map(p -> "- " + p)
                .collect(Collectors.joining("\n"));

        return PromptTemplate.from(PromptLoader.load("user/arch_outline.txt"))
                .with("businessName",       b.businessName())
                .with("category",           b.category())
                .with("location",           b.location())
                .with("slug",               slug)
                .with("techStack",          b.techStack().toString())
                .with("mustHaveFeatures",   mustHaveList)
                .with("niceToHaveFeatures", b.niceToHaveFeatures().toString())
                .with("recommendedPages",   recommendedPagesList)
                .with("architecturalNotes", b.architecturalNotes())
                .with("seoKeywords",          b.seoKeywords().toString())
                .with("competitorInsights",  b.competitorInsights() != null ? b.competitorInsights() : "")
                .with("industryInsights",    b.industryInsights()   != null ? b.industryInsights()   : "")
                .with("designDirection",     b.designDirection())
                .with("colorScheme",         b.colorScheme())
                .with("tone",                b.tone())
                .with("changesSection",      changesSection)
                .render();
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private String formatFilesSection(Map<String, String> files) {
        if (files == null || files.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            sb.append("\n--- ").append(entry.getKey()).append(" ---\n");
            sb.append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }

    protected String stripMarkdown(String raw) {
        return LlmResponseParser.stripMarkdown(raw);
    }
}
