package com.business.discovery.worker.nodes;

import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.service.GitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@Order(4)
@Slf4j
@RequiredArgsConstructor
public class GitCloneNode implements WorkerNode {

    private final GitService gitService;

    @Override
    public void execute(WorkerContext ctx) {
        Path workspace = ctx.getWorkspaceDir();
        String repoUrl = ctx.getGithubRepoUrl();
        String token = ctx.getGithubToken();
        String branch = ctx.getGithubBranch();

        String authedUrl = repoUrl.replace("https://", "https://x-access-token:" + token + "@");

        gitService.init(workspace);
        gitService.remoteAdd(workspace, "origin", authedUrl);
        gitService.checkout(workspace, branch, true);

        log.info("[GitCloneNode] Initialized git in {} on branch '{}'", workspace, branch);
    }
}
