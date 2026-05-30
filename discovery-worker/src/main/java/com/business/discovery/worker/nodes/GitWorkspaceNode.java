package com.business.discovery.worker.nodes;

import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.repository.ContainerTaskRepository;
import com.business.discovery.worker.service.GitHubApiService;
import com.business.discovery.worker.service.GitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
@Order(2)
@Slf4j
@RequiredArgsConstructor
public class GitWorkspaceNode implements WorkerNode {

    private static final String HISTORY_PATH = "docs/PROJECT_HISTORY.md";

    private final GitHubApiService gitHubApiService;
    private final ContainerTaskRepository taskRepo;
    private final GitService gitService;

    @Override
    public void execute(WorkerContext ctx) {
        createOrReuseRepo(ctx);
        initWorkspace(ctx);
        loadProjectHistory(ctx);
    }

    // ── Step 1: GitHub repo ───────────────────────────────────────────────

    private void createOrReuseRepo(WorkerContext ctx) {
        String existing = ctx.getGithubRepoUrl();
        if (existing != null && !existing.isBlank()) {
            log.info("[GitWorkspaceNode] Reusing existing repo: {}", existing);
            return;
        }

        String repoName = toRepoName(ctx.getBusiness().getTitle());
        String description = "Auto-generated website for " + ctx.getBusiness().getTitle();

        String repoUrl = gitHubApiService.createRepo(repoName, description);
        ctx.setGithubRepoUrl(repoUrl);
        taskRepo.updateGithubRepoUrl(ctx.getTaskId(), repoUrl);

        log.info("[GitWorkspaceNode] Created repo '{}' at {}", repoName, repoUrl);
    }

    // ── Step 2: Init workspace and checkout branch ────────────────────────

    private void initWorkspace(WorkerContext ctx) {
        Path workspace = ctx.getWorkspaceDir();
        String token = ctx.getGithubToken();
        String branch = ctx.getGithubBranch();
        String authedUrl = ctx.getGithubRepoUrl()
                .replace("https://", "https://x-access-token:" + token + "@");

        gitService.init(workspace);
        gitService.remoteAdd(workspace, "origin", authedUrl);
        gitService.fetchAll(workspace);

        int attempt = ctx.getAttemptNumber();
        if (attempt > 1) {
            String prevBranch = derivePreviousBranch(branch, attempt);
            log.info("[GitWorkspaceNode] Retry attempt {} — branching '{}' from 'origin/{}'",
                    attempt, branch, prevBranch);
            gitService.checkoutFromRef(workspace, branch, "origin/" + prevBranch);
        } else {
            gitService.checkoutFromMain(workspace, branch);
        }

        log.info("[GitWorkspaceNode] Checked out branch '{}' (attempt {})", branch, attempt);
    }

    // ── Step 3: Load project history from cloned workspace ────────────────

    private void loadProjectHistory(WorkerContext ctx) {
        Path historyFile = ctx.getWorkspaceDir().resolve(HISTORY_PATH);

        if (!Files.exists(historyFile)) {
            log.info("[GitWorkspaceNode] No PROJECT_HISTORY.md — first run for '{}'",
                    ctx.getBusiness().getTitle());
            return;
        }

        try {
            String history = Files.readString(historyFile);
            ctx.setProjectHistory(history);
            log.info("[GitWorkspaceNode] Loaded {} chars of project history for '{}'",
                    history.length(), ctx.getBusiness().getTitle());
        } catch (IOException e) {
            log.warn("[GitWorkspaceNode] Could not read PROJECT_HISTORY.md: {}", e.getMessage());
        }
    }

    // ── Static helpers (package-visible for tests) ────────────────────────

    static String toRepoName(String businessName) {
        if (businessName == null) return "business-site";
        return businessName.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    static String derivePreviousBranch(String branch, int attempt) {
        String suffix = "-attempt-" + attempt;
        if (branch.endsWith(suffix)) {
            return branch.substring(0, branch.length() - suffix.length()) + "-attempt-" + (attempt - 1);
        }
        return "main";
    }
}
