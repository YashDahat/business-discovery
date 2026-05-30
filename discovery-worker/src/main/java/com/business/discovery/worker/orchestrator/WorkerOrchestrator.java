package com.business.discovery.worker.orchestrator;

import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.nodes.WorkerNode;
import com.business.discovery.worker.service.GitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class WorkerOrchestrator {

    private final WorkerContext ctx;
    private final GitService gitService;

    // Nodes are discovered via component scanning. Each node MUST carry
    // @Order(N) to guarantee execution sequence (1=FileManifest … 14=PullRequest).
    // required=false allows the module to start with zero nodes during early commits.
    @Autowired(required = false)
    private List<WorkerNode> nodes = new ArrayList<>();

    public WorkerOrchestrator(WorkerContext ctx, GitService gitService) {
        this.ctx = ctx;
        this.gitService = gitService;
    }

    public void run() {
        log.info("[worker] Starting — taskId={} briefId={} attempt={}",
                ctx.getTaskIdStr(), ctx.getBriefIdStr(), ctx.getAttemptNumber());

        try {
            for (WorkerNode node : nodes) {
                String nodeName = node.getClass().getSimpleName();
                log.info("[{}] executing", nodeName);
                node.execute(ctx);
                log.info("[{}] completed", nodeName);
            }
            log.info("[worker] All {} nodes completed successfully", nodes.size());
        } catch (Exception e) {
            pushProgressOnFailure(e);
            throw e;
        }
    }

    // Called by the JVM shutdown hook when SIGTERM is received externally.
    public void pushProgressOnShutdown() {
        pushProgressOnFailure(new RuntimeException("container stopped by external signal"));
    }

    // Best-effort: commit + push whatever is in /workspace before the container exits.
    // Runs only if git was initialised (GitWorkspaceNode completed).
    // Swallows its own exceptions — the original failure is what matters.
    private void pushProgressOnFailure(Exception cause) {
        if (!Files.exists(ctx.getWorkspaceDir().resolve(".git"))) {
            log.info("[WorkerOrchestrator] No .git in workspace — nothing to push on failure");
            return;
        }
        try {
            String shortMsg = cause.getMessage() != null
                    ? cause.getMessage().substring(0, Math.min(120, cause.getMessage().length()))
                    : "unknown error";

            gitService.addAll(ctx.getWorkspaceDir());
            gitService.commit(ctx.getWorkspaceDir(),
                    "wip: failed attempt %d — %s".formatted(ctx.getAttemptNumber(), shortMsg));
            gitService.push(ctx.getWorkspaceDir(), ctx.getGithubBranch());

            log.info("[WorkerOrchestrator] Progress pushed to branch '{}' before container exit",
                    ctx.getGithubBranch());
        } catch (Exception pushEx) {
            // commit exits non-zero when nothing is staged (no files generated yet) — expected
            log.warn("[WorkerOrchestrator] Could not push progress on failure: {}", pushEx.getMessage());
        }
    }
}
