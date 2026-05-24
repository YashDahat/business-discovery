package com.business.discovery.worker.service;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.errorhandler.WorkerException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@Service
@Slf4j
public class GitService {

    public void init(Path dir) {
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

    public void commit(Path repoDir, String message) {
        run(repoDir, List.of("git", "commit", "-m", message),
                "git commit failed");
    }

    public void push(Path repoDir, String branch) {
        run(repoDir, List.of("git", "push", "origin", branch),
                "git push failed for branch " + branch);
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
            throw new WorkerException(FailureType.INFRA, errorMsg + ": " + e.getMessage(), e);
        }
    }
}
