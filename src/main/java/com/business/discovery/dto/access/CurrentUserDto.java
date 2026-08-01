package com.business.discovery.dto.access;

import com.business.discovery.model.PlatformUser;

import java.util.List;
import java.util.UUID;

/** Returned by GET /api/auth/me — the logged-in user's identity + scope for the SPA. */
public record CurrentUserDto(
        UUID id,
        String name,
        String email,
        PlatformUser.Role role,
        PlatformUser.UserStatus status,
        List<UUID> assignedBusinessIds
) {
    public static CurrentUserDto from(PlatformUser u) {
        return new CurrentUserDto(
                u.getId(), u.getName(), u.getEmail(), u.getRole(), u.getStatus(),
                List.copyOf(u.getAssignedBusinessIds()));
    }
}
