package com.business.discovery.worker.orchestrator;

import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.nodes.WorkerNode;
import com.business.discovery.worker.service.GitService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.util.List;

@Component
@Slf4j
public class WorkerOrchestrator {

    // Total number of WorkerNode beans expected in the pipeline.
    // Update this constant whenever a node is added or removed.
    private static final int EXPECTED_NODE_COUNT = 12;

    private final WorkerContext ctx;
    private final GitService gitService;

    // All WorkerNode beans, ordered by @Order(N). Spring injects them sorted.
    @Autowired
    private List<WorkerNode> nodes;

    public WorkerOrchestrator(WorkerContext ctx, GitService gitService) {
        this.ctx = ctx;
        this.gitService = gitService;
    }

    @PostConstruct
    void validatePipeline() {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalStateException(
                    "[WorkerOrchestrator] No WorkerNode beans found — pipeline cannot run");
        }
        if (nodes.size() != EXPECTED_NODE_COUNT) {
            throw new IllegalStateException(
                    "[WorkerOrchestrator] Expected %d nodes but found %d — a node may have failed to register. Found: %s"
                            .formatted(EXPECTED_NODE_COUNT, nodes.size(), nodeNames()));
        }
        log.info("[WorkerOrchestrator] Pipeline validated — {} nodes in execution order:", nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            WorkerNode node = nodes.get(i);
            Order orderAnn = AnnotationUtils.findAnnotation(node.getClass(), Order.class);
            int order = orderAnn != null ? orderAnn.value() : -1;
            log.info("  [{}/{}] @Order({}) {}", i + 1, nodes.size(), order, node.getClass().getSimpleName());
        }
    }

    private String nodeNames() {
        return nodes.stream()
                .map(n -> n.getClass().getSimpleName())
                .toList()
                .toString();
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
    // Runs only if git was initialised (GitWorkspaceNode completed) and there is something to commit.
    // Swallows its own exceptions — the original failure is what matters.
    private void pushProgressOnFailure(Exception cause) {
        if (!Files.exists(ctx.getWorkspaceDir().resolve(".git"))) {
            log.info("[WorkerOrchestrator] No .git in workspace — nothing to push on failure");
            return;
        }

        // Skip entirely if there is nothing new to commit — avoids the "nothing to commit"
        // non-zero exit that previously swallowed the real error message
        if (!gitService.hasChanges(ctx.getWorkspaceDir())) {
            log.info("[WorkerOrchestrator] Working tree clean — no progress to push on failure");
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
            String msg = pushEx.getMessage() != null ? pushEx.getMessage() : pushEx.getClass().getSimpleName();
            log.warn("[WorkerOrchestrator] Could not push progress on failure: {}", msg);
        }
    }
}
