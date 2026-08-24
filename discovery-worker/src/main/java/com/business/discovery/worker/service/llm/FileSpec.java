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
public class FileSpec {
    private String fileName;
    private String filePath;
    private String fileType;
    private String layer;
    private String createdDate;
    private String updatedDate;
    private String status;
    private String description;
    private List<PublicFunction> publicFunctions;
    private List<PublicVariable> publicVariables;
    private List<ApiEndpoint> apiEndpoints;
    private List<ApiEndpoint> apiEndpointsConsumed;
    private List<String> importsFrom;
    private List<String> dependsOn;

    /** References FeatureSpec.featureName. Set by the planning LLM during generateArchitectureSpec. */
    private String featureName;

    /**
     * Short structural role of this file within its feature. Set by the planning LLM (not enrichment).
     * e.g. "SERVICE layer — implements createOrder(CreateOrderRequestDTO): OrderResponseDTO
     * and getOrderById(UUID): OrderResponseDTO using OrderRepository and PaymentService"
     */
    private String fileRole;

    /**
     * File-grain change targeting for update runs — set by the change-targeting pass, consumed by
     * shouldSkip in the generator nodes. Deliberately Boolean, not boolean: null means "no
     * file-grain decision exists" (old spec, or targeting omitted this file) and the decision
     * falls back to the feature-level FeatureSpec.changeRequired — the pre-file-grain behavior.
     */
    private Boolean changeRequired;

    /**
     * True once ContractReconciler has set this file's interface — i.e. its signatures were
     * cross-checked against its producers/consumers and the foundation, so they are AUTHORITATIVE.
     * Null/false means the interface is only the planned/enriched one (best-available, not
     * reconciled). Lets generation label a contract "reconciled" vs "planned" and lets a consumer
     * treat a reconciled dependency interface as ground truth over the dependency's raw body.
     */
    private Boolean contractReconciled;
}
