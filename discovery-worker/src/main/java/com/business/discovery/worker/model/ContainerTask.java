package com.business.discovery.worker.model;

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

    @Column(name = "brief_id", nullable = false)
    private UUID briefId;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "task_description", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> taskDescription;

    @Column(name = "docker_container_id")
    private String dockerContainerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private ContainerTaskStatus status = ContainerTaskStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    @Builder.Default
    private Integer maxAttempts = 3;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_type")
    private ContainerFailureType failureType;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "container_logs", columnDefinition = "TEXT")
    private String containerLogs;

    @Column(name = "github_repo_url")
    private String githubRepoUrl;

    @Column(name = "github_branch")
    private String githubBranch;

    @Column(name = "github_pr_url")
    private String githubPrUrl;

    @Column(name = "github_token_expires_at")
    private LocalDateTime githubTokenExpiresAt;

    @Column(name = "llm_input_tokens")
    private Long llmInputTokens;

    @Column(name = "llm_output_tokens")
    private Long llmOutputTokens;

    @Column(name = "generation_cost_usd")
    private Double generationCostUsd;

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

    public enum ContainerTaskStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        RETRYING
    }

    public enum ContainerFailureType {
        INFRA,
        CODE,
        CONFIG_AUTH,
        MAX_RETRIES
    }

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
