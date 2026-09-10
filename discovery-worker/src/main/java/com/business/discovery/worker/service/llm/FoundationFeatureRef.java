package com.business.discovery.worker.service.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The planner's assertion of ONE foundation feature this project consumes — the kept-closure
 * entry of the foundation manifest (§6b Part A of foundation-feature-manifest-plan.md), stated
 * by the outline LLM as it writes ARCHITECTURE.json rather than derived by a separate node.
 *
 * <p>Records the coupling that today is only inferred downstream by name-matching import strings
 * against {@code FoundationSymbolRegistry.isFenced(name)}: with this in the artifact, the
 * reconciler can <em>check</em> the invariant "a file imports a fenced symbol ⟺ its feature is
 * declared here" instead of re-deriving it from prose every run.
 *
 * <p>Null on specs written before this field existed (safe via {@link JsonIgnoreProperties}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FoundationFeatureRef {

    /** Foundation feature id — matches a feature in the foundation manifest. e.g. "auth", "payment", "cart". */
    private String id;

    /**
     * The fixed handles through which this project reaches the feature — the exact symbols the
     * generators import VERBATIM (frontend hooks/paths, backend types/services/events).
     * e.g. ["useAuth()", "@/api/client", "@CurrentUser Integer userId"] for auth,
     *      ["PaymentService.createOrder", "PaymentCapturedEvent"] for payment.
     */
    private List<String> consumedVia;
}
