package com.business.discovery.api;

import com.business.discovery.model.ContainerTask;
import com.business.discovery.model.ContainerTask.ContainerTaskStatus;
import com.business.discovery.repository.ContainerTaskRepository;
import com.business.discovery.repository.GeneratedFileRepository;
import com.business.discovery.repository.MasterAgentHeartbeatRepository;
import com.business.discovery.services.container.ContainerImageService;
import com.business.discovery.services.container.ContainerMonitorService;
import com.business.discovery.services.container.ContainerPoolManager;
import com.business.discovery.services.container.DockerContainerService;
import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContainerTaskControllerTest {

    @Mock ContainerTaskRepository containerTaskRepository;
    @Mock GeneratedFileRepository generatedFileRepository;
    @Mock MasterAgentHeartbeatRepository masterAgentHeartbeatRepository;
    @Mock DockerContainerService dockerContainerService;
    @Mock ContainerImageService containerImageService;
    @Mock ContainerPoolManager containerPoolManager;
    @Mock ContainerMonitorService containerMonitorService;
    @Mock DockerClient dockerClient;

    @InjectMocks ContainerTaskController controller;

    private static final UUID TASK_ID  = UUID.randomUUID();
    private static final UUID BRIEF_ID = UUID.randomUUID();

    private ContainerTask task(ContainerTaskStatus status) {
        return ContainerTask.builder()
                .briefId(BRIEF_ID)
                .businessId(UUID.randomUUID())
                .runId(UUID.randomUUID())
                .taskDescription(Map.of("test", "true"))
                .status(status)
                .build();
    }

    // ── GET /api/v3/containers/tasks ──────────────────────────────────────

    @Test
    void getAllTasks_noFilter_returnsFullList() {
        when(containerTaskRepository.findAll())
                .thenReturn(List.of(task(ContainerTaskStatus.COMPLETED),
                        task(ContainerTaskStatus.RUNNING)));

        ResponseEntity<List<ContainerTask>> response = controller.getAllTasks(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    void getAllTasks_filteredByStatus_returnsFilteredList() {
        when(containerTaskRepository.findByStatus(ContainerTaskStatus.PENDING))
                .thenReturn(List.of(task(ContainerTaskStatus.PENDING)));

        ResponseEntity<List<ContainerTask>> response =
                controller.getAllTasks(ContainerTaskStatus.PENDING);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).getStatus()).isEqualTo(ContainerTaskStatus.PENDING);
    }

    // ── GET /api/v3/containers/tasks/{taskId} ─────────────────────────────

    @Test
    void getTask_found_returns200() {
        ContainerTask t = task(ContainerTaskStatus.RUNNING);
        when(containerTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(t));

        ResponseEntity<ContainerTask> response = controller.getTask(TASK_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getStatus()).isEqualTo(ContainerTaskStatus.RUNNING);
    }

    @Test
    void getTask_notFound_returns404() {
        when(containerTaskRepository.findById(TASK_ID)).thenReturn(Optional.empty());

        ResponseEntity<ContainerTask> response = controller.getTask(TASK_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── GET /api/v3/containers/tasks/{taskId}/files ───────────────────────

    @Test
    void getTaskFiles_returnsList() {
        when(generatedFileRepository.findByTaskId(TASK_ID)).thenReturn(List.of());

        ResponseEntity<?> response = controller.getTaskFiles(TASK_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── GET /api/v3/containers/pool/status ───────────────────────────────

    @Test
    void getPoolStatus_returnsAllFields() {
        when(containerPoolManager.poolSize()).thenReturn(2);
        when(containerPoolManager.activeSlots()).thenReturn(1);
        when(containerPoolManager.availableSlots()).thenReturn(1);
        when(containerPoolManager.isSlotAvailable()).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.getPoolStatus();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("poolSize", 2)
                .containsEntry("activeSlots", 1)
                .containsEntry("isSlotAvailable", true);
    }
}
