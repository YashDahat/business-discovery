package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.service.GitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(17)
@Slf4j
@RequiredArgsConstructor
public class GitPushNode implements WorkerNode {

    private final GitService gitService;

    @Override
    public void execute(WorkerContext ctx) {
        String branch = ctx.getGithubBranch();

        try {
            gitService.push(ctx.getWorkspaceDir(), branch);
            log.info("[GitPushNode] Pushed branch '{}'", branch);
            return;
        } catch (WorkerException e) {
            log.warn("[GitPushNode] Push failed — attempting rebase and retry: {}", e.getMessage());
        }

        // Branch diverged — rebase and retry once
        gitService.pullRebase(ctx.getWorkspaceDir(), branch);

        try {
            gitService.push(ctx.getWorkspaceDir(), branch);
            log.info("[GitPushNode] Pushed branch '{}' after rebase", branch);
        } catch (WorkerException e) {
            throw new WorkerException(FailureType.INFRA,
                    "git push failed after rebase retry on branch '" + branch + "': " + e.getMessage(), e);
        }
    }
}
