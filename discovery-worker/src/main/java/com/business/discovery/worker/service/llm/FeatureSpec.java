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
     * True if any file in this feature needs regeneration on an update run.
     * Replaces per-file FileSpec.changeRequired — features are regenerated atomically.
     * Defaults to true so old specs without this field regenerate everything (safe).
     */
    @Builder.Default
    private boolean changeRequired = true;
}
