package com.business.discovery.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "generated_file")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GeneratedFile {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    // e.g. backend/src/main/java/com/venustraderspune/controller/ProductController.java
    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false)
    private FileType fileType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private FileStatus status = FileStatus.PENDING;

    // LLM token consumption for this file — cost attribution
    @Column(name = "input_token_count")
    private Integer inputTokenCount;

    @Column(name = "output_token_count")
    private Integer outputTokenCount;

    // Attempt this file was generated on (1, 2, or 3)
    @Column(name = "attempt_number", nullable = false)
    @Builder.Default
    private Integer attemptNumber = 1;

    // Error message if this specific file caused a compilation failure
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public enum FileType {
        BACKEND,        // Java Spring Boot files
        FRONTEND,       // React/TypeScript files
        INFRA,          // Dockerfile, docker-compose, GitHub Actions
        CONFIG          // application.properties, package.json, pom.xml
    }

    public enum FileStatus {
        PENDING,
        GENERATED,      // file written to disk
        VALIDATED,      // compilation/build passed
        FAILED          // caused a compilation or build error
    }
}