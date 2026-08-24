package com.business.discovery.worker.service.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One entry in docs/CONTRACTS.json — an audit record of a single file's interface as ContractReconciler
 * changed it: the PLANNED interface (from enrichment, before reconciliation) and the RECONCILED
 * interface (after). Purely observability — nothing reads it back; generation consumes the reconciled
 * fields written into ARCHITECTURE.json. Lets a human/PR see exactly what the reconciler decided
 * (e.g. userId: UUID → userId: Integer) instead of it being invisible inside ARCHITECTURE.json.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContractRecord {
    /** Feature the file belongs to. */
    private String featureName;
    /** Workspace-relative file path. */
    private String module;
    /** FIELDS | METHODS | PROPS — the reconciled interface kind. */
    private String kind;
    /** The interface before reconciliation (enrichment/planned). */
    private String plannedInterface;
    /** The interface after reconciliation (authoritative). */
    private String reconciledInterface;
}
