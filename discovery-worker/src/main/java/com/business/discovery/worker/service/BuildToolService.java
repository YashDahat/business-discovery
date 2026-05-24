package com.business.discovery.worker.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@Service
@Slf4j
public class BuildToolService {

    public BuildResult runMvnCompile(Path backendDir) {
        return run(backendDir, List.of("mvn", "compile", "-q", "--no-transfer-progress"));
    }

    public BuildResult runNpmInstall(Path frontendDir) {
        return run(frontendDir, List.of("npm", "install", "--silent"));
    }

    public BuildResult runNpmBuild(Path frontendDir) {
        return run(frontendDir, List.of("npm", "run", "build"));
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private BuildResult run(Path workDir, List<String> cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.directory(workDir.toFile());

            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            int exit = proc.waitFor();

            log.debug("cmd={} exit={}", cmd, exit);
            return new BuildResult(exit, output);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return new BuildResult(1, e.getMessage());
        }
    }

    public record BuildResult(int exitCode, String output) {
        public boolean success() {
            return exitCode == 0;
        }
    }
}
