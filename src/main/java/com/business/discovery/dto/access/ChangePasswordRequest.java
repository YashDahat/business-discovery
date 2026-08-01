package com.business.discovery.dto.access;

/**
 * Self-service password change (POST /api/auth/change-password). The current password
 * is required and verified before the new one is accepted.
 */
public record ChangePasswordRequest(
        String currentPassword,
        String newPassword
) {}
