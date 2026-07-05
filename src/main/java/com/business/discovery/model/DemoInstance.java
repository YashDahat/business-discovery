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
 * One row per running (or recently stopped) client demo.
 *
 * A demo runs the GHCR image published by the worker after smoke gates passed
 * (container_task.published_image) — pulled and started as sibling containers,
 * never rebuilt. Reaped after expiresAt by DemoReaperService.
 */
@Entity
@Table(name = "demo_instance")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DemoInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "brief_id", nullable = false)
    private UUID briefId;

    /** Docker-safe name fragment, derived from the image repo (e.g. log-house-restaurant). */
    @Column(name = "slug", nullable = false)
    private String slug;

    /** GHCR ref being served, e.g. ghcr.io/owner/log-house-restaurant:ab12cd3 */
    @Column(name = "image_ref", nullable = false)
    private String imageRef;

    @Column(name = "host_port")
    private Integer hostPort;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private DemoStatus status = DemoStatus.PULLING;

    @Column(name = "demo_url")
    private String demoUrl;

    @Column(name = "app_container_id")
    private String appContainerId;

    @Column(name = "db_container_id")
    private String dbContainerId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    public enum DemoStatus {
        PULLING,    // image pull in progress
        STARTING,   // containers created, waiting for /actuator/health
        RUNNING,    // boot gate passed — demoUrl is live
        FAILED,     // pull/start/boot failed — see errorMessage
        STOPPED     // torn down (manual stop or reaper)
    }

    public boolean isActive() {
        return status == DemoStatus.PULLING || status == DemoStatus.STARTING || status == DemoStatus.RUNNING;
    }
}
