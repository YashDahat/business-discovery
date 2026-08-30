package com.business.discovery.services.sandbox;

import com.business.discovery.model.SandboxInstance;
import com.business.discovery.model.SandboxInstance.SandboxStatus;
import com.business.discovery.repository.SandboxInstanceRepository;
import com.business.discovery.services.cline.RepoScopeResolver;
import com.business.discovery.services.cline.RepoScopeResolver.RepoScope;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.HostConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the lifecycle of the per-brief Cline execution sandbox: reuse a live one or spawn a fresh
 * keep-alive container (from {@code sandbox.image}) and clone the project's working branch into
 * {@code /workspace}. Mirrors the spawn shape of {@code DockerContainerService.createAndStartContainer}
 * (resource-limited, on the shared network, labelled) but the container runs {@code sleep infinity} and
 * is driven entirely via {@link SandboxExecService}. Idle instances are reaped by {@code SandboxReaper}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxManager {

    private static final EnumSet<SandboxStatus> LIVE = EnumSet.of(SandboxStatus.READY, SandboxStatus.CREATING);

    private final DockerClient dockerClient;
    private final SandboxInstanceRepository sandboxRepository;
    private final RepoScopeResolver repoScopeResolver;
    private final SandboxExecService exec;

    @Value("${sandbox.image:discovery-sandbox:latest}")
    private String sandboxImage;

    // Isolated network for sandbox containers — NOT shared-network (so docker-proxy + postgres are
    // unreachable from inside the sandbox). Plain bridge → still has internet egress for git/npm/pip.
    @Value("${sandbox.network.name:sandbox-network}")
    private String networkName;

    @Value("${sandbox.max-instances:2}")
    private int maxInstances;

    @Value("${sandbox.memory-mb:2048}")
    private long memoryMb;

    @Value("${sandbox.pids-limit:512}")
    private long pidsLimit;

    @Value("${worker.github.token:}")
    private String githubToken;

    @Value("${cline.repo.working-branch:cline/edits}")
    private String defaultWorkingBranch;

    // A checkout/branch name we allow through to git — no shell metacharacters.
    private static final java.util.regex.Pattern SAFE_BRANCH =
            java.util.regex.Pattern.compile("[A-Za-z0-9._/-]{1,255}");

    // Serialise create/reuse per brief so two concurrent turns don't spawn duplicate sandboxes.
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();

    /** Return a READY sandbox's container id for this brief, creating (and cloning) one if needed. */
    public String ensureReady(UUID briefId) {
        Object lock = locks.computeIfAbsent(briefId, k -> new Object());
        synchronized (lock) {
            Optional<SandboxInstance> existing =
                    sandboxRepository.findFirstByBriefIdAndStatusIn(briefId, EnumSet.of(SandboxStatus.READY));
            if (existing.isPresent() && isRunning(existing.get().getContainerId())) {
                SandboxInstance s = existing.get();
                s.setLastUsedAt(LocalDateTime.now());
                sandboxRepository.save(s);
                return s.getContainerId();
            }
            existing.ifPresent(this::markStopped); // row said READY but the container is gone

            // Preserve the last-used branch across recreation so a checked-out branch survives an
            // idle-reap / container death (defaults to the working branch on first ever create).
            String branch = sandboxRepository.findFirstByBriefIdOrderByCreatedAtDesc(briefId)
                    .map(SandboxInstance::getBranch)
                    .filter(b -> b != null && !b.isBlank())
                    .orElse(defaultWorkingBranch);
            return create(briefId, branch).getContainerId();
        }
    }

    public Optional<SandboxInstance> current(UUID briefId) {
        return sandboxRepository.findFirstByBriefIdAndStatusIn(briefId, LIVE);
    }

    /** The branch this brief's sandbox is on (or would be), without creating a sandbox. */
    public String currentBranch(UUID briefId) {
        return sandboxRepository.findFirstByBriefIdOrderByCreatedAtDesc(briefId)
                .map(SandboxInstance::getBranch)
                .filter(b -> b != null && !b.isBlank())
                .orElse(defaultWorkingBranch);
    }

    /**
     * Check out {@code branch} in this brief's sandbox and record it as the current branch (so commit,
     * PR and any future recreation follow it). {@code createNew} makes/resets a branch at HEAD; otherwise
     * an existing local or remote branch is checked out. Returns the verified branch name from git.
     */
    public String checkout(UUID briefId, String branch, boolean createNew) {
        if (branch == null || !SAFE_BRANCH.matcher(branch.trim()).matches()) {
            throw new IllegalArgumentException("Invalid branch name: " + branch);
        }
        String b = branch.trim();
        String cid = ensureReady(briefId);

        String script = createNew
                ? "git fetch origin --prune >/dev/null 2>&1 || true; git checkout -B \"" + b + "\""
                : "git fetch origin --prune >/dev/null 2>&1; "
                    + "if git show-ref --verify --quiet \"refs/heads/" + b + "\"; then git checkout \"" + b + "\"; "
                    + "elif git ls-remote --exit-code --heads origin \"" + b + "\" >/dev/null 2>&1; then "
                    + "git checkout -B \"" + b + "\" \"origin/" + b + "\"; "
                    + "else echo __NO_SUCH_BRANCH__; exit 3; fi";

        SandboxExecService.ExecResult r = exec.exec(cid, script);
        if (r.stdout().contains("__NO_SUCH_BRANCH__")) {
            throw new SandboxExecService.SandboxException("Branch not found locally or on origin: " + b
                    + " (pass createNew=true to start it from the current HEAD)");
        }
        if (r.exitCode() != 0) {
            throw new SandboxExecService.SandboxException("checkout '" + b + "' failed: " + redact(r.combined()));
        }

        String actual = exec.exec(cid, "git rev-parse --abbrev-ref HEAD").stdout().trim();
        current(briefId).ifPresent(inst -> {
            inst.setBranch(actual);
            inst.setLastUsedAt(LocalDateTime.now());
            sandboxRepository.save(inst);
        });
        log.info("[Sandbox] Checked out branch '{}' for brief {}", actual, briefId);
        return actual;
    }

    /**
     * Pull the latest commits for the sandbox's current branch from origin (rebasing any local commits
     * on top). No-op if the branch isn't on origin yet. Returns git's summary. Fails cleanly on conflicts
     * or uncommitted changes so the caller can surface it.
     */
    public String pull(UUID briefId) {
        String cid = ensureReady(briefId);
        String branch = currentBranch(briefId);
        String script =
                "if git ls-remote --exit-code --heads origin \"" + branch + "\" >/dev/null 2>&1; "
                + "then git pull --rebase origin \"" + branch + "\"; else echo __NO_REMOTE_BRANCH__; fi";
        SandboxExecService.ExecResult r = exec.exec(cid, script);
        if (r.stdout().contains("__NO_REMOTE_BRANCH__")) {
            return "Branch '" + branch + "' is not on origin yet — nothing to pull.";
        }
        if (r.exitCode() != 0) {
            throw new SandboxExecService.SandboxException(
                    "pull failed on '" + branch + "': " + redact(r.combined()));
        }
        log.info("[Sandbox] Pulled latest for branch '{}' (brief {})", branch, briefId);
        String out = r.combined().trim();
        return out.isBlank() ? "Already up to date." : out;
    }

    // ── Create + clone ────────────────────────────────────────────────────

    private SandboxInstance create(UUID briefId, String branch) {
        RepoScope scope = repoScopeResolver.resolve(briefId);
        if (!scope.hasRepo()) {
            throw new IllegalStateException(
                    "No repository for this project yet — call create_repo first.");
        }
        if (sandboxRepository.countByStatusIn(LIVE) >= maxInstances) {
            throw new IllegalStateException(
                    "Sandbox capacity reached (" + maxInstances + "). Try again once one is reaped.");
        }
        ensureImagePresent();
        ensureNetwork();

        SandboxInstance instance = sandboxRepository.save(SandboxInstance.builder()
                .briefId(briefId)
                .status(SandboxStatus.CREATING)
                .repoUrl(scope.repoUrl())
                .branch(branch)
                .createdAt(LocalDateTime.now())
                .lastUsedAt(LocalDateTime.now())
                .build());

        try {
            String containerName = "sandbox-" + briefId.toString().substring(0, 8);
            String containerId = startContainer(containerName, briefId);
            instance.setContainerId(containerId);
            instance.setContainerName(containerName);
            sandboxRepository.save(instance);

            cloneRepo(containerId, scope.repoUrl(), branch);

            instance.setStatus(SandboxStatus.READY);
            instance.setLastUsedAt(LocalDateTime.now());
            sandboxRepository.save(instance);
            log.info("[Sandbox] READY for brief {} — container {}, branch {}",
                    briefId, containerId, branch);
            return instance;
        } catch (RuntimeException e) {
            instance.setStatus(SandboxStatus.ERROR);
            instance.setErrorMessage(e.getMessage());
            sandboxRepository.save(instance);
            if (instance.getContainerId() != null) {
                forceRemove(instance.getContainerId());
            }
            throw e;
        }
    }

    private String startContainer(String containerName, UUID briefId) {
        forceRemoveByName(containerName); // clear a stale container from a previous run

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withMemory(memoryMb * 1024 * 1024L)
                .withCpuShares(1024)
                .withPidsLimit(pidsLimit)
                .withNetworkMode(networkName)
                .withAutoRemove(false);

        CreateContainerResponse container = dockerClient.createContainerCmd(sandboxImage)
                .withName(containerName)
                .withHostConfig(hostConfig)
                .withLabels(Map.of(
                        "brief_id", briefId.toString(),
                        "managed_by", "discovery-sandbox"))
                .exec();

        dockerClient.startContainerCmd(container.getId()).exec();
        log.info("[Sandbox] Started container {} ({}) for brief {}", containerName, container.getId(), briefId);
        return container.getId();
    }

    /** Clone the repo into /workspace and check out (or create) the given branch. */
    private void cloneRepo(String containerId, String repoUrl, String branch) {
        String authedUrl = repoUrl.startsWith("https://")
                ? repoUrl.replace("https://", "https://x-access-token:" + githubToken + "@")
                : repoUrl;

        // Values are controlled + shell-safe (GitHub token/url/branch), so interpolating inside double
        // quotes is safe; one sh -lc level (SandboxExecService wraps the command). Clone the default
        // branch, then track the working branch if it exists remotely, else create it.
        String script =
                "git clone \"" + authedUrl + "\" . && "
                + "if git ls-remote --exit-code --heads origin \"" + branch + "\" >/dev/null 2>&1; "
                + "then git checkout \"" + branch + "\"; else git checkout -B \"" + branch + "\"; fi";
        SandboxExecService.ExecResult r = exec.exec(containerId, script);
        if (r.exitCode() != 0) {
            // Don't leak the token in the error surfaced to callers/logs.
            throw new SandboxExecService.SandboxException(
                    "git clone/checkout failed: " + redact(r.combined()));
        }
    }

    // ── Teardown ──────────────────────────────────────────────────────────

    public void destroy(SandboxInstance instance) {
        if (instance.getContainerId() != null) {
            forceRemove(instance.getContainerId());
        }
        markStopped(instance);
        log.info("[Sandbox] Destroyed sandbox {} (brief {})", instance.getContainerName(), instance.getBriefId());
    }

    /** Stop + remove this brief's sandbox if one is live. Returns true if something was stopped. */
    public boolean stopForBrief(UUID briefId) {
        Object lock = locks.computeIfAbsent(briefId, k -> new Object());
        synchronized (lock) {
            Optional<SandboxInstance> live = sandboxRepository.findFirstByBriefIdAndStatusIn(briefId, LIVE);
            if (live.isEmpty()) {
                return false;
            }
            destroy(live.get());
            return true;
        }
    }

    /** Tear down every live sandbox (used by the manual cleanup endpoint). Returns how many. */
    public int destroyAllLive() {
        List<SandboxInstance> live = sandboxRepository.findByStatusIn(LIVE);
        live.forEach(this::destroy);
        return live.size();
    }

    private void markStopped(SandboxInstance instance) {
        instance.setStatus(SandboxStatus.STOPPED);
        sandboxRepository.save(instance);
    }

    // ── Docker helpers ────────────────────────────────────────────────────

    private boolean isRunning(String containerId) {
        if (containerId == null) return false;
        try {
            InspectContainerResponse resp = dockerClient.inspectContainerCmd(containerId).exec();
            return Boolean.TRUE.equals(resp.getState().getRunning());
        } catch (NotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            log.warn("[Sandbox] inspect {} failed: {}", containerId, e.getMessage());
            return false;
        }
    }

    private void ensureImagePresent() {
        try {
            dockerClient.inspectImageCmd(sandboxImage).exec();
        } catch (NotFoundException e) {
            throw new IllegalStateException("Sandbox image '" + sandboxImage
                    + "' not found — build it: docker compose --profile build-only build sandbox");
        }
    }

    /** Create the isolated sandbox network if it doesn't exist yet (plain bridge → internet egress only). */
    private void ensureNetwork() {
        try {
            boolean exists = dockerClient.listNetworksCmd().withNameFilter(networkName).exec().stream()
                    .anyMatch(n -> networkName.equals(n.getName()));
            if (!exists) {
                dockerClient.createNetworkCmd().withName(networkName).withDriver("bridge").exec();
                log.info("[Sandbox] Created isolated network '{}'", networkName);
            }
        } catch (RuntimeException e) {
            // Non-fatal here — if the network truly can't be resolved, createContainer fails with a clear error.
            log.warn("[Sandbox] ensureNetwork('{}') failed: {}", networkName, e.getMessage());
        }
    }

    private void forceRemove(String containerId) {
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (Exception ignored) { /* already gone */ }
    }

    private void forceRemoveByName(String containerName) {
        try {
            dockerClient.removeContainerCmd(containerName).withForce(true).exec();
        } catch (Exception ignored) { /* didn't exist */ }
    }

    private static String redact(String s) {
        return s == null ? "" : s.replaceAll("x-access-token:[^@]+@", "x-access-token:***@");
    }
}
