package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FeatureSpec;
import com.business.discovery.worker.service.llm.FileSpec;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * Applies a change-targeting LLM response to the architecture spec: per-feature changeRequired +
 * changeInstruction, and per-file changeRequired. Pure spec mutation — no LLM coupling — so the
 * targeting decision logic is unit-testable.
 *
 * <p>Reset-then-apply: every feature is first reset to changeRequired=false and every file flag to
 * null. The specs on disk carry stale marks — a first run serializes the default
 * change_required=true on every feature, and a previous update run leaves its own marks — so
 * anything short of a full reset lets an old mark trigger a spurious regeneration. After the reset,
 * only what THIS response names is marked. INFRA features stay false (never enriched or
 * change-targeted; InfraGeneratorNode has its own update-mode handling).
 */
@Slf4j
public final class ChangeTargetingUtil {

    private ChangeTargetingUtil() {}

    /** Marks every non-INFRA feature changed and clears file grain — full-regeneration fallback. */
    public static void markAllChanged(ArchitectureSpec spec) {
        if (spec.getFeatures() != null) {
            for (FeatureSpec feature : spec.getFeatures()) {
                feature.setChangeRequired(!"INFRA".equalsIgnoreCase(feature.getFeatureType()));
                feature.setChangeInstruction(null);
            }
        }
        if (spec.getFiles() != null) {
            spec.getFiles().forEach(f -> f.setChangeRequired(null));
        }
    }

    /**
     * Applies the targeting response. Expected shape:
     * <pre>{"features":[{"feature_name":"...","change_required":true,"change_instruction":"...",
     * "files":[{"file_path":"...","change_required":true}]}]}</pre>
     * Returns the number of features marked changed. The outline owns both lists: unknown feature
     * names and file paths in the response are dropped with a warning, never added.
     */
    public static int apply(ArchitectureSpec spec, JsonNode result) {
        Map<String, FeatureSpec> featuresByName = new HashMap<>();
        if (spec.getFeatures() != null) {
            for (FeatureSpec f : spec.getFeatures()) {
                if (f.getFeatureName() != null) featuresByName.put(f.getFeatureName(), f);
            }
        }
        Map<String, FileSpec> filesByPath = new HashMap<>();
        if (spec.getFiles() != null) {
            for (FileSpec f : spec.getFiles()) {
                if (f.getFilePath() != null) filesByPath.put(f.getFilePath(), f);
            }
        }

        // Reset: only this response's marks survive (see class doc).
        featuresByName.values().forEach(f -> {
            f.setChangeRequired(false);
            f.setChangeInstruction(null);
        });
        filesByPath.values().forEach(f -> f.setChangeRequired(null));

        int changedFeatures = 0;
        for (JsonNode featureNode : result.path("features")) {
            String name = featureNode.path("feature_name").asText(null);
            FeatureSpec feature = name == null ? null : featuresByName.get(name);
            if (feature == null) {
                log.warn("[ChangeTargeting] Unknown feature '{}' in targeting response — dropped", name);
                continue;
            }
            if ("INFRA".equalsIgnoreCase(feature.getFeatureType())) continue;

            boolean changed = featureNode.path("change_required").asBoolean(false);
            feature.setChangeRequired(changed);
            if (changed) {
                changedFeatures++;
                String instruction = featureNode.path("change_instruction").asText(null);
                if (instruction != null && !instruction.isBlank()) {
                    feature.setChangeInstruction(instruction);
                }
            }

            for (JsonNode fileNode : featureNode.path("files")) {
                String path = fileNode.path("file_path").asText(null);
                FileSpec file = path == null ? null : filesByPath.get(path);
                if (file == null) {
                    log.warn("[ChangeTargeting] Unknown file '{}' in targeting response for feature '{}' "
                            + "— dropped (the outline owns the file list)", path, name);
                    continue;
                }
                if (fileNode.has("change_required")) {
                    file.setChangeRequired(fileNode.path("change_required").asBoolean(false));
                }
            }
        }
        return changedFeatures;
    }
}
