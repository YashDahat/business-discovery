package com.business.discovery.worker.service.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FeatureSpec {

    /** Slug identifier — foreign key referenced in FileSpec.featureName. e.g. "order-management" */
    private String featureName;

    /** Human-readable name for logging and history. e.g. "Order Management" */
    private String featureDisplayName;

    /**
     * BACKEND | FRONTEND | INFRA | SHARED
     * String (not enum) so LLM casing variants don't break deserialization.
     * INFRA features are never enriched — InfraGeneratorNode handles them with its own context.
     */
    private String featureType;

    /** Full relative file paths of all FileSpec entries belonging to this feature. */
    private List<String> filePaths;

    /**
     * Holistic implementation brief covering all files in this feature together.
     * Null until enrichFeature() runs. A non-blank value signals "already enriched" for resume.
     */
    private String featureInstruction;

    /**
     * Slugs of the OTHER features whose classes or endpoints this feature's files inject or call —
     * declared by enrichment, which already works this wiring out for the instruction prose.
     * Capturing it structurally is what makes dependency direction checkable: it feeds the
     * "these features depend on you, do not call back" constraint injected into later enrichment
     * calls, and the post-enrichment cycle gate that catches a bean cycle at planning time instead
     * of at container boot. Null on specs written before this field existed (treated as no edges).
     *
     * @see com.business.discovery.worker.util.FeatureDependencyGraph
     */
    private List<String> dependsOnFeatures;

    /**
     * True if any file in this feature needs regeneration on an update run.
     * Defaults to true so old specs without this field regenerate everything (safe).
     * FileSpec.changeRequired (nullable) narrows the decision to file grain when present.
     */
    @Builder.Default
    private boolean changeRequired = true;

    /**
     * What to change in this feature on an update run — set by the change-targeting pass from the
     * client's requested changes, null otherwise. Kept SEPARATE from featureInstruction: the
     * enrichment instruction is stable across runs (and signals "already enriched" for resume),
     * while this is per-update-run and overwritten by the next targeting pass.
     */
    private String changeInstruction;

    /**
     * The instruction the file-generation prompt should see. On update runs the change directive
     * is appended so the minimal-diff edit knows WHAT to change — without this the client's
     * request never reaches the generation prompt (enrichment does not re-run on update runs).
     */
    public String effectiveInstruction(boolean requestedChangesMode) {
        if (!requestedChangesMode || changeInstruction == null || changeInstruction.isBlank()) {
            return featureInstruction;
        }
        return (featureInstruction == null ? "" : featureInstruction)
                + "\n\n== REQUESTED CHANGE (this update run — apply as a minimal edit) ==\n"
                + changeInstruction;
    }
}
