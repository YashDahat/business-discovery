package com.business.discovery.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "container_task")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContainerTask {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // ─── Links to Phase 2 ─────────────────────────────────
    @Column(name = "brief_id", nullable = false)
    private UUID briefId;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    // ─── Task description ─────────────────────────────────
    // Full ArchitectBrief serialized as JSON — passed to sub-container via TASK_DESC ENV
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "task_description", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> taskDescription;

    // ─── Container lifecycle ──────────────────────────────
    // Docker container ID — set after spawn, used for monitoring and cleanup
    @Column(name = "docker_container_id")
    private String dockerContainerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private ContainerTaskStatus status = ContainerTaskStatus.PENDING;

    // ─── Attempt tracking ─────────────────────────────────
    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    @Builder.Default
    private Integer maxAttempts = 3;

    // ─── Failure details ──────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "failure_type")
    private ContainerFailureType failureType;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // Container logs captured on failure — fed back to LLM for re-generation
    @Column(name = "container_logs", columnDefinition = "TEXT")
    private String containerLogs;

    // ─── GitHub details ───────────────────────────────────
    @Column(name = "github_repo_url")
    private String githubRepoUrl;

    @Column(name = "github_branch")
    private String githubBranch;

    @Column(name = "github_pr_url")
    private String githubPrUrl;

    // GHCR image ref pushed by the worker after smoke gates pass — the demoable artifact.
    // Null until an attempt passes the smoke test; DemoService pulls and runs this image.
    @Column(name = "published_image")
    private String publishedImage;

    // Which smoke gate failed (launch/boot/frontend/api-data) — null when passed or not run.
    // Structured so stage pass-rates are a GROUP BY, not log archaeology.
    @Column(name = "failed_gate")
    private String failedGate;

    // GitHub App installation token — expires in 1hr
    @Column(name = "github_token_expires_at")
    private LocalDateTime githubTokenExpiresAt;

    @Column(name = "llm_input_tokens")
    private Long llmInputTokens;

    @Column(name = "llm_output_tokens")
    private Long llmOutputTokens;

    @Column(name = "generation_cost_usd")
    private Double generationCostUsd;

    // ─── Timestamps ───────────────────────────────────────
    @Column(name = "spawned_at")
    private LocalDateTime spawnedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ─── Enums ────────────────────────────────────────────

    public enum ContainerTaskStatus {
        PENDING,        // in queue, waiting for pool slot
        RUNNING,        // container spawned and running
        COMPLETED,      // exit code 0, PR created
        FAILED,         // all retries exhausted
        RETRYING        // failed once, queued for retry
    }

    public enum ContainerFailureType {
        INFRA,          // OOM, timeout — transient, retry
        CODE,           // compilation/test failure — retry with error context
        CONFIG_AUTH,    // GitHub token expired, DB unreachable — alert human
        MAX_RETRIES     // gave up after 3 attempts
    }

    // ─── Convenience methods ──────────────────────────────

    public boolean canRetry() {
        return attemptCount < maxAttempts
                && failureType != ContainerFailureType.CONFIG_AUTH;
    }

    public boolean isTimedOut(int maxLifetimeMinutes) {
        if (spawnedAt == null) return false;
        return spawnedAt.plusMinutes(maxLifetimeMinutes)
                .isBefore(LocalDateTime.now());
    }
}