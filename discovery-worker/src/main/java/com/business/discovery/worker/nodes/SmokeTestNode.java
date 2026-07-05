package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.repository.ContainerTaskRepository;
import com.business.discovery.worker.service.ComposeLaunchService;
import com.business.discovery.worker.service.ComposeLaunchService.GateReport;
import com.business.discovery.worker.service.ComposeLaunchService.GateResult;
import com.business.discovery.worker.service.ComposeLaunchService.LaunchResult;
import com.business.discovery.worker.service.ComposeLaunchService.LaunchSpec;
import com.business.discovery.worker.util.GhcrImageRef;
import com.business.discovery.worker.util.SlugUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * Runtime gate between compile validation and commit: launches the generated stack
 * with docker compose and asserts the site actually works — boots, serves the SPA,
 * and answers API calls with seeded data.
 *
 * Redefines pipeline success from "code compiles" to "site is demoable". Every gate
 * is a deterministic HTTP assertion — zero LLM cost. On failure the task fails as
 * CODE (or INFRA for docker/daemon problems) and the existing container retry loop
 * regenerates with the failing gate recorded in PROJECT_HISTORY.
 *
 * Failure evidence trail (for post-mortem debugging):
 *  - container_task.failed_gate  — structured gate name (launch/boot/frontend/api-data)
 *  - docs/SMOKE_FAILURE.log      — full compose logs + gate report; rides the failure push
 *  - WorkerException message     — gate + detail + log tail (lands in PROJECT_HISTORY)
 *
 * The compose build tags the app image as ghcr.io/<owner>/<repo>:attempt-<N>; on
 * gate success that tag is recorded on the context for ImagePublishNode to push —
 * the demo later runs the exact image that passed these gates.
 */
@Component
@Order(13)
@Slf4j
public class SmokeTestNode implements WorkerNode {

    static final String FAILURE_LOG = "docs/SMOKE_FAILURE.log";
    private static final int FULL_LOG_TAIL_LINES = 2000;

    private final ComposeLaunchService compose;
    private final ContainerTaskRepository taskRepo;

    @Value("${worker.smoke.enabled:true}")
    private boolean enabled;

    public SmokeTestNode(ComposeLaunchService compose, ContainerTaskRepository taskRepo) {
        this.compose = compose;
        this.taskRepo = taskRepo;
    }

    @Override
    public void execute(WorkerContext ctx) {
        if (!enabled) {
            log.info("[SmokeTestNode] Disabled via worker.smoke.enabled=false — skipping");
            return;
        }

        Path workspace = ctx.getWorkspaceDir();
        String projectName = projectName(ctx);
        String imageRef = GhcrImageRef.build(
                ctx.getGithubOwner(),
                ctx.getGithubRepoUrl(),
                SlugUtil.toSlug(ctx.getBusiness().getTitle()),
                "attempt-" + ctx.getAttemptNumber());

        log.info("[SmokeTestNode] Launching smoke stack '{}' (image {})", projectName, imageRef);

        try {
            LaunchResult launch = compose.launch(workspace, new LaunchSpec(projectName, imageRef, null));
            if (!launch.success()) {
                recordFailure(ctx, workspace, "launch",
                        "docker compose up --build failed", launch.output());
                throw new WorkerException(classifyLaunchFailure(launch.output()),
                        "Smoke gate 'launch' failed (docker compose up --build):\n" + launch.output());
            }

            GateReport report = compose.runGates(launch.baseUrl(), workspace);
            log.info("[SmokeTestNode] Gate report:\n{}", report.summary());

            if (!report.passed()) {
                GateResult failure = report.firstFailure();
                String fullLogs = compose.collectLogs(workspace, projectName, FULL_LOG_TAIL_LINES);
                recordFailure(ctx, workspace, failure.gate(), report.summary(), fullLogs);

                String excerpt = fullLogs.length() > 3000 ? fullLogs.substring(fullLogs.length() - 3000) : fullLogs;
                throw new WorkerException(FailureType.CODE,
                        "Smoke gate '%s' failed: %s\n--- container logs (tail; full log in %s) ---\n%s"
                                .formatted(failure.gate(), failure.detail(), FAILURE_LOG, excerpt));
            }

            markPassed(ctx, workspace);
            ctx.setSmokeTestedImage(imageRef);
            log.info("[SmokeTestNode] All gates passed — image {} is demoable", imageRef);
        } finally {
            compose.down(workspace, projectName, true);
        }
    }

    // ── Failure evidence ───────────────────────────────────────────────────

    /**
     * Persists the structured gate name and writes the full evidence file. The file
     * lives under docs/ (not gitignored) so pushProgressOnFailure carries it to the
     * branch; stale copies from failed attempts are deleted when a later attempt passes.
     */
    private void recordFailure(WorkerContext ctx, Path workspace,
                               String gate, String gateReport, String fullLogs) {
        if (ctx.getTaskId() != null) {
            try {
                taskRepo.updateFailedGate(ctx.getTaskId(), gate);
            } catch (Exception e) {
                log.warn("[SmokeTestNode] Could not persist failed_gate: {}", e.getMessage());
            }
        }
        try {
            Path logFile = workspace.resolve(FAILURE_LOG);
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, """
                    # Smoke Test Failure — attempt %d — %s
                    Failed gate: %s

                    == Gate report ==
                    %s
                    == Container logs (tail %d lines) ==
                    %s
                    """.formatted(ctx.getAttemptNumber(), LocalDateTime.now(), gate,
                    gateReport, FULL_LOG_TAIL_LINES, fullLogs));
            log.info("[SmokeTestNode] Failure evidence written to {}", FAILURE_LOG);
        } catch (IOException e) {
            log.warn("[SmokeTestNode] Could not write {}: {}", FAILURE_LOG, e.getMessage());
        }
    }

    private void markPassed(WorkerContext ctx, Path workspace) {
        if (ctx.getTaskId() != null) {
            try {
                taskRepo.updateFailedGate(ctx.getTaskId(), null);
            } catch (Exception e) {
                log.warn("[SmokeTestNode] Could not clear failed_gate: {}", e.getMessage());
            }
        }
        try {
            // A stale failure log from a previous attempt must not ship in the passing commit
            Files.deleteIfExists(workspace.resolve(FAILURE_LOG));
        } catch (IOException e) {
            log.warn("[SmokeTestNode] Could not remove stale {}: {}", FAILURE_LOG, e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private String projectName(WorkerContext ctx) {
        String taskId = ctx.getTaskIdStr();
        String suffix = (taskId != null && taskId.length() >= 8) ? taskId.substring(0, 8) : "local";
        return "smoke-" + suffix;
    }

    /**
     * Daemon/connectivity problems are INFRA (retry may land on a healthy slot);
     * anything else — a failing image build is almost always broken generated code
     * (frontend build, mvn package) — is CODE so the retry regenerates.
     */
    private FailureType classifyLaunchFailure(String output) {
        String lower = output == null ? "" : output.toLowerCase();
        boolean daemonIssue = lower.contains("cannot connect to the docker daemon")
                || lower.contains("dial tcp")
                || lower.contains("no such host")
                || lower.contains("permission denied while trying to connect")
                || lower.contains("docker: command not found")
                || lower.contains("network shared-network not found");
        return daemonIssue ? FailureType.INFRA : FailureType.CODE;
    }
}
