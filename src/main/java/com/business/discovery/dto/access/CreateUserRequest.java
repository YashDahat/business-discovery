package com.business.discovery.dto.access;

import com.business.discovery.model.PlatformUser;

import java.util.List;
import java.util.UUID;

/**
 * Body for POST /api/admin/users. The operator sets the initial password directly
 * (no email invite). assignedBusinessIds is only used for CLIENT/RESELLER.
 */
public record CreateUserRequest(
        String name,
        String email,
        String password,
        PlatformUser.Role role,
        List<UUID> assignedBusinessIds
) {}
