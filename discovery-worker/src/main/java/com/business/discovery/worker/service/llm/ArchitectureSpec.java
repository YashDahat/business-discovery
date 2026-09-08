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
public class ArchitectureSpec {
    private String generatedAt;
    private String businessName;
    private String basePackage;
    private ProjectDependencies projectDependencies;
    private List<FileSpec> files;

    /**
     * Feature groups — each maps to a set of files sharing one business domain.
     * Null on old specs (deserializes safely via @JsonIgnoreProperties).
     */
    private List<FeatureSpec> features;

    /**
     * The planner's assertion of which foundation features this project actually consumes —
     * the kept closure of the foundation manifest, declared as outline output (§6b Part A of
     * foundation-feature-manifest-plan.md). Serializes as {@code foundation_features} (SNAKE_CASE).
     * Makes the foundation↔domain coupling a first-class artifact fact the reconciler can check
     * instead of inferring from import strings. Null on old specs (safe via @JsonIgnoreProperties).
     */
    private List<FoundationFeatureRef> foundationFeatures;
}
