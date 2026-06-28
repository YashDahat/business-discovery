package com.business.discovery.api;

import com.business.discovery.model.ContainerTask;
import com.business.discovery.model.ContainerTask.ContainerTaskStatus;
import com.business.discovery.model.GeneratedFile;
import com.business.discovery.repository.ContainerTaskRepository;
import com.business.discovery.repository.GeneratedFileRepository;
import com.business.discovery.repository.MasterAgentHeartbeatRepository;
import com.business.discovery.services.container.ContainerImageService;
import com.business.discovery.services.container.ContainerMonitorService;
import com.business.discovery.services.container.ContainerPoolManager;
import com.business.discovery.services.container.DockerContainerService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.HostConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v3/containers")
@RequiredArgsConstructor
public class ContainerTaskController {

    private final ContainerTaskRepository containerTaskRepository;
    private final GeneratedFileRepository generatedFileRepository;
    private final MasterAgentHeartbeatRepository heartbeatRepository;
    private final DockerContainerService dockerContainerService;
    private final ContainerImageService containerImageService;
    private final ContainerPoolManager poolManager;
    private final ContainerMonitorService containerMonitorService;
    private final DockerClient dockerClient;

    // ─────────────────────────────────────────────────────
    // POOL ENDPOINTS
    // ─────────────────────────────────────────────────────

    // GET /api/v3/containers/pool/status
    // Current state of the container pool
    @GetMapping("/pool/status")
    public ResponseEntity<Map<String, Object>> getPoolStatus() {
        return ResponseEntity.ok(Map.of(
                "poolSize", poolManager.poolSize(),
                "activeSlots", poolManager.activeSlots(),
                "availableSlots", poolManager.availableSlots(),
                "isSlotAvailable", poolManager.isSlotAvailable()
        ));
    }

    // ─────────────────────────────────────────────────────
    // IMAGE ENDPOINTS
    // ─────────────────────────────────────────────────────

    // POST /api/v3/containers/image/pull
    // Manually trigger image pull from registry
    @PostMapping("/image/pull")
    public ResponseEntity<Map<String, String>> pullImage() {
        log.info("Manual image pull requested");
        containerImageService.pullImage();
        return ResponseEntity.ok(Map.of(
                "status", "pulled",
                "message", "Worker image pulled successfully"
        ));
    }

    // POST /api/v3/containers/image/refresh
    // Force pull latest image even if exists locally
    @PostMapping("/image/refresh")
    public ResponseEntity<Map<String, String>> refreshImage() {
        log.info("Image refresh requested");
        containerImageService.refreshImage();
        return ResponseEntity.ok(Map.of(
                "status", "refreshed",
                "message", "Worker image refreshed successfully"
        ));
    }

    // GET /api/v3/containers/image/status
    // Check if worker image exists locally
    @GetMapping("/image/status")
    public ResponseEntity<Map<String, Object>> getImageStatus() {
        boolean exists = containerImageService.imageExistsLocally();
        return ResponseEntity.ok(Map.of(
                "imageAvailable", exists,
                "message", exists
                        ? "Worker image is available locally"
                        : "Worker image not found — pull required"
        ));
    }

    // ─────────────────────────────────────────────────────
    // SPAWN ENDPOINTS
    // ─────────────────────────────────────────────────────

