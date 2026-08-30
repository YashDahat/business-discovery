package com.business.discovery.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row per Cline execution sandbox — a long-lived, per-brief container the master spawns from the
 * {@code discovery-sandbox} image, clones the project's working branch into, and drives via
 * {@code docker exec} (SandboxManager/SandboxExecService). Reused across chat turns so installed deps
 * persist; reaped after {@code sandbox.idle-timeout-minutes} of inactivity by SandboxReaper.
 */
@Entity
@Table(name = "sandbox_instance")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SandboxInstance {

    public enum SandboxStatus { CREATING, READY, ERROR, STOPPED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "brief_id", nullable = false)
    private UUID briefId;

    @Column(name = "container_id")
    private String containerId;

    @Column(name = "container_name")
    private String containerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SandboxStatus status;

    @Column(name = "repo_url")
    private String repoUrl;

    @Column(name = "branch")
    private String branch;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
