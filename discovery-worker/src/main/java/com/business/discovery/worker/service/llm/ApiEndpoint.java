package com.business.discovery.worker.service.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiEndpoint {
    private String method;
    private String path;
    private String requestBody;
    private String responseBody;
    private String description;

    /**
     * Who may call this endpoint: {@code public} | {@code authenticated} | {@code admin}.
     * Set by the enrichment pass, consumed by ApiAccessPolicy → SecurityConfigPatcher (writes
     * the matchers) and the smoke flows gate (probes them).
     *
     * Declared here rather than inferred from the path because only the plan knows the
     * business: a gym's public catalog is /classes and /trainers, a clinic's is /doctors, a
     * realtor's is /listings. Null on old specs or when the model omits it — the policy then
     * falls back to its own catalog heuristic, which is deny-by-default.
     */
    private String access;
}
