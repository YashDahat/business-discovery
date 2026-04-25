package com.business.discovery.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Immutable                          // events are write-once, never updated
@Table(name = "agent_event")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentEvent {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "step_name")
    private String stepName;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    // Structured payload — tool inputs/outputs, LLM responses, scores etc.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "duration_ms")
    private Long durationMs;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public enum EventType {
        STEP_STARTED,
        STEP_COMPLETED,
        STEP_FAILED,
        TOOL_CALL,
        LLM_CALL,
        SCRAPE_SUBMITTED,
        SCRAPE_COMPLETED,
        BUSINESSES_SCORED,
        BRIEF_GENERATED,
        ERROR
    }
}