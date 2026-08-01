package com.business.discovery.worker.nodes;

import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.service.GitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(16)
@Slf4j
@RequiredArgsConstructor
public class GitCommitNode implements WorkerNode {

    private final GitService gitService;

    @Override
    public void execute(WorkerContext ctx) {
        String message = "feat: generate %s website (attempt %d)"
                .formatted(ctx.getBusiness().getTitle(), ctx.getAttemptNumber());

        gitService.addAll(ctx.getWorkspaceDir());
        gitService.commit(ctx.getWorkspaceDir(), message);

        log.info("[GitCommitNode] Committed: {}", message);
    }
}
