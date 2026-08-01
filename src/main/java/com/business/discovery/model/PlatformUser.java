package com.business.discovery.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A person who can log in to the Discovery Ops Console.
 *
 * Roles (see {@link Role}):
 *   OPERATOR  — full platform access (admin)
 *   ANALYST   — read-only across business details + full KPI framework
 *   CLIENT    — can only view their own assigned business(es)
 *   RESELLER  — read-only, business-agnostic KPI/aggregate data
 *
 * Business scoping: OPERATOR/ANALYST are implicitly "all businesses" and carry no
 * explicit assignments; CLIENT/RESELLER carry the specific businesses they may see
 * in {@link #assignedBusinessIds} (join table platform_user_business).
 */
@Entity
@Table(name = "platform_user",
        uniqueConstraints = @UniqueConstraint(name = "uk_platform_user_email", columnNames = "email"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlatformUser {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    // Login identifier — unique, stored lower-cased by UserService.
    @Column(name = "email", nullable = false)
    private String email;

    // BCrypt hash — never the plaintext password.
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    // Only meaningful for CLIENT/RESELLER — the businesses this user is scoped to.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "platform_user_business",
            joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "business_id")
    @Builder.Default
    private Set<UUID> assignedBusinessIds = new HashSet<>();

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ─── Enums ────────────────────────────────────────────

    public enum Role {
        OPERATOR,   // full platform access
        ANALYST,    // read-only + full KPI framework
        CLIENT,     // scoped to their own business(es)
        RESELLER    // read-only, business-agnostic KPI/aggregate data
    }

    public enum UserStatus {
        ACTIVE,     // can log in
        PENDING,    // created, not yet activated
        DISABLED    // blocked — cannot log in
    }

    // ─── Convenience ──────────────────────────────────────

    public boolean isBusinessScoped() {
        return role == Role.CLIENT || role == Role.RESELLER;
    }
}
