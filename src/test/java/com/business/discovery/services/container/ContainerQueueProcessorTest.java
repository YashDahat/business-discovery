package com.business.discovery.services.container;

import com.business.discovery.model.ContainerTask;
import com.business.discovery.model.ContainerTask.ContainerTaskStatus;
import com.business.discovery.repository.ContainerTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContainerQueueProcessorTest {

    @Mock ContainerTaskRepository containerTaskRepository;
    @Mock ContainerPoolManager poolManager;
    @Mock DockerContainerService dockerContainerService;

    @InjectMocks ContainerQueueProcessor processor;

    private ContainerTask task(ContainerTaskStatus status) {
        return ContainerTask.builder()
                .briefId(UUID.randomUUID())
                .businessId(UUID.randomUUID())
                .runId(UUID.randomUUID())
                .taskDescription(Map.of("test", "true"))
                .status(status)
                .build();
    }

    // ── Pool full ─────────────────────────────────────────────────────────

    @Test
    void processPendingTasks_poolFull_noSpawnAttempted() {
        when(poolManager.isSlotAvailable()).thenReturn(false);

        processor.processPendingTasks();

        verify(containerTaskRepository, never()).findByStatus(any());
        verify(dockerContainerService, never()).spawnContainer(any());
    }

    // ── No tasks queued ───────────────────────────────────────────────────

    @Test
    void processPendingTasks_noQueuedTasks_skips() {
        when(poolManager.isSlotAvailable()).thenReturn(true);
        when(containerTaskRepository.findByStatus(ContainerTaskStatus.RETRYING))
                .thenReturn(List.of());
        when(containerTaskRepository.findByStatus(ContainerTaskStatus.PENDING))
                .thenReturn(List.of());

        processor.processPendingTasks();

        verify(dockerContainerService, never()).spawnContainer(any());
    }

    // ── RETRYING spawned before PENDING ───────────────────────────────────

    @Test
    void processPendingTasks_retryingAndPendingPresent_retryingSpawnedFirst() {
        ContainerTask retrying = task(ContainerTaskStatus.RETRYING);
        ContainerTask pending  = task(ContainerTaskStatus.PENDING);

        when(poolManager.isSlotAvailable()).thenReturn(true);
        when(containerTaskRepository.findByStatus(ContainerTaskStatus.RETRYING))
                .thenReturn(List.of(retrying));
        when(containerTaskRepository.findByStatus(ContainerTaskStatus.PENDING))
                .thenReturn(List.of(pending));

        processor.processPendingTasks();

        InOrder order = inOrder(dockerContainerService);
        order.verify(dockerContainerService).spawnContainer(retrying);
        order.verify(dockerContainerService).spawnContainer(pending);
    }

    // ── Only PENDING ──────────────────────────────────────────────────────

    @Test
    void processPendingTasks_onlyPending_spawned() {
        ContainerTask pending = task(ContainerTaskStatus.PENDING);

        when(poolManager.isSlotAvailable()).thenReturn(true);
        when(containerTaskRepository.findByStatus(ContainerTaskStatus.RETRYING))
                .thenReturn(List.of());
        when(containerTaskRepository.findByStatus(ContainerTaskStatus.PENDING))
                .thenReturn(List.of(pending));

        processor.processPendingTasks();

        verify(dockerContainerService).spawnContainer(pending);
    }

    // ── Spawn failure does not crash the processor ────────────────────────

    @Test
    void processPendingTasks_spawnThrows_otherTasksStillProcessed() {
        ContainerTask failing = task(ContainerTaskStatus.PENDING);
        ContainerTask normal  = task(ContainerTaskStatus.PENDING);

        when(poolManager.isSlotAvailable()).thenReturn(true);
        when(containerTaskRepository.findByStatus(ContainerTaskStatus.RETRYING))
                .thenReturn(List.of());
        when(containerTaskRepository.findByStatus(ContainerTaskStatus.PENDING))
                .thenReturn(List.of(failing, normal));

        doThrow(new RuntimeException("Docker unreachable"))
                .when(dockerContainerService).spawnContainer(failing);

        processor.processPendingTasks();

        verify(dockerContainerService).spawnContainer(failing);
        verify(dockerContainerService).spawnContainer(normal);
    }
}
