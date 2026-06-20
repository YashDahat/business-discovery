package com.business.discovery.worker.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class BuildToolService {

    public BuildResult runMvnCompile(Path backendDir) {
        return run(backendDir, List.of("mvn", "compile", "-q", "--no-transfer-progress"));
    }

    public BuildResult runNpmInstall(Path frontendDir) {
        return run(frontendDir, List.of("npm", "ci", "--silent"));
    }

    public BuildResult runNpmBuild(Path frontendDir) {
        return run(frontendDir, List.of("npm", "run", "build"));
    }

    public BuildResult runTscCheck(Path frontendDir) {
        return run(frontendDir, List.of("npm", "exec", "tsc", "--", "--project", "tsconfig.app.json"));
    }

    public BuildResult runNpmInstallPackage(Path frontendDir, String packageName) {
        return run(frontendDir, List.of("npm", "install", "--save", packageName, "--silent"));
    }

    public BuildResult runNpmInstallDevPackages(Path frontendDir, String... packages) {
        List<String> cmd = new ArrayList<>(List.of("npm", "install", "--save-dev", "--silent"));
        cmd.addAll(List.of(packages));
        return run(frontendDir, cmd);
    }

    public BuildResult runShadcnAdd(Path frontendDir, java.util.Collection<String> components) {
        List<String> cmd = new ArrayList<>(List.of("npx", "--yes", "shadcn@latest", "add", "--yes", "--overwrite"));
        cmd.addAll(components);
        return run(frontendDir, cmd);
    }

    /**
     * Runs eslint --fix using the worker-managed eslint.fix.config.mjs.
     * Exit code 1 means unfixable issues remain — that is expected and not a failure.
     * Exit code 2 means eslint itself crashed (config error, missing plugin).
     */
    public BuildResult runEslintFix(Path frontendDir) {
        return run(frontendDir, List.of(
                "npx", "eslint", "--fix",
                "--config", "eslint.fix.config.mjs",
                "src/"));
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
