package com.business.discovery.agents.coder.nodes;

import com.business.discovery.agents.coder.CoderAgentState;
import com.business.discovery.model.ContainerTask;
import com.business.discovery.model.ContainerTask.ContainerTaskStatus;
import com.business.discovery.repository.ContainerTaskRepository;
import com.business.discovery.services.agent.AgentEventService;
import com.business.discovery.services.container.ContainerPoolManager;
import com.business.discovery.services.container.DockerContainerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class ContainerSpawnerNode implements NodeAction<CoderAgentState> {

    private static final String NODE_NAME = "CONTAINER_SPAWNER";

    private final ContainerTaskRepository containerTaskRepository;
    private final ContainerPoolManager poolManager;
    private final DockerContainerService dockerContainerService;
    private final AgentEventService eventService;

    @Override
    public Map<String, Object> apply(CoderAgentState state) {
        UUID runId = UUID.fromString(state.runId().orElseThrow());

        // If task was skipped by TaskManagerNode — pass through
        if (state.skipReason().isPresent()) {
            log.info("[{}] Skipping spawn — reason: {}",
                    NODE_NAME, state.skipReason().get());
            return Map.of();
        }

        // If error from previous node — pass through
        if (state.error().isPresent()) {
            log.warn("[{}] Skipping spawn — upstream error: {}",
                    NODE_NAME, state.error().get());
            return Map.of();
        }

        String taskId = state.taskId()
                .orElseThrow(() -> new IllegalStateException(
                        "No taskId in state — TaskManagerNode must run first"));

        UUID taskUUID = UUID.fromString(taskId);
        log.info("[{}] Processing taskId: {}", NODE_NAME, taskId);
        eventService.stepStarted(runId, NODE_NAME);

        try {
            ContainerTask task = containerTaskRepository.findById(taskUUID)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Task not found: " + taskId));

            // ── Pool full — park task, return immediately ──────
            // The ContainerQueueProcessor will pick it up when a slot opens
            if (!poolManager.isSlotAvailable()) {
                log.info("[{}] Pool full ({}/{}) — task {} parked as PENDING",
                        NODE_NAME,
                        poolManager.activeSlots(),
                        poolManager.poolSize(),
                        taskId);

                // Task stays PENDING — queue processor handles it
                return Map.of(
                        CoderAgentState.TASK_STATUS,
                        ContainerTaskStatus.PENDING.name()
                );
            }

            // ── Spawn the container ────────────────────────────
            log.info("[{}] Pool has slot — spawning container for task: {}",
                    NODE_NAME, taskId);

            eventService.toolCall(runId, NODE_NAME, "DockerContainerService",
                    Map.of("taskId", taskId,
                            "poolBefore", poolManager.activeSlots() + "/" +
                                    poolManager.poolSize()));

            String containerId = dockerContainerService.spawnContainer(task);

            log.info("[{}] Container spawned — taskId: {}, containerId: {}",
                    NODE_NAME, taskId, containerId);

            eventService.stepCompleted(runId, NODE_NAME, 0L);

            return Map.of(
                    CoderAgentState.CONTAINER_ID, containerId,
                    CoderAgentState.TASK_STATUS, ContainerTaskStatus.RUNNING.name()
            );

        } catch (Exception e) {
            log.error("[{}] Failed to spawn for task: {}", NODE_NAME, taskId, e);
            eventService.stepFailed(runId, NODE_NAME, e.getMessage());
            return Map.of(CoderAgentState.ERROR, e.getMessage());
        }
    }
}