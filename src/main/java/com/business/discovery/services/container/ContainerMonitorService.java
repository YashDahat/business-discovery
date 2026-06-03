package com.business.discovery.services.container;

import com.business.discovery.model.ContainerTask;
import com.business.discovery.model.ContainerTask.ContainerFailureType;
import com.business.discovery.model.ContainerTask.ContainerTaskStatus;
import com.business.discovery.model.MasterAgentHeartbeat;
import com.business.discovery.model.MasterAgentHeartbeat.MasterStatus;
import com.business.discovery.repository.ArchitectBriefRepository;
import com.business.discovery.repository.ContainerTaskRepository;
import com.business.discovery.repository.MasterAgentHeartbeatRepository;
import com.business.discovery.services.agent.AgentEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContainerMonitorService {

    private final ContainerTaskRepository containerTaskRepository;
    private final MasterAgentHeartbeatRepository heartbeatRepository;
    private final DockerContainerService dockerContainerService;
    private final ContainerPoolManager poolManager;
    private final AgentEventService agentEventService;
    private final ArchitectBriefRepository architectBriefRepository;

    @Value("${docker.container.max-lifetime-minutes:30}")
    private int maxLifetimeMinutes;

    // ─── Heartbeat — runs every 60 seconds ────────────────

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void updateHeartbeat() {
        MasterAgentHeartbeat heartbeat = heartbeatRepository.findById(1)
                .orElse(MasterAgentHeartbeat.builder().id(1).build());

        heartbeat.setLastHeartbeat(LocalDateTime.now());
        heartbeat.setStatus(MasterStatus.RUNNING);
        heartbeat.setActiveContainers(poolManager.activeSlots());
        heartbeatRepository.save(heartbeat);

        log.debug("Heartbeat updated — active containers: {}", poolManager.activeSlots());
    }

    // ─── Monitor loop — runs every 30 seconds ─────────────

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void monitorContainers() {
        List<ContainerTask> runningTasks = containerTaskRepository
                .findByStatus(ContainerTaskStatus.RUNNING);

        if (runningTasks.isEmpty()) {
            log.debug("Monitor: no running containers");
            return;
        }

        log.info("Monitor: checking {} running containers", runningTasks.size());

        for (ContainerTask task : runningTasks) {
            try {
                processRunningTask(task);
            } catch (Exception e) {
                log.error("Monitor: error processing task {}: {}", task.getId(), e.getMessage());
            }
        }
    }

    private void processRunningTask(ContainerTask task) {
        String containerId = task.getDockerContainerId();

        if (containerId == null) {
            log.warn("Monitor: task {} has no containerId — marking failed", task.getId());
            handleFailure(task, "No container ID — orphaned task", ContainerFailureType.INFRA);
            return;
        }

        // Check if container exceeded max lifetime
        if (task.isTimedOut(maxLifetimeMinutes)) {
            log.warn("Monitor: task {} timed out after {} minutes — killing",
                    task.getId(), maxLifetimeMinutes);
            dockerContainerService.killContainer(containerId);
            handleFailure(task,
                    "Container exceeded max lifetime of " + maxLifetimeMinutes + " minutes",
                    ContainerFailureType.INFRA);
            return;
        }

        // Check if container is still running — network errors skip this cycle
        boolean running;
        try {
            running = dockerContainerService.isContainerRunning(containerId);
        } catch (Exception e) {
            log.warn("Monitor: Docker API unreachable for container {} — skipping cycle: {}",
                    containerId.substring(0, 12), e.getMessage());
            return;
        }

        if (running) {
            log.debug("Monitor: container {} still running for task {}",
                    containerId.substring(0, 12), task.getId());
            return;
        }

        // Container has exited — check exit code
        Integer exitCode = dockerContainerService.getExitCode(containerId);
        log.info("Monitor: container {} exited with code {} for task {}",
                containerId.substring(0, 12), exitCode, task.getId());

        if (exitCode != null && exitCode == 0) {
            handleSuccess(task, containerId);
            // Remove only on success — failed containers are kept for inspection
            dockerContainerService.removeContainer(containerId);
        } else {
            String logs = dockerContainerService.getContainerLogs(containerId);
            ContainerFailureType failureType = classifyFailure(logs, exitCode);
            handleFailure(task, logs, failureType);
            // Keep failed container for manual inspection — retrieve logs via:
            // GET /api/v3/containers/tasks/{taskId}/logs
            // Remove manually after inspection via:
            // POST /api/v3/containers/cleanup/all
            log.warn("[MONITOR] Container {} kept for inspection (exit code {}) — task {}",
                    containerId.substring(0, 12), exitCode, task.getId());
        }

        // OLD: removed unconditionally — now handled per branch above
        // dockerContainerService.removeContainer(containerId);
    }

    // ─── Success handler ──────────────────────────────────

    private void handleSuccess(ContainerTask task, String containerId) {
        task.setStatus(ContainerTaskStatus.COMPLETED);
        task.setCompletedAt(LocalDateTime.now());
        containerTaskRepository.save(task);

        // Clear requested_changes — change cycle is done; null = no cycle in flight
        architectBriefRepository.updateRequestedChanges(task.getBriefId(), null);

        poolManager.releaseSlot();

        log.info("[SUCCESS] Task completed — taskId: {}, briefId: {}",
                task.getId(), task.getBriefId());

        agentEventService.log(
                task.getRunId(), "CONTAINER_MONITOR",
                com.business.discovery.model.AgentEvent.EventType.STEP_COMPLETED,
                "Container task completed — PR created",
                java.util.Map.of(
                        "task_id", task.getId().toString(),
                        "brief_id", task.getBriefId().toString(),
                        "pr_url", task.getGithubPrUrl() != null ? task.getGithubPrUrl() : "N/A"
                ),
                null
        );
    }

    // ─── Failure handler ──────────────────────────────────

    private void handleFailure(ContainerTask task, String errorContext,
                               ContainerFailureType failureType) {
        task.setFailureType(failureType);
        task.setContainerLogs(errorContext);

        poolManager.releaseSlot();

        switch (failureType) {

            case CONFIG_AUTH -> {
                // Never retry — alert human immediately
                task.setStatus(ContainerTaskStatus.FAILED);
                task.setErrorMessage("Config/Auth error — human intervention required");
                task.setCompletedAt(LocalDateTime.now());
                containerTaskRepository.save(task);

                log.error("[FAILURE] Config/Auth error for task {} — alerting",
                        task.getId());
                alertHuman(task, errorContext);
            }

            case INFRA -> {
                // Transient — retry if attempts remain
                if (task.canRetry()) {
                    task.setStatus(ContainerTaskStatus.RETRYING);
                    task.setAttemptCount(task.getAttemptCount() + 1);
                    containerTaskRepository.save(task);
                    log.warn("[RETRY] Infra failure — queuing retry {}/{} for task {}",
                            task.getAttemptCount(), task.getMaxAttempts(), task.getId());
                } else {
                    exhaustRetries(task);
                }
            }

            case CODE -> {
                // Code failure — retry with error context fed back to LLM
                if (task.canRetry()) {
                    task.setStatus(ContainerTaskStatus.RETRYING);
                    task.setAttemptCount(task.getAttemptCount() + 1);
                    containerTaskRepository.save(task);
                    log.warn("[RETRY] Code failure — queuing re-generation {}/{} for task {}",
                            task.getAttemptCount(), task.getMaxAttempts(), task.getId());
                } else {
                    exhaustRetries(task);
                }
            }

            default -> exhaustRetries(task);
        }
    }

    private void exhaustRetries(ContainerTask task) {
        task.setStatus(ContainerTaskStatus.FAILED);
        task.setFailureType(ContainerFailureType.MAX_RETRIES);
        task.setErrorMessage("Max retries (" + task.getMaxAttempts() + ") exhausted");
        task.setCompletedAt(LocalDateTime.now());
        containerTaskRepository.save(task);

        log.error("[FAILED] Max retries exhausted for task: {}", task.getId());
        alertHuman(task, "Max retries exhausted — manual review needed");
    }

    // ─── Failure classification ───────────────────────────

    private ContainerFailureType classifyFailure(String logs, Integer exitCode) {
        if (logs == null) return ContainerFailureType.INFRA;

        String lowerLogs = logs.toLowerCase();

        // Config / Auth errors — non-retryable
        if (lowerLogs.contains("authentication failed")
                || lowerLogs.contains("401 unauthorized")
                || lowerLogs.contains("403 forbidden")
                || lowerLogs.contains("bad credentials")
                || lowerLogs.contains("invalid token")
                || lowerLogs.contains("permission denied")) {
            return ContainerFailureType.CONFIG_AUTH;
        }

        // Infrastructure errors — transient, retry as-is
        if (lowerLogs.contains("out of memory")
                || lowerLogs.contains("oom")
                || lowerLogs.contains("timeout")
                || lowerLogs.contains("connection refused")
                || lowerLogs.contains("connection reset")
                || lowerLogs.contains("no space left")
                || lowerLogs.contains("429")
                || lowerLogs.contains("quota")
                || lowerLogs.contains("resource exhausted")
                || lowerLogs.contains("rate limit")) {
            return ContainerFailureType.INFRA;
        }

        // SIGTERM (Docker stop / OOM killer / credits exhausted mid-run)
        if (exitCode != null && exitCode == 143) {
            return ContainerFailureType.INFRA;
        }

        // Code errors — retry with error context fed to LLM
        if (lowerLogs.contains("compilation failed")
                || lowerLogs.contains("build failed")
                || lowerLogs.contains("cannot find symbol")
                || lowerLogs.contains("error: ")
                || lowerLogs.contains("npm err")
                || lowerLogs.contains("failed to compile")) {
            return ContainerFailureType.CODE;
        }

        // Default — treat as code failure if exit code non-zero
        return exitCode != null && exitCode != 0
                ? ContainerFailureType.CODE
                : ContainerFailureType.INFRA;
    }

    // ─── Alert human ──────────────────────────────────────

    private void alertHuman(ContainerTask task, String reason) {
        log.error("ALERT — Human intervention required for task: {} | reason: {}",
                task.getId(), reason);

        agentEventService.log(
                task.getRunId(), "CONTAINER_MONITOR",
                com.business.discovery.model.AgentEvent.EventType.ERROR,
                "Human alert: " + reason,
                java.util.Map.of(
                        "task_id", task.getId().toString(),
                        "failure_type", task.getFailureType().name(),
                        "brief_id", task.getBriefId().toString()
                ),
                null
        );
    }

    // ─── Orphan recovery — runs on startup ────────────────

    @Scheduled(initialDelay = 5_000, fixedDelay = Long.MAX_VALUE)
    public void recoverOrphanedContainers() {
        log.info("Orphan recovery starting...");

        List<ContainerTask> runningTasks = containerTaskRepository
                .findByStatus(ContainerTaskStatus.RUNNING);

        if (runningTasks.isEmpty()) {
            log.info("Orphan recovery: no orphaned tasks found");
            return;
        }

        log.warn("Orphan recovery: found {} potentially orphaned tasks", runningTasks.size());

        for (ContainerTask task : runningTasks) {
            String containerId = task.getDockerContainerId();

            if (containerId == null) {
                log.warn("Orphan: task {} has no container ID — marking as RETRYING", task.getId());
                task.setStatus(ContainerTaskStatus.RETRYING);
                containerTaskRepository.save(task);
                continue;
            }

            boolean running = dockerContainerService.isContainerRunning(containerId);

            if (running) {
                log.info("Orphan: container {} still running — re-adopting for task {}",
                        containerId.substring(0, 12), task.getId());
                // Container is alive — monitor will pick it up on next cycle
            } else {
                log.warn("Orphan: container {} is gone — treating as RETRYING for task {}",
                        containerId.substring(0, 12), task.getId());
                task.setStatus(ContainerTaskStatus.RETRYING);
                task.setAttemptCount(task.getAttemptCount() + 1);
                containerTaskRepository.save(task);
                poolManager.releaseSlot();
            }
        }

        // Update heartbeat to RUNNING after recovery
        MasterAgentHeartbeat heartbeat = heartbeatRepository.findById(1)
                .orElse(MasterAgentHeartbeat.builder().id(1).build());
        heartbeat.setStatus(MasterStatus.RUNNING);
        heartbeat.setLastOrphanCheck(LocalDateTime.now());
        heartbeatRepository.save(heartbeat);

        log.info("Orphan recovery complete");
    }
}