package com.business.discovery.dto.architect;

import com.business.discovery.model.AgentRun.AgentRunStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record ArchitectRunStatusResponse(
        UUID runId,
        String keyword,
        String category,
        String location,
        AgentRunStatus status,
        String currentStep,
        Integer scrapedCount,
        Integer filteredCount,
        UUID briefId,               // non-null only when status = COMPLETED
        String errorMessage,        // non-null only when status = FAILED
        LocalDateTime createdAt,
        LocalDateTime completedAt
) {}