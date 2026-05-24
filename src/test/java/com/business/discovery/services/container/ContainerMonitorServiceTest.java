package com.business.discovery.services.container;

import com.business.discovery.model.ContainerTask;
import com.business.discovery.model.ContainerTask.ContainerFailureType;
import com.business.discovery.model.ContainerTask.ContainerTaskStatus;
import com.business.discovery.model.MasterAgentHeartbeat;
import com.business.discovery.repository.ArchitectBriefRepository;
import com.business.discovery.repository.ContainerTaskRepository;
import com.business.discovery.repository.MasterAgentHeartbeatRepository;
import com.business.discovery.services.agent.AgentEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContainerMonitorServiceTest {

    @Mock ContainerTaskRepository containerTaskRepository;
    @Mock MasterAgentHeartbeatRepository heartbeatRepository;
    @Mock DockerContainerService dockerContainerService;
    @Mock ContainerPoolManager poolManager;
    @Mock AgentEventService agentEventService;
    @Mock ArchitectBriefRepository architectBriefRepository;

    @InjectMocks ContainerMonitorService monitor;

    private static final UUID TASK_ID  = UUID.randomUUID();
    private static final UUID BRIEF_ID = UUID.randomUUID();
    private static final UUID RUN_ID   = UUID.randomUUID();
    private static final String CONTAINER_ID = "abc123def456";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(monitor, "maxLifetimeMinutes", 30);
    }

    private ContainerTask runningTask() {
        return ContainerTask.builder()
                .id(TASK_ID)
                .briefId(BRIEF_ID)
                .businessId(UUID.randomUUID())
                .runId(RUN_ID)
                .taskDescription(Map.of("test", "true"))
                .status(ContainerTaskStatus.RUNNING)
                .dockerContainerId(CONTAINER_ID)
                .spawnedAt(LocalDateTime.now().minusMinutes(5))
                .build();
    }

    // ── Success path ──────────────────────────────────────────────────────

    @Test
    void monitorContainers_containerExitedZero_taskCompletedAndChangesCleared() {
        ContainerTask task = runningTask();
        when(containerTaskRepository.findByStatus(ContainerTaskStatus.RUNNING))
                .thenReturn(List.of(task));
        when(dockerContainerService.isContainerRunning(CONTAINER_ID)).thenReturn(false);
        when(dockerContainerService.getExitCode(CONTAINER_ID)).thenReturn(0);
        when(containerTaskRepository.save(any())).thenReturn(task);
        doNothing().when(architectBriefRepository).updateRequestedChanges(any(), any());
        doNothing().when(agentEventService).log(any(), any(), any(), any(), any(), any());

        monitor.monitorContainers();

        ArgumentCaptor<ContainerTask> saved = ArgumentCaptor.forClass(ContainerTask.class);
        verify(containerTaskRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ContainerTaskStatus.COMPLETED);

        // MC10: requested_changes must be cleared to null on success
        verify(architectBriefRepository).updateRequestedChanges(BRIEF_ID, null);
        verify(poolManager).releaseSlot();
        verify(dockerContainerService).removeContainer(CONTAINER_ID);
    }

    // ── Still running ─────────────────────────────────────────────────────

    @Test
    void monitorContainers_containerStillRunning_noStatusChange() {
        ContainerTask task = runningTask();
        when(containerTaskRepository.findByStatus(ContainerTaskStatus.RUNNING))
                .thenReturn(List.of(task));
        when(dockerContainerService.isContainerRunning(CONTAINER_ID)).thenReturn(true);

        monitor.monitorContainers();

        verify(containerTaskRepository, never()).save(any());
        verify(poolManager, never()).releaseSlot();
    }

    // ── No running tasks ──────────────────────────────────────────────────

    @Test
    void monitorContainers_noRunningTasks_skipsProcessing() {
        when(containerTaskRepository.findByStatus(ContainerTaskStatus.RUNNING))
                .thenReturn(List.of());

        monitor.monitorContainers();

        verify(dockerContainerService, never()).isContainerRunning(any());
    }

    // ── Code failure with retries remaining ───────────────────────────────

    @Test
    void monitorContainers_codeFailureWithRetriesRemaining_taskSetToRetrying() {
        ContainerTask task = runningTask();
        when(containerTaskRepository.findByStatus(ContainerTaskStatus.RUNNING))
                .thenReturn(List.of(task));
        when(dockerContainerService.isContainerRunning(CONTAINER_ID)).thenReturn(false);
        when(dockerContainerService.getExitCode(CONTAINER_ID)).thenReturn(1);
        when(dockerContainerService.getContainerLogs(CONTAINER_ID))
                .thenReturn("BUILD FAILED\ncompilation failed: cannot find symbol");
        when(containerTaskRepository.save(any())).thenReturn(task);

        monitor.monitorContainers();

        ArgumentCaptor<ContainerTask> saved = ArgumentCaptor.forClass(ContainerTask.class);
        verify(containerTaskRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ContainerTaskStatus.RETRYING);
        assertThat(saved.getValue().getFailureType()).isEqualTo(ContainerFailureType.CODE);
        verify(poolManager).releaseSlot();
        // Changes must NOT be cleared on failure — only on success
        verify(architectBriefRepository, never()).updateRequestedChanges(any(), any());
    }

    // ── Config auth failure — non-retryable ───────────────────────────────

    @Test
    void monitorContainers_configAuthFailure_taskFailedImmediately() {
        ContainerTask task = runningTask();
        when(containerTaskRepository.findByStatus(ContainerTaskStatus.RUNNING))
                .thenReturn(List.of(task));
        when(dockerContainerService.isContainerRunning(CONTAINER_ID)).thenReturn(false);
        when(dockerContainerService.getExitCode(CONTAINER_ID)).thenReturn(1);
        when(dockerContainerService.getContainerLogs(CONTAINER_ID))
                .thenReturn("401 unauthorized — GitHub token invalid");
        when(containerTaskRepository.save(any())).thenReturn(task);
        doNothing().when(agentEventService).log(any(), any(), any(), any(), any(), any());

        monitor.monitorContainers();

        ArgumentCaptor<ContainerTask> saved = ArgumentCaptor.forClass(ContainerTask.class);
        verify(containerTaskRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ContainerTaskStatus.FAILED);
        assertThat(saved.getValue().getFailureType()).isEqualTo(ContainerFailureType.CONFIG_AUTH);
    }
}
