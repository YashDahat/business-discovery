package com.business.discovery.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "master_agent_heartbeat")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MasterAgentHeartbeat {

    // Single row — always id = 1
    @Id
    @Column(name = "id")
    @Builder.Default
    private Integer id = 1;

    @Column(name = "last_heartbeat", nullable = false)
    private LocalDateTime lastHeartbeat;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private MasterStatus status = MasterStatus.STARTING;

    // How many containers currently active according to master
    @Column(name = "active_containers", nullable = false)
    @Builder.Default
    private Integer activeContainers = 0;

    // Last time orphan recovery ran
    @Column(name = "last_orphan_check")
    private LocalDateTime lastOrphanCheck;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum MasterStatus {
        STARTING,
        RUNNING,
        RECOVERING   // restarted — orphan recovery in progress
    }

    public boolean isStale(int thresholdSeconds) {
        return lastHeartbeat.plusSeconds(thresholdSeconds)
                .isBefore(LocalDateTime.now());
    }
}