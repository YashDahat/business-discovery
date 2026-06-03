package com.business.discovery.services.container;

import com.business.discovery.model.ContainerTask;
import com.business.discovery.model.ContainerTask.ContainerTaskStatus;
import com.business.discovery.repository.ContainerTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContainerQueueProcessor {

    private final ContainerTaskRepository containerTaskRepository;
    private final ContainerPoolManager poolManager;
    private final DockerContainerService dockerContainerService;

    // Runs every 30 seconds — picks up PENDING tasks when slots open
    @Scheduled(fixedDelay = 30_000)
    public void processPendingTasks() {
        if (!poolManager.isSlotAvailable()) {
            log.debug("Queue processor: pool full — skipping");
            return;
        }

        List<ContainerTask> retrying = containerTaskRepository
                .findByStatus(ContainerTaskStatus.RETRYING);

        List<ContainerTask> pending = containerTaskRepository
                .findByStatus(ContainerTaskStatus.PENDING);

        if (retrying.isEmpty() && pending.isEmpty()) {
            log.debug("Queue processor: no pending or retrying tasks");
            return;
        }

        // Process retrying first — they've already waited
        retrying.stream()
                .filter(t -> poolManager.isSlotAvailable())
                .forEach(this::processTask);

        // Then new tasks
        pending.stream()
                .filter(t -> poolManager.isSlotAvailable())
                .forEach(this::processTask);
    }

    private void processTask(ContainerTask task) {
        // OLD: no guard — CONFIG_AUTH tasks could slip through if manually reset to RETRYING
        // New: skip tasks that had a config/auth failure — require human fix first
        if (task.getFailureType() == ContainerTask.ContainerFailureType.CONFIG_AUTH) {
            log.error("[QUEUE] Skipping task {} — CONFIG_AUTH failure requires human intervention",
                    task.getId());
            return;
        }

        try {
            log.info("Queue processor: spawning container for task: {} (attempt {})",
                    task.getId(), task.getAttemptCount() + 1);
            dockerContainerService.spawnContainer(task);
        } catch (Exception e) {
            log.error("Queue processor: failed to spawn task {}: {}",
                    task.getId(), e.getMessage());
        }
    }
}