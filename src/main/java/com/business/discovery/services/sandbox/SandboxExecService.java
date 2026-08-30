package com.business.discovery.services.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Runs commands and file operations inside a sandbox container via the Docker exec API.
 *
 * Everything goes through {@code docker exec} (so on Mac only the docker-proxy {@code EXEC} flag is
 * needed — no archive/PUT API). File writes/reads pipe base64 over exec stdin/stdout to sidestep shell
 * escaping and binary issues. All execs run in {@code sandbox.workspace-dir} as the image's non-root
 * {@code cline} user, with a timeout and an output cap.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxExecService {

    // Commands run under bash (a login shell) so real bash syntax works — /bin/sh is dash on Ubuntu.
    private static final String SHELL = "bash";

    private final DockerClient dockerClient;

    @Value("${sandbox.workspace-dir:/workspace}")
    private String workspaceDir;

    @Value("${sandbox.exec-timeout-seconds:600}")
    private int defaultTimeoutSeconds;

    @Value("${sandbox.output-max-bytes:100000}")
    private int outputMaxBytes;

    public static class SandboxException extends RuntimeException {
        public SandboxException(String message) { super(message); }
    }

    /** stdout/stderr (capped) + exit code of one exec; {@code timedOut} when it hit the timeout. */
    public record ExecResult(String stdout, String stderr, int exitCode, boolean timedOut) {
        public String combined() {
            return stderr == null || stderr.isBlank() ? stdout : (stdout + "\n" + stderr);
        }
    }

    /** Run a shell command in the workspace with the default timeout. */
    public ExecResult exec(String containerId, String command) {
        return exec(containerId, command, defaultTimeoutSeconds);
    }

    public ExecResult exec(String containerId, String command, int timeoutSeconds) {
        return run(containerId, new String[]{SHELL, "-lc", command}, null,
                timeoutSeconds > 0 ? timeoutSeconds : defaultTimeoutSeconds, outputMaxBytes);
    }

    /** Create/overwrite a file with {@code content} (parent dirs auto-created). */
    public void writeFile(String containerId, String path, String content) {
        byte[] b64 = Base64.getEncoder().encode(content.getBytes(StandardCharsets.UTF_8));
        ExecResult r = run(containerId,
                new String[]{SHELL, "-lc", "mkdir -p \"$(dirname \"$1\")\" && base64 -d > \"$1\"", SHELL, path},
                b64, defaultTimeoutSeconds, outputMaxBytes);
        if (r.exitCode() != 0) {
            throw new SandboxException("write '" + path + "' failed: " + r.combined());
        }
    }

    /** Read a text file's full content. Reads use a larger cap (base64 is ~1.33x the bytes). */
    public String readFile(String containerId, String path) {
        ExecResult r = run(containerId,
                new String[]{SHELL, "-lc", "base64 \"$1\"", SHELL, path},
                null, defaultTimeoutSeconds, outputMaxBytes * 8);
        if (r.exitCode() != 0) {
            throw new SandboxException("read '" + path + "' failed: " + r.combined());
        }
        String b64 = r.stdout().replaceAll("\\s", "");
        // Trim to a multiple of 4 so a capped/truncated stream still decodes without throwing.
        int len = b64.length() - (b64.length() % 4);
        return new String(Base64.getDecoder().decode(b64.substring(0, len)), StandardCharsets.UTF_8);
    }

    /** Directory listing (best-effort; returns the ls output including any error text). */
    public String listFiles(String containerId, String path) {
        String p = (path == null || path.isBlank()) ? "." : path;
        ExecResult r = run(containerId,
                new String[]{SHELL, "-lc", "ls -la \"$1\" 2>&1 || true", SHELL, p},
                null, 30, outputMaxBytes);
        return r.stdout();
    }

    // ── Core ──────────────────────────────────────────────────────────────

    private ExecResult run(String containerId, String[] cmd, byte[] stdin, int timeoutSeconds, int cap) {
        ExecCreateCmdResponse created = dockerClient.execCreateCmd(containerId)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withAttachStdin(stdin != null)
                .withWorkingDir(workspaceDir)
                .withCmd(cmd)
                .exec();
        String execId = created.getId();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                ByteArrayOutputStream sink = frame.getStreamType() == StreamType.STDERR ? err : out;
                if (sink.size() < cap) {
                    sink.write(frame.getPayload(), 0, frame.getPayload().length);
                }
            }
        };

        boolean completed;
        try (ExecStartCmd start = dockerClient.execStartCmd(execId)) {
            if (stdin != null) {
                start.withStdIn(new ByteArrayInputStream(stdin));
            }
            completed = start.exec(callback).awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxException("exec interrupted");
        }

        String stdout = truncate(out, cap);
        String stderr = truncate(err, cap);
        if (!completed) {
            safeClose(callback);
            log.warn("[Sandbox] exec timed out after {}s in {}", timeoutSeconds, containerId);
            return new ExecResult(stdout, stderr, -1, true);
        }

        Long exit = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
        return new ExecResult(stdout, stderr, exit != null ? exit.intValue() : -1, false);
    }

    private static String truncate(ByteArrayOutputStream buf, int cap) {
        byte[] bytes = buf.toByteArray();
        if (bytes.length <= cap) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return new String(bytes, 0, cap, StandardCharsets.UTF_8) + "\n…[output truncated]";
    }

    private static void safeClose(ResultCallback.Adapter<Frame> cb) {
        try { cb.close(); } catch (Exception ignored) { /* best effort */ }
    }
}
