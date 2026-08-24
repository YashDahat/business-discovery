package com.business.discovery.worker.service.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Machine-readable twin of docs/ENRICHMENT.md: the full "why this feature exists and how its files
 * fit together" context for ONE feature, serialized to docs/ENRICHMENT.json as a
 * {@code Map<featureName, FeatureCard>}.
 *
 * <p>Every field is derived deterministically from {@link ArchitectureSpec} ({@link FeatureSpec} +
 * the feature's {@link FileSpec} entries) — there is no separate LLM call. The JSON exists so the
 * generator nodes can load the whole feature's context by {@code featureName} (the same key already
 * carried on every {@link FileSpec#getFeatureName()}) and inject "here is your feature and your
 * sibling files' roles" into each file-generation prompt, instead of re-deriving it from the spec.
 *
 * @see com.business.discovery.worker.util.EnrichmentCardUtil
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FeatureCard {

    /** Slug identifier — the map key in ENRICHMENT.json and FileSpec.featureName. e.g. "order-management" */
    private String featureName;

    /** Human-readable name. e.g. "Order Management" */
    private String featureDisplayName;

    /** BACKEND | FRONTEND | INFRA | SHARED */
    private String featureType;

    /** True if any file in this feature needs regeneration on an update run. */
    private boolean changeRequired;

    /** Holistic implementation brief covering all files in this feature together (from enrichment). */
    private String featureInstruction;

    /** Slugs of the OTHER features whose classes/endpoints this feature's files inject or call. */
    private List<String> dependsOnFeatures;

    /** The files that make up this feature, each with its structural role. */
    private List<FileRef> files;

    /** One file within a feature: its workspace-relative path and its role in the feature. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FileRef {
        private String path;
        private String role;
    }

    /**
     * The complete feature block for a file's generation prompt: the "== FEATURE CONTEXT ==" identity
     * + sibling map (from the card) followed by the "== FEATURE INSTRUCTION ==" holistic brief. This
     * is the single feature-level input to {@code LlmGeneratorService.generateFileContent} — it
     * replaced the former standalone featureInstruction parameter.
     *
     * @param card        the file's feature card, or null when ENRICHMENT.json is absent / the file
     *                    has no feature — the identity/sibling block is then simply omitted
     * @param currentFilePath the file being generated, marked YOU ARE HERE in the sibling map
     * @param instruction the EFFECTIVE feature instruction for this run (base + any update-run change
     *                    directive), sourced from FeatureSpec — authoritative over the card's copy
     */
    public static String buildFeatureContext(FeatureCard card, String currentFilePath, String instruction) {
        StringBuilder sb = new StringBuilder();
        if (card != null) {
            sb.append(card.toPromptSection(currentFilePath));
        }
        if (instruction != null && !instruction.isBlank()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("== FEATURE INSTRUCTION (holistic brief for all files in this feature — "
                    + "use for context and inter-file contracts) ==\n")
              .append(instruction).append("\n");
        }
        return sb.toString();
    }

    /**
     * Renders the "== FEATURE CONTEXT ==" block: the feature's identity and the sibling map (each
     * OTHER file in the feature + its role); the file being generated is marked "YOU ARE HERE" without
     * its role, since that role + reconciled contract are shown in full in the dedicated
     * "YOUR FILE'S ROLE" prompt section (avoids duplicating it every call). Tells the generator WHY
     * this file exists and how it must fit its teammates. The instruction prose is appended separately
     * by {@link #buildFeatureContext}, so it is not repeated here.
     *
     * <p><b>Side segregation:</b> the sibling map is filtered to the SAME side (backend/frontend) as the
     * file being generated. Backend generation and frontend generation are separate passes with separate
     * system prompts, so a Java file must never be shown {@code .tsx} siblings and vice versa. Most
     * features are single-sided, but a SHARED (or mis-grouped) feature can hold both — this keeps the
     * cross-side files out regardless. Non-code siblings (config, etc.) have no side and are always kept.
     */
    public String toPromptSection(String currentFilePath) {
        StringBuilder sb = new StringBuilder();
        sb.append("== FEATURE CONTEXT (why this file exists and how it fits its feature) ==\n");
        sb.append("Feature: ")
          .append(featureDisplayName != null ? featureDisplayName : featureName);
        if (featureName != null) sb.append(" (").append(featureName).append(")");
        if (featureType != null) sb.append(" [").append(featureType).append("]");
        sb.append("\n");

        if (dependsOnFeatures != null && !dependsOnFeatures.isEmpty()) {
            sb.append("Depends on features: ").append(String.join(", ", dependsOnFeatures)).append("\n");
        }

        if (files != null && !files.isEmpty()) {
            String currentSide = sideOf(currentFilePath);
            StringBuilder rows = new StringBuilder();
            for (FileRef f : files) {
                if (f == null || f.getPath() == null) continue;
                boolean isCurrent = f.getPath().equals(currentFilePath);
                // Drop opposite-side siblings; always keep the current file and side-agnostic files.
                if (!isCurrent && currentSide != null) {
                    String s = sideOf(f.getPath());
                    if (s != null && !s.equals(currentSide)) continue;
                }
                rows.append("- ").append(f.getPath());
                if (isCurrent) {
                    // The current file's role + reconciled contract are shown in full in the dedicated
                    // "YOUR FILE'S ROLE" section below — don't repeat them here (avoids per-call dup).
                    rows.append("  ← YOU ARE HERE (your role & contract are in the section below)");
                } else if (f.getRole() != null && !f.getRole().isBlank()) {
                    rows.append(" — ").append(f.getRole());
                }
                rows.append("\n");
            }
            if (rows.length() > 0) {
                sb.append("\nFiles in this feature:\n").append(rows);
            }
        }
        return sb.toString();
    }

    /** BACKEND for .java, FRONTEND for .ts/.tsx, null for side-agnostic (config, docs, etc.). */
    private static String sideOf(String path) {
        if (path == null) return null;
        if (path.endsWith(".java")) return "BACKEND";
        if (path.endsWith(".ts") || path.endsWith(".tsx")) return "FRONTEND";
        return null;
    }
}
