package com.business.discovery.worker;

import com.business.discovery.worker.model.ArchitectBrief;
import com.business.discovery.worker.model.BusinessEntity;
import com.business.discovery.worker.model.ContainerTask;
import com.business.discovery.worker.model.ContainerTask.ContainerTaskStatus;
import com.business.discovery.worker.model.GeneratedFile;
import com.business.discovery.worker.model.GeneratedFile.FileStatus;
import com.business.discovery.worker.model.GeneratedFile.FileType;
import com.business.discovery.worker.orchestrator.WorkerOrchestrator;
import com.business.discovery.worker.repository.ArchitectBriefRepository;
import com.business.discovery.worker.repository.BusinessEntityRepository;
import com.business.discovery.worker.repository.ContainerTaskRepository;
import com.business.discovery.worker.repository.GeneratedFileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JPA integration test against local native Postgres (business_discovery_test DB).
 * Profile "test" prevents WorkerApplication from calling System.exit().
 * Prereq: CREATE DATABASE business_discovery_test; (already done)
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/business_discovery_test",
        "spring.datasource.username=amardahat",
        "spring.datasource.password=94227",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "worker.task.id=00000000-0000-0000-0000-000000000001",
        "worker.task.brief-id=00000000-0000-0000-0000-000000000002",
        "worker.task.business-id=00000000-0000-0000-0000-000000000003",
        "worker.llm.anthropic.api-key=test-key",
        "worker.github.token=test-token",
        "worker.github.owner=test-owner"
})
@ActiveProfiles("test")
class WorkerDatabaseIntegrationTest {

    @MockitoBean
    private WorkerOrchestrator orchestrator; // mocked so run() is no-op; profile "test" skips System.exit()

    @Autowired
    private ArchitectBriefRepository briefRepo;

    @Autowired
    private BusinessEntityRepository businessRepo;

    @Autowired
    private ContainerTaskRepository taskRepo;

    @Autowired
    private GeneratedFileRepository fileRepo;

    @Test
    void insertAndReadGeneratedFile() {
        UUID taskId = UUID.randomUUID();

        GeneratedFile file = GeneratedFile.builder()
                .taskId(taskId)
                .filePath("backend/src/main/java/com/test/controller/ProductController.java")
                .fileType(FileType.BACKEND)
                .status(FileStatus.GENERATED)
                .attemptNumber(1)
                .build();

        GeneratedFile saved = fileRepo.save(file);

        assertThat(saved.getId()).isNotNull();
        assertThat(fileRepo.findByTaskId(taskId)).hasSize(1);
        assertThat(fileRepo.findByTaskIdAndStatus(taskId, FileStatus.GENERATED)).hasSize(1);
        assertThat(fileRepo.findByTaskIdAndFileType(taskId, FileType.BACKEND)).hasSize(1);
        assertThat(fileRepo.countByTaskIdAndStatus(taskId, FileStatus.GENERATED)).isEqualTo(1);
    }

    @Test
    void readArchitectBrief() {
        ArchitectBrief brief = ArchitectBrief.builder()
                .runId(UUID.randomUUID())
                .businessCategory("Restaurant")
                .location("Mumbai")
                .mustHaveFeatures(List.of("Menu", "Reservation"))
                .recommendedTechStack(Map.of("frontend", "React", "backend", "Spring Boot"))
                .build();

        ArchitectBrief saved = briefRepo.save(brief);

        ArchitectBrief found = briefRepo.findById(saved.getId()).orElseThrow();
        assertThat(found.getBusinessCategory()).isEqualTo("Restaurant");
        assertThat(found.getMustHaveFeatures()).containsExactly("Menu", "Reservation");
        assertThat(found.getRecommendedTechStack()).containsEntry("frontend", "React");
    }

    @Test
    void readBusinessEntity() {
        BusinessEntity biz = BusinessEntity.builder()
                .runId(UUID.randomUUID())
                .title("Shree Restaurant")
                .category("Restaurant")
                .address("Andheri West, Mumbai")
                .rating(4.3)
                .reviewCount(120)
                .build();

        BusinessEntity saved = businessRepo.save(biz);

        BusinessEntity found = businessRepo.findById(saved.getId()).orElseThrow();
        assertThat(found.getTitle()).isEqualTo("Shree Restaurant");
        assertThat(found.getRating()).isEqualTo(4.3);
    }

    @Test
    void updateContainerTaskGithubUrls() {
        ContainerTask task = ContainerTask.builder()
                .briefId(UUID.randomUUID())
                .businessId(UUID.randomUUID())
                .runId(UUID.randomUUID())
                .taskDescription(Map.of("key", "value"))
                .status(ContainerTaskStatus.RUNNING)
                .build();

        ContainerTask saved = taskRepo.save(task);
        UUID taskId = saved.getId();

        taskRepo.updateGithubRepoUrl(taskId, "https://github.com/test/repo");
        taskRepo.updateGithubPrUrl(taskId, "https://github.com/test/repo/pull/1");

        ContainerTask updated = taskRepo.findById(taskId).orElseThrow();
        assertThat(updated.getGithubRepoUrl()).isEqualTo("https://github.com/test/repo");
        assertThat(updated.getGithubPrUrl()).isEqualTo("https://github.com/test/repo/pull/1");
    }

    @Test
    void updateContainerTaskStatus() {
        ContainerTask task = ContainerTask.builder()
                .briefId(UUID.randomUUID())
                .businessId(UUID.randomUUID())
                .runId(UUID.randomUUID())
                .taskDescription(Map.of("key", "value"))
                .status(ContainerTaskStatus.RUNNING)
                .build();

        ContainerTask saved = taskRepo.save(task);

        taskRepo.updateStatus(saved.getId(), ContainerTaskStatus.COMPLETED);

        ContainerTask updated = taskRepo.findById(saved.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(ContainerTaskStatus.COMPLETED);
    }
}
