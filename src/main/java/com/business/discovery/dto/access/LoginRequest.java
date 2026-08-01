package com.business.discovery.dto.access;

/** Credentials posted to POST /api/auth/login. Verified against platform_user (BCrypt). */
public record LoginRequest(
        String email,
        String password
) {}
