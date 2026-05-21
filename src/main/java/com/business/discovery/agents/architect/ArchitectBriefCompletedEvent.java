package com.business.discovery.agents.architect;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

@Getter
public class ArchitectBriefCompletedEvent extends ApplicationEvent {

    private final UUID runId;
    private final UUID briefId;
    private final UUID businessId;

    public ArchitectBriefCompletedEvent(Object source,
                                        UUID runId,
                                        UUID briefId,
                                        UUID businessId) {
        super(source);
        this.runId = runId;
        this.briefId = briefId;
        this.businessId = businessId;
    }
}