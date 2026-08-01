package com.business.discovery.dto.access;

import com.business.discovery.model.PlatformUser;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One row in the Access & Roles user list. {@code assignedBusinessNames} is resolved
 * from BusinessEntity so the UI can render assignment chips without a second lookup.
 */
public record UserSummaryDto(
        UUID id,
        String name,
        String email,
        PlatformUser.Role role,
        PlatformUser.UserStatus status,
        List<UUID> assignedBusinessIds,
        List<String> assignedBusinessNames,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt
) {}
