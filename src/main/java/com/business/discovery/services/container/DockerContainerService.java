package com.business.discovery.services.container;

import com.business.discovery.model.ContainerTask;
import com.business.discovery.repository.ContainerTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerContainerService {

    private final DockerClient dockerClient;
    private final ContainerPoolManager poolManager;
    private final ContainerImageService imageService;
    private final ContainerTaskRepository containerTaskRepository;
    private final ObjectMapper objectMapper;

    @Value("${docker.worker.image}")
    private String workerImage;

    @Value("${docker.network.name:discovery-network}")
    private String networkName;

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUsername;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    @Value("${langchain4j.google-ai-gemini.chat-model.api-key}")
    private String geminiApiKey;

    @Value("${worker.llm.anthropic.api-key:}")
    private String anthropicApiKey;

    @Value("${worker.llm.gemini.pro-model:gemini-2.5-pro}")
    private String geminiProModel;

    @Value("${worker.llm.gemini.flash-model:gemini-2.5-flash}")
    private String geminiFlashModel;

    @Value("${tavily.api-key:}")
    private String tavilyApiKey;

    @Value("${worker.github.owner:}")
    private String githubOwner;

    @Value("${worker.github.token:}")
    private String githubToken;

    // ─── Spawn a sub-container for a task ─────────────────

    @Transactional
    public String spawnContainer(ContainerTask task) {
        imageService.ensureImageAvailable();

        // Lock the task row — prevents ContainerSpawnerNode (HTTP thread) and
        // ContainerQueueProcessor (scheduler thread) from both spawning the same task
        ContainerTask lockedTask = containerTaskRepository.findByIdForUpdate(task.getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Task not found: " + task.getId()));

        if (lockedTask.getStatus() != ContainerTask.ContainerTaskStatus.PENDING
                && lockedTask.getStatus() != ContainerTask.ContainerTaskStatus.RETRYING) {
            log.info("[SPAWN] Task {} already claimed (status: {}) — skipping",
                    task.getId(), lockedTask.getStatus());
            throw new IllegalStateException(
                    "Task already claimed — status: " + lockedTask.getStatus());
        }

        // OLD: no guard on failure type — CONFIG_AUTH tasks could be manually reset and re-spawned
        // New: block spawn if previous failure was a config/auth error — requires human fix first
        if (lockedTask.getFailureType() == ContainerTask.ContainerFailureType.CONFIG_AUTH) {
            log.error("[SPAWN] Refusing to spawn task {} — CONFIG_AUTH failure requires human intervention before retry",
                    task.getId());
            throw new IllegalStateException(
                    "Spawn blocked — task has CONFIG_AUTH failure. Fix credentials before retrying.");
        }

        // Acquire pool slot — also uses a locked read, atomic within this transaction
        boolean acquired = poolManager.tryAcquireSlot();
        if (!acquired) {
            throw new IllegalStateException("No pool slot available — cannot spawn container");
        }

        try {
            String containerId = createAndStartContainer(lockedTask);

            lockedTask.setDockerContainerId(containerId);
            lockedTask.setStatus(ContainerTask.ContainerTaskStatus.RUNNING);
            lockedTask.setSpawnedAt(LocalDateTime.now());
            containerTaskRepository.save(lockedTask);

            log.info("[SPAWN] Container started — taskId: {}, containerId: {}, pool: {}/{}",
                    lockedTask.getId(), containerId,
                    poolManager.activeSlots(), poolManager.poolSize());

            return containerId;

        } catch (Exception e) {
            poolManager.releaseSlot();
            log.error("[SPAWN] Failed to spawn container for task: {}", task.getId(), e);
            throw new RuntimeException("Container spawn failed for task: " + task.getId(), e);
        }
    }

    private String createAndStartContainer(ContainerTask task) throws Exception {
        String containerName = "worker-" + task.getId().toString().substring(0, 8);
        String taskDescJson = objectMapper.writeValueAsString(task.getTaskDescription());

        // Build ENV variables for sub-container
        List<String> envVars = buildEnvVars(task, taskDescJson);

        // Resource constraints
        HostConfig hostConfig = HostConfig.newHostConfig()
                // Memory limit — 1.5GB per container
                .withMemory(1536 * 1024 * 1024L)
                // CPU — 1.0 CPU (1024 shares)
                .withCpuShares(1024)
                // Read-only root filesystem — only /workspace is writable
                .withReadonlyRootfs(false) // false because JVM needs to write temp files
                // Network — attach to discovery network
                .withNetworkMode(networkName)
                // Auto-remove container after exit — keeps Docker clean
                .withAutoRemove(false); // false so we can read logs after exit

        // Create container
        CreateContainerResponse container = dockerClient
                .createContainerCmd(workerImage)
                .withName(containerName)
                .withEnv(envVars)
                .withHostConfig(hostConfig)
                .withLabels(java.util.Map.of(
                        "task_id", task.getId().toString(),
                        "brief_id", task.getBriefId().toString(),
                        "managed_by", "discovery-master"
                ))
                .exec();

        String containerId = container.getId();
        log.info("[SPAWN] Container created — name: {}, id: {}", containerName, containerId);

        // Start the container
        dockerClient.startContainerCmd(containerId).exec();
        log.info("[SPAWN] Container started — id: {}", containerId);

        return containerId;
    }

    private List<String> buildEnvVars(ContainerTask task, String taskDescJson) {
        List<String> env = new ArrayList<>();

        // Task identification
        env.add("TASK_ID=" + task.getId().toString());
        env.add("BRIEF_ID=" + task.getBriefId().toString());
        env.add("BUSINESS_ID=" + task.getBusinessId().toString());
        env.add("ATTEMPT_NUMBER=" + (task.getAttemptCount() + 1));

        // Task description — the ArchitectBrief JSON
        env.add("TASK_DESC=" + taskDescJson);

        // GitHub credentials
        env.add("GITHUB_REPO_URL=" + (task.getGithubRepoUrl() != null ? task.getGithubRepoUrl() : ""));
        env.add("GITHUB_BRANCH=" + task.getGithubBranch());
        env.add("GITHUB_TOKEN=" + resolveGitHubToken(task));
        env.add("GITHUB_OWNER=" + githubOwner);

        // Database — worker reads ArchitectBrief and writes GeneratedFile records
        env.add("SPRING_DATASOURCE_URL=" + dbUrl);
        env.add("SPRING_DATASOURCE_USERNAME=" + dbUsername);
        env.add("SPRING_DATASOURCE_PASSWORD=" + dbPassword);

        // LLM
        env.add("GEMINI_API_KEY=" + geminiApiKey);
        env.add("GEMINI_PRO_MODEL=" + geminiProModel);
        env.add("GEMINI_FLASH_MODEL=" + geminiFlashModel);
        env.add("ANTHROPIC_API_KEY=" + anthropicApiKey);
        env.add("TAVILY_API_KEY=" + tavilyApiKey);

        return env;
    }

    private String resolveGitHubToken(ContainerTask task) {
        if (task.getGithubTokenExpiresAt() != null
                && task.getGithubTokenExpiresAt().isBefore(LocalDateTime.now())) {
            log.warn("[SPAWN] GitHub token may be expired for task: {}", task.getId());
        }
        return githubToken;
    }

    // ─── Stop a running container ─────────────────────────

    public void stopContainer(String containerId) {
        try {
            dockerClient.stopContainerCmd(containerId)
                    .withTimeout(30) // 30 second graceful shutdown
                    .exec();
            log.info("[STOP] Container stopped: {}", containerId);
        } catch (Exception e) {
            log.warn("[STOP] Failed to stop container gracefully — killing: {}", containerId);
            killContainer(containerId);
        }
    }

    // ─── Force kill a container ───────────────────────────

    public void killContainer(String containerId) {
        try {
            dockerClient.killContainerCmd(containerId).exec();
            log.info("[KILL] Container killed: {}", containerId);
        } catch (Exception e) {
            log.warn("[KILL] Failed to kill container {}: {}", containerId, e.getMessage());
        }
    }

    // ─── Remove a container ───────────────────────────────

    public void removeContainer(String containerId) {
        try {
            dockerClient.removeContainerCmd(containerId)
                    .withForce(true)
                    .withRemoveVolumes(true)
                    .exec();
            log.info("[REMOVE] Container removed: {}", containerId);
        } catch (Exception e) {
            log.warn("[REMOVE] Failed to remove container {}: {}", containerId, e.getMessage());
        }
    }

    // ─── Inspect container state ──────────────────────────

    public InspectContainerResponse inspectContainer(String containerId) {
        return dockerClient.inspectContainerCmd(containerId).exec();
    }

    public boolean isContainerRunning(String containerId) {
        try {
            InspectContainerResponse response = inspectContainer(containerId);
            return Boolean.TRUE.equals(response.getState().getRunning());
        } catch (NotFoundException e) {
            // Container genuinely gone (404) — treat as stopped
            return false;
        }
        // Any other exception (NoHttpResponseException, network error) bubbles up
        // so the monitor skips this cycle rather than treating it as a stopped container
    }

    public Integer getExitCode(String containerId) {
        try {
            InspectContainerResponse response = inspectContainer(containerId);
            return response.getState().getExitCodeLong() != null
                    ? response.getState().getExitCodeLong().intValue()
                    : null;
        } catch (Exception e) {
            log.warn("[INSPECT] Could not get exit code for container: {}", containerId);
            return null;
        }
    }

    // ─── Read container logs ──────────────────────────────

    public String getContainerLogs(String containerId) {
        StringBuilder logs = new StringBuilder();
        try {
            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTail(500) // last 500 lines — enough for error context
                    .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            logs.append(new String(frame.getPayload()));
                        }
                    })
                    .awaitCompletion(30, java.util.concurrent.TimeUnit.SECONDS);

        } catch (Exception e) {
            log.warn("[LOGS] Failed to retrieve logs for container {}: {}", containerId, e.getMessage());
        }
        return logs.toString();
    }

    public void onShutdown() {
        log.warn("Master agent shutting down — stopping all running containers");

        List<ContainerTask> running = containerTaskRepository
                .findByStatus(ContainerTask.ContainerTaskStatus.RUNNING);

        running.forEach(task -> {
            if (task.getDockerContainerId() != null) {
                log.info("Stopping container on shutdown: {}", task.getDockerContainerId());
                stopContainer(task.getDockerContainerId());
                removeContainer(task.getDockerContainerId());
            }
            task.setStatus(ContainerTask.ContainerTaskStatus.RETRYING);
            task.setErrorMessage("Master agent shutdown — will retry on restart");
            containerTaskRepository.save(task);
        });

        poolManager.releaseAllSlots();
        log.info("Shutdown cleanup complete — {} containers stopped", running.size());
    }
}