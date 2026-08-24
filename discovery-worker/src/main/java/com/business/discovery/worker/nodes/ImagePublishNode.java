package com.business.discovery.worker.nodes;

import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.repository.ContainerTaskRepository;
import com.business.discovery.worker.service.GitService;
import com.business.discovery.worker.util.GhcrImageRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Publishes the smoke-tested image to GHCR after the code is pushed, so the demo
 * runs the exact artifact that passed the gates — pulled, never rebuilt.
 *
 * Tags pushed: :<shortCommitSha> (immutable, traceable to the PR) and :demo
 * (rolling "latest demoable" — what DemoService pulls by default).
 *
 * Non-fatal by design: a registry hiccup must not fail a task whose code PR is
 * already valid. Publish is re-runnable; failures are logged and the task proceeds
 * without a published_image (the demo button stays disabled for this attempt).
 *
 * Requires GITHUB_TOKEN with the `write:packages` scope (documented in README/.env.example).
 */
@Component
@Order(18)
@Slf4j
public class ImagePublishNode implements WorkerNode {

    private static final int PUSH_TIMEOUT_MINUTES = 5;

    private final GitService gitService;
    private final ContainerTaskRepository taskRepo;

    @Value("${worker.docker.host:}")
    private String dockerHost;

    public ImagePublishNode(GitService gitService, ContainerTaskRepository taskRepo) {
        this.gitService = gitService;
        this.taskRepo = taskRepo;
    }

    @Override
    public void execute(WorkerContext ctx) {
        String smokeImage = ctx.getSmokeTestedImage();
        if (smokeImage == null || smokeImage.isBlank()) {
            log.info("[ImagePublishNode] No smoke-tested image on context — skipping publish");
            return;
        }

        Path workspace = ctx.getWorkspaceDir();
        String shortSha = gitService.getShortCommitSha(workspace);
        String shaTag  = GhcrImageRef.retag(smokeImage, shortSha != null ? shortSha : "attempt-" + ctx.getAttemptNumber());
        String demoTag = GhcrImageRef.retag(smokeImage, "demo");

        try {
            if (!login(ctx)) return;

            tag(workspace, smokeImage, shaTag);
            tag(workspace, smokeImage, demoTag);

            boolean shaPushed  = push(workspace, shaTag);
            boolean demoPushed = push(workspace, demoTag);

            if (shaPushed && demoPushed) {
                taskRepo.updatePublishedImage(ctx.getTaskId(), shaTag);
                ctx.setSmokeTestedImage(shaTag);
                log.info("[ImagePublishNode] Published {} and {}", shaTag, demoTag);
            } else {
                log.warn("[ImagePublishNode] Publish incomplete (sha={} demo={}) — task continues without published image",
                        shaPushed, demoPushed);
            }
        } catch (Exception e) {
            log.warn("[ImagePublishNode] Publish failed (non-fatal): {}", e.getMessage());
        }
    }

    // ── Docker CLI wrappers ────────────────────────────────────────────────

    private boolean login(WorkerContext ctx) throws IOException, InterruptedException {
        String token = ctx.getGithubToken();
        String owner = ctx.getGithubOwner();
        if (token == null || token.isBlank() || owner == null || owner.isBlank()) {
            log.warn("[ImagePublishNode] Missing GitHub token/owner — cannot log in to ghcr.io");
            return false;
        }

        ProcessBuilder pb = docker("login", "ghcr.io", "-u", owner, "--password-stdin");
        Process proc = pb.start();
        try (OutputStream stdin = proc.getOutputStream()) {
            stdin.write(token.getBytes(StandardCharsets.UTF_8));
        }
        String output = new String(proc.getInputStream().readAllBytes());
        proc.waitFor(1, TimeUnit.MINUTES);
        if (proc.exitValue() != 0) {
            log.warn("[ImagePublishNode] ghcr.io login failed (check write:packages scope on PAT): {}",
                    output.lines().limit(3).reduce("", (a, b) -> a + b + " "));
            return false;
        }
        return true;
    }

    private void tag(Path workDir, String source, String target) throws IOException, InterruptedException {
        exec(workDir, 1, "tag", source, target);
    }

    private boolean push(Path workDir, String ref) throws IOException, InterruptedException {
        try {
            exec(workDir, PUSH_TIMEOUT_MINUTES, "push", ref);
            return true;
        } catch (IllegalStateException e) {
            log.warn("[ImagePublishNode] push {} failed: {}", ref, e.getMessage());
            return false;
        }
    }

    private void exec(Path workDir, int timeoutMinutes, String... dockerArgs)
            throws IOException, InterruptedException {
        ProcessBuilder pb = docker(dockerArgs);
        pb.directory(workDir.toFile());
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            proc.destroyForcibly();
            throw new IllegalStateException("docker " + dockerArgs[0] + " timed out after " + timeoutMinutes + "m");
        }
        if (proc.exitValue() != 0) {
            String tail = output.length() > 500 ? output.substring(output.length() - 500) : output;
            throw new IllegalStateException("docker " + dockerArgs[0] + " exit " + proc.exitValue() + ": " + tail);
        }
    }

    private ProcessBuilder docker(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "docker";
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        if (dockerHost != null && !dockerHost.isBlank()) {
            pb.environment().put("DOCKER_HOST", dockerHost);
        }
        return pb;
    }
}