    // POST /api/v3/containers/spawn/test
    // Spawn a test container with a dummy task — validates Docker connectivity
    @PostMapping("/spawn/test")
    public ResponseEntity<Map<String, Object>> spawnTestContainer() {
        log.info("Test container spawn requested");

        // Acquire slot HERE — only once
        if (!poolManager.tryAcquireSlot()) {
            return ResponseEntity.status(429).body(Map.of(
                    "status", "POOL_FULL",
                    "message", "No pool slots available",
                    "activeSlots", poolManager.activeSlots(),
                    "poolSize", poolManager.poolSize()
            ));
        }

        ContainerTask testTask = ContainerTask.builder()
                .briefId(UUID.randomUUID())
                .businessId(UUID.randomUUID())
                .runId(UUID.randomUUID())
                .taskDescription(Map.of(
                        "test", true,
                        "description", "Test container spawn",
                        "timestamp", LocalDateTime.now().toString()
                ))
                .githubRepoUrl("https://github.com/test/test-repo")
                .githubBranch("test-branch")
                .status(ContainerTask.ContainerTaskStatus.PENDING)
                .build();

        ContainerTask saved = containerTaskRepository.save(testTask);

        try {
            String containerId = spawnTestWithImage(saved, "alpine:latest");
            return ResponseEntity.accepted().body(Map.of(
                    "status", "SPAWNED",
                    "taskId", saved.getId().toString(),
                    "containerId", containerId,
                    "message", "Test container spawned using alpine:latest",
                    "poolStatus", Map.of(
                            "active", poolManager.activeSlots(),
                            "available", poolManager.availableSlots()
                    )
            ));
        } catch (Exception e) {
            // Release on failure — slot was acquired above
            poolManager.releaseSlot();
            log.error("Spawn failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "FAILED",
                    "taskId", saved.getId().toString(),
                    "error", e.getMessage()
            ));
        }
    }

    private String spawnTestWithImage(ContainerTask task, String image) {
        // NO slot acquisition here — already done by caller

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withMemory(512 * 1024 * 1024L)
                .withNetworkMode("shared-network");

        CreateContainerResponse container = dockerClient
                .createContainerCmd(image)
                .withName("worker-test-" + task.getId().toString().substring(0, 8))
                .withCmd("sleep", "60")
                .withHostConfig(hostConfig)
                .withLabels(Map.of(
                        "task_id", task.getId().toString(),
                        "managed_by", "discovery-master",
                        "test", "true"
                ))
                .exec();

        String containerId = container.getId();
        dockerClient.startContainerCmd(containerId).exec();

        task.setDockerContainerId(containerId);
        task.setStatus(ContainerTask.ContainerTaskStatus.RUNNING);
        task.setSpawnedAt(LocalDateTime.now());
        containerTaskRepository.save(task);

        log.info("[TEST SPAWN] Container started — id: {}", containerId);
        return containerId;
    }

    // POST /api/v3/containers/spawn/{briefId}
    // Spawn a real container for a specific ArchitectBrief
    @PostMapping("/spawn/{briefId}")
    public ResponseEntity<Map<String, Object>> spawnContainer(
            @PathVariable UUID briefId,
            @RequestBody SpawnRequest request) {

        log.info("Container spawn requested for briefId: {}", briefId);

        if (!poolManager.isSlotAvailable()) {
            return ResponseEntity.status(429).body(Map.of(
                    "status", "POOL_FULL",
                    "message", "No pool slots available — task will be queued",
                    "activeSlots", poolManager.activeSlots(),
                    "poolSize", poolManager.poolSize()
            ));
        }

        ContainerTask task = ContainerTask.builder()
                .briefId(briefId)
                .businessId(request.businessId())
                .runId(request.runId())
                .taskDescription(request.taskDescription())
                .githubRepoUrl(request.githubRepoUrl())
                .githubBranch(request.githubBranch())
                .status(ContainerTaskStatus.PENDING)
                .build();

        ContainerTask saved = containerTaskRepository.save(task);

        try {
            String containerId = dockerContainerService.spawnContainer(saved);
            return ResponseEntity.accepted().body(Map.of(
                    "status", "SPAWNED",
                    "taskId", saved.getId().toString(),
                    "containerId", containerId,
                    "poolStatus", Map.of(
                            "active", poolManager.activeSlots(),
                            "available", poolManager.availableSlots()
                    )
            ));
        } catch (Exception e) {
            log.error("Failed to spawn container for brief {}: {}", briefId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "FAILED",
                    "taskId", saved.getId().toString(),
                    "error", e.getMessage()
            ));
        }
    }

    // ─────────────────────────────────────────────────────
    // TASK ENDPOINTS
    // ─────────────────────────────────────────────────────

    // GET /api/v3/containers/tasks
    // List all container tasks — optionally filter by status
    @GetMapping("/tasks")
    public ResponseEntity<List<ContainerTask>> getAllTasks(
            @RequestParam(required = false) ContainerTaskStatus status) {

        if (status != null) {
            return ResponseEntity.ok(containerTaskRepository.findByStatus(status));
        }
        return ResponseEntity.ok(containerTaskRepository.findAll());
    }

    // GET /api/v3/containers/tasks/{taskId}
    // Get a specific task by ID
    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<ContainerTask> getTask(@PathVariable UUID taskId) {
        return containerTaskRepository.findById(taskId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // GET /api/v3/containers/tasks/{taskId}/logs
    // Get container logs for a specific task
    @GetMapping("/tasks/{taskId}/logs")
    public ResponseEntity<Map<String, Object>> getTaskLogs(@PathVariable UUID taskId) {
        Optional<ContainerTask> opt = containerTaskRepository.findById(taskId);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ContainerTask task = opt.get();
        if (task.getContainerLogs() != null) {
            return ResponseEntity.ok(Map.<String, Object>of(
                    "taskId", taskId.toString(),
                    "status", task.getStatus().name(),
                    "source", "stored",
                    "logs", task.getContainerLogs()
            ));
        }
        if (task.getDockerContainerId() != null) {
            String liveLogs = dockerContainerService.getContainerLogs(task.getDockerContainerId());
            return ResponseEntity.ok(Map.<String, Object>of(
                    "taskId", taskId.toString(),
                    "status", task.getStatus().name(),
                    "source", "live",
                    "logs", liveLogs
            ));
        }
        return ResponseEntity.ok(Map.<String, Object>of(
                "taskId", taskId.toString(),
                "status", task.getStatus().name(),
                "logs", "No logs available"
        ));
    }

    // GET /api/v3/containers/tasks/{taskId}/files
    // List all generated files for a task
    @GetMapping("/tasks/{taskId}/files")
    public ResponseEntity<List<GeneratedFile>> getTaskFiles(@PathVariable UUID taskId) {
        return ResponseEntity.ok(generatedFileRepository.findByTaskId(taskId));
    }

    // POST /api/v3/containers/tasks/{taskId}/stop
    // Manually stop a running container
    @PostMapping("/tasks/{taskId}/stop")
    public ResponseEntity<Map<String, String>> stopTask(@PathVariable UUID taskId) {
        return containerTaskRepository.findById(taskId)
                .map(task -> {
                    if (task.getDockerContainerId() == null) {
                        return ResponseEntity.badRequest().body(
                                Map.of("error", "Task has no container ID"));
                    }
                    dockerContainerService.stopContainer(task.getDockerContainerId());
                    task.setStatus(ContainerTaskStatus.FAILED);
                    containerTaskRepository.save(task);
                    poolManager.releaseSlot();
                    return ResponseEntity.ok(Map.of(
                            "taskId", taskId.toString(),
                            "status", "STOPPED"
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // POST /api/v3/containers/tasks/{taskId}/retry
    // Manually retry a failed task
    @PostMapping("/tasks/{taskId}/retry")
    public ResponseEntity<Map<String, Object>> retryTask(@PathVariable UUID taskId) {
        return containerTaskRepository.findById(taskId)
                .map(task -> {
                    if (!task.canRetry()) {
                        return ResponseEntity.badRequest().body(Map.<String, Object>of(
                                "error", "Task cannot be retried",
                                "attemptCount", task.getAttemptCount(),
                                "maxAttempts", task.getMaxAttempts(),
                                "failureType", task.getFailureType() != null
                                        ? task.getFailureType().name() : "N/A"
                        ));
                    }
                    task.setStatus(ContainerTaskStatus.RETRYING);
                    task.setAttemptCount(task.getAttemptCount() + 1);
                    containerTaskRepository.save(task);
                    return ResponseEntity.accepted().body(Map.<String, Object>of(
                            "taskId", taskId.toString(),
                            "status", "RETRYING",
                            "attemptCount", task.getAttemptCount()
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ─────────────────────────────────────────────────────
    // MONITORING ENDPOINTS
    // ─────────────────────────────────────────────────────

    // GET /api/v3/containers/monitor/heartbeat
    // Check master agent heartbeat — is the master alive?
//    @GetMapping("/monitor/heartbeat")
//    public ResponseEntity<Map<String,? extends Serializable>> getHeartbeat() {
//        return heartbeatRepository.findById(1)
//                .map(hb -> ResponseEntity.ok(Map.of(
//                        "status", hb.getStatus().name(),
//                        "lastHeartbeat", hb.getLastHeartbeat().toString(),
//                        "activeContainers", hb.getActiveContainers(),
//                        "lastOrphanCheck", hb.getLastOrphanCheck() != null
//                                ? hb.getLastOrphanCheck().toString() : "never",
//                        "isStale", hb.isStale(120) // stale if no heartbeat in 2 mins
//                )))
//                .orElse(ResponseEntity.ok(Map.of(
//                        "status", "NOT_STARTED",
//                        "message", "Master agent has not started yet"
//                )));
//    }

    // POST /api/v3/containers/monitor/scan
    // Manually trigger monitor scan — don't wait for the 30s scheduler
    @PostMapping("/monitor/scan")
    public ResponseEntity<Map<String, String>> triggerScan() {
        log.info("Manual monitor scan triggered");
        containerMonitorService.monitorContainers();
        return ResponseEntity.ok(Map.of(
                "status", "SCANNED",
                "message", "Monitor scan completed"
        ));
    }

    // POST /api/v3/containers/monitor/recover
    // Manually trigger orphan recovery
    @PostMapping("/monitor/recover")
    public ResponseEntity<Map<String, String>> triggerOrphanRecovery() {
        log.info("Manual orphan recovery triggered");
        containerMonitorService.recoverOrphanedContainers();
        return ResponseEntity.ok(Map.of(
                "status", "RECOVERED",
                "message", "Orphan recovery completed"
        ));
    }

    // ─────────────────────────────────────────────────────
    // DATABASE TEST ENDPOINTS
    // ─────────────────────────────────────────────────────

    // GET /api/v3/containers/db/stats
    // Quick DB health check — counts across all tables
    @GetMapping("/db/stats")
    public ResponseEntity<Map<String, Object>> getDbStats() {
        return ResponseEntity.ok(Map.of(
                "containerTasks", Map.of(
                        "total", containerTaskRepository.count(),
                        "pending", containerTaskRepository.countByStatus(ContainerTaskStatus.PENDING),
                        "running", containerTaskRepository.countByStatus(ContainerTaskStatus.RUNNING),
                        "completed", containerTaskRepository.countByStatus(ContainerTaskStatus.COMPLETED),
                        "failed", containerTaskRepository.countByStatus(ContainerTaskStatus.FAILED),
                        "retrying", containerTaskRepository.countByStatus(ContainerTaskStatus.RETRYING)
                ),
                "generatedFiles", Map.of(
                        "total", generatedFileRepository.count()
                ),
                "poolStatus", Map.of(
                        "size", poolManager.poolSize(),
                        "active", poolManager.activeSlots(),
                        "available", poolManager.availableSlots()
                )
        ));
    }

    @PostMapping("/cleanup/all")
    public ResponseEntity<Map<String, Object>> cleanupAllContainers() {
        log.info("Manual cleanup requested — stopping all running containers");

        List<ContainerTask> running = containerTaskRepository
                .findByStatus(ContainerTask.ContainerTaskStatus.RUNNING);

        running.forEach(task -> {
            if (task.getDockerContainerId() != null) {
                dockerContainerService.stopContainer(task.getDockerContainerId());
                dockerContainerService.removeContainer(task.getDockerContainerId());
            }
            task.setStatus(ContainerTask.ContainerTaskStatus.FAILED);
            task.setErrorMessage("Manually cleaned up");
            containerTaskRepository.save(task);
        });

        poolManager.releaseAllSlots();

        return ResponseEntity.ok(Map.of(
                "stopped", running.size(),
                "message", running.size() + " containers stopped and removed",
                "poolStatus", Map.of(
                        "active", poolManager.activeSlots(),
                        "available", poolManager.availableSlots()
                )
        ));
    }

    // ─────────────────────────────────────────────────────
    // RESPAWN ENDPOINT
    // ─────────────────────────────────────────────────────

    // POST /api/v3/containers/brief/{briefId}/respawn
    // Resets the latest task for a brief back to PENDING for re-spawning.
    // Blocked only when the task is already active: PENDING, RETRYING, or RUNNING.
    @PostMapping("/brief/{briefId}/respawn")
    public ResponseEntity<Map<String, Object>> respawnForBrief(@PathVariable UUID briefId) {

        List<ContainerTask> tasks = containerTaskRepository.findByBriefId(briefId);
        if (tasks.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ContainerTask task = tasks.stream()
                .max(java.util.Comparator.comparing(ContainerTask::getCreatedAt))
                .orElseThrow();

        if (task.getStatus() == ContainerTaskStatus.PENDING
                || task.getStatus() == ContainerTaskStatus.RETRYING
                || task.getStatus() == ContainerTaskStatus.RUNNING) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Task is already active — respawn not allowed",
                    "taskId", task.getId().toString(),
                    "status", task.getStatus().name()
            ));
        }

        ContainerTaskStatus previousStatus = task.getStatus();

        task.setStatus(ContainerTaskStatus.PENDING);
        task.setAttemptCount(0);
        task.setFailureType(null);
        task.setErrorMessage(null);
        task.setContainerLogs(null);
        task.setDockerContainerId(null);
        task.setSpawnedAt(null);
        task.setCompletedAt(null);
        containerTaskRepository.save(task);

        log.info("Brief {} re-queued for respawn — taskId: {}, previousStatus: {}",
                briefId, task.getId(), previousStatus);

        return ResponseEntity.accepted().body(Map.of(
                "status", "QUEUED_FOR_RESPAWN",
                "taskId", task.getId().toString(),
                "briefId", briefId.toString(),
                "previousStatus", previousStatus.name(),
                "message", "Task reset to PENDING — container will spawn on the next queue processor tick (~30s)"
        ));
    }

    // ─────────────────────────────────────────────────────
    // REQUEST RECORDS
    // ─────────────────────────────────────────────────────

    public record SpawnRequest(
            UUID businessId,
            UUID runId,
            String githubRepoUrl,
            String githubBranch,
            Map<String, Object> taskDescription
    ) {}
}