package com.business.discovery.dto;

import java.util.UUID;

public record ArchitectRunResponse(
        UUID runId,
        String status,
        String message
) {}