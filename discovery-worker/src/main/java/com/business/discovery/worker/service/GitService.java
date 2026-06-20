package com.business.discovery.worker.service;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.errorhandler.WorkerException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
@Slf4j
public class GitService {

    public void init(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new WorkerException(FailureType.INFRA, "Failed to create workspace directory: " + dir, e);
        }
        run(dir, List.of("git", "init"), "git init failed in " + dir);
    }

    public void remoteAdd(Path dir, String name, String url) {
        run(dir, List.of("git", "remote", "add", name, url), "git remote add failed");
    }

    public void setTokenAuth(Path repoDir, String repoUrl, String token) {
        // Embed token into the remote URL so subsequent push/pull are authenticated
        String authedUrl = repoUrl.replace("https://", "https://x-access-token:" + token + "@");
        run(repoDir, List.of("git", "remote", "set-url", "origin", authedUrl),
                "Failed to set token auth on remote");
    }

    public void clone(String repoUrl, Path targetDir) {
        run(null, List.of("git", "clone", repoUrl, targetDir.toString()),
                "git clone failed for " + repoUrl);
    }

    public void fetchAll(Path repoDir) {
        run(repoDir, List.of("git", "fetch", "origin"), "git fetch failed");
    }

    // Creates a new local branch from origin/main so existing code is present in workspace.
    // Falls back to an empty branch if origin/main doesn't exist (edge case: empty remote).
    public void checkoutFromMain(Path repoDir, String branch) {
        checkoutFromRef(repoDir, branch, "origin/main");
    }

    // Creates a new local branch from an arbitrary start point (e.g. origin/prev-attempt-branch).
    // Falls back to origin/main, then to an empty orphan branch, so this never hard-fails.
    public void checkoutFromRef(Path repoDir, String branch, String startPoint) {
        try {
            run(repoDir, List.of("git", "checkout", "-b", branch, startPoint),
                    "git checkout from " + startPoint + " failed for branch " + branch);
        } catch (WorkerException e) {
            log.warn("[GitService] '{}' not found — falling back to origin/main for branch '{}'",
                    startPoint, branch);
            try {
                run(repoDir, List.of("git", "checkout", "-b", branch, "origin/main"),
                        "git checkout from origin/main fallback failed");
            } catch (WorkerException e2) {
                log.warn("[GitService] origin/main also not available — creating empty branch '{}'", branch);
                run(repoDir, List.of("git", "checkout", "-b", branch),
                        "git checkout empty branch failed for " + branch);
            }
        }
    }

    public void checkout(Path repoDir, String branch, boolean create) {
        List<String> cmd = create
                ? List.of("git", "checkout", "-b", branch)
                : List.of("git", "checkout", branch);
        run(repoDir, cmd, "git checkout failed for branch " + branch);
    }

    public void addAll(Path repoDir) {
        run(repoDir, List.of("git", "add", "-A"),
                "git add -A failed");
    }

    /**
     * Returns true if there are staged or unstaged changes in the working tree.
     * Uses --porcelain so the output is empty on a clean tree.
     */
    public boolean hasChanges(Path repoDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "status", "--porcelain");
            pb.redirectErrorStream(true);
            pb.directory(repoDir.toFile());
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
            return !output.isBlank();
        } catch (Exception e) {
            log.warn("[GitService] Could not check git status: {}", e.getMessage());
            return false;
        }
    }

    public void commit(Path repoDir, String message) {
        run(repoDir, List.of("git", "commit", "-m", message),
                "git commit failed");
    }

    public void push(Path repoDir, String branch) {
        run(repoDir, List.of("git", "push", "origin", branch),
                "git push failed for branch " + branch);
    }

    public void pullRebase(Path repoDir, String branch) {
        run(repoDir, List.of("git", "pull", "--rebase", "origin", branch),
                "git pull --rebase failed for branch " + branch);
    }

    /**
     * Returns true if the branch already exists on origin (checked after a fetch).
     */
    public boolean remoteBranchExists(Path repoDir, String branch) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "ls-remote", "--heads", "origin", branch);
            pb.redirectErrorStream(true);
            pb.directory(repoDir.toFile());
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
            return !output.isBlank();
        } catch (Exception e) {
            log.warn("[GitService] Could not check remote branch '{}': {}", branch, e.getMessage());
            return false;
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private void run(Path workDir, List<String> cmd, String errorMsg) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            if (workDir != null) pb.directory(workDir.toFile());

            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            int exit = proc.waitFor();

            log.debug("git cmd={} exit={} output={}", cmd.get(1), exit, output.strip());

            if (exit != 0) {
                throw new WorkerException(FailureType.INFRA,
                        errorMsg + " (exit " + exit + "): " + output.strip());
            }
        } catch (WorkerException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new WorkerException(FailureType.INFRA, errorMsg + ": " + detail, e);
        }
    }
}
