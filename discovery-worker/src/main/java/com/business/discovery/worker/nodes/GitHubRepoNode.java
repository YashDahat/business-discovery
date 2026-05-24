package com.business.discovery.worker.nodes;

import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.repository.ContainerTaskRepository;
import com.business.discovery.worker.service.GitHubApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(3)
@Slf4j
@RequiredArgsConstructor
public class GitHubRepoNode implements WorkerNode {

    private final GitHubApiService gitHubApiService;
    private final ContainerTaskRepository taskRepo;

    @Override
    public void execute(WorkerContext ctx) {
        if (ctx.getGithubRepoUrl() != null && !ctx.getGithubRepoUrl().isBlank()) {
            log.info("[GitHubRepoNode] Using existing repo URL: {}", ctx.getGithubRepoUrl());
            return;
        }

        String repoName = toRepoName(ctx.getBusiness().getTitle());
        String description = "Auto-generated website for " + ctx.getBusiness().getTitle();

        String repoUrl = gitHubApiService.createRepo(repoName, description);
        ctx.setGithubRepoUrl(repoUrl);
        taskRepo.updateGithubRepoUrl(ctx.getTaskId(), repoUrl);

        log.info("[GitHubRepoNode] Created repo '{}' at {}", repoName, repoUrl);
    }

    static String toRepoName(String businessName) {
        if (businessName == null) return "business-site";
        return businessName.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }
}
