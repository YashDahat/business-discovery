package com.business.discovery.dto.access;

import com.business.discovery.model.PlatformUser;

import java.util.List;
import java.util.UUID;

/**
 * Body for PATCH /api/admin/users/{id}. Every field is optional — only non-null
 * fields are applied. A non-blank {@code password} re-hashes and resets the user's
 * password (operator-driven password reset). {@code status} covers block/unblock.
 */
public record UpdateUserRequest(
        String name,
        PlatformUser.Role role,
        PlatformUser.UserStatus status,
        String password,
        List<UUID> assignedBusinessIds
) {}
