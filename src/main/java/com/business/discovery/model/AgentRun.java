package com.business.discovery.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "agent_run")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentRun {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "location", nullable = false)
    private String location;

    // Combined keyword sent to gosom e.g. "restaurants in Khadki, Pune"
    @Column(name = "keyword", nullable = false)
    private String keyword;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private AgentRunStatus status = AgentRunStatus.PENDING;

    // Tracks which node is currently executing — useful for monitoring
    @Column(name = "current_step")
    private String currentStep;

    // Updated every 60s by the running agent — detect stuck runs
    @Column(name = "last_heartbeat")
    private LocalDateTime lastHeartbeat;

    // Total businesses scraped
    @Column(name = "scraped_count")
    @Builder.Default
    private Integer scrapedCount = 0;

    // Businesses passing the scoring filter
    @Column(name = "filtered_count")
    @Builder.Default
    private Integer filteredCount = 0;

    // Error message if status = FAILED
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // Link to the brief produced at the end
    @Column(name = "brief_id")
    private UUID briefId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public enum AgentRunStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }
}