package com.business.discovery.worker.service.llm;

import java.util.List;

/**
 * The reconciled public interface of one spec file, produced by the planning-time
 * {@code ContractReconciler} (Pro). It is the GROUND TRUTH both the backend and frontend generators
 * bind to — the single agreed contract that stops producer/consumer drift across siblings, models,
 * services and props (docs/frontend-hook-generation-and-prompt-segregation.md — the same drift on
 * both sides: service↔repo↔DTO on the back, page↔props↔DTO on the front).
 *
 * @param module  the file the contract is for — its path as planned
 * @param members fields (MODEL/DTO/ENUM/TYPE) or props (COMPONENT/PAGE), each a {@code name}/{@code type}
 * @param methods method signatures (SERVICE/REPOSITORY/CONTROLLER); empty for data/UI files
 */
public record FileContract(String module, List<Member> members, List<Method> methods) {

    /** A field or prop, e.g. {@code durationMonths: Integer} or {@code onSelectClass: (c: FitnessClassDto) => void}. */
    public record Member(String name, String type) {
        public String asMember() { return name + ": " + type; }
    }

    /**
     * A method's full signature verbatim, e.g.
     * {@code createSubscription(request: CreateSubscriptionRequest): MemberSubscriptionDto}.
     */
    public record Method(String signature) {}
}
