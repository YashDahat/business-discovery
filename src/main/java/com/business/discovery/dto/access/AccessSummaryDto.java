package com.business.discovery.dto.access;

/**
 * Top-of-page tile counts for the Access & Roles screen.
 * internal = OPERATOR + ANALYST (discovery staff); external = CLIENT + RESELLER.
 * pendingInvites = users with status PENDING. totalBusinesses lets the UI render
 * "All N businesses" for OPERATOR/ANALYST without a separate call.
 */
public record AccessSummaryDto(
        long totalUsers,
        long internalUsers,
        long externalUsers,
        long operators,
        long analysts,
        long clients,
        long resellers,
        long pendingInvites,
        long totalBusinesses
) {}
