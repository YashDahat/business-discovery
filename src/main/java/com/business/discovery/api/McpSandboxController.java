package com.business.discovery.api;

import com.business.discovery.security.McpCallContext;
import com.business.discovery.services.cline.ClineStepRecorder;
import com.business.discovery.services.sandbox.SandboxExecService;
import com.business.discovery.services.sandbox.SandboxExecService.ExecResult;
import com.business.discovery.services.sandbox.SandboxExecService.SandboxException;
import com.business.discovery.services.sandbox.SandboxManager;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * MCP tools giving Cline a real execution environment: write/edit/read files and run scripts (Python,
 * TypeScript/tsx, Node, Maven) inside the per-brief sandbox container, then commit + push.
 *
 * Security invariants (same MCP foundation as {@link McpRepoController}):
 *  - Internal token + signed grant validated by {@code McpAuthFilter}; acting user in the SecurityContext.
 *  - The sandbox is resolved from the GRANT's {@code briefId} — NEVER from arguments (arguments only carry
 *    path/content/command/message). A prompt-injected Cline cannot touch another project.
 *  - The grant must list the tool; mutating tools are OPERATOR-gated.
 * Edits land on the working branch in the sandbox and reach the default branch only via a pull request.
 */
@Slf4j
@RestController
@RequestMapping("/internal/mcp/sandbox")
@RequiredArgsConstructor
public class McpSandboxController {

    private final SandboxManager sandboxManager;
    private final SandboxExecService exec;
    private final ClineStepRecorder stepRecorder;

    public record WriteFileRequest(String path, String content) {}
    public record EditFileRequest(String path, String find, String replace) {}
    public record ReadFileRequest(String path) {}
    public record ListFilesRequest(String path) {}
    public record RunCommandRequest(String command, Integer timeoutSec) {}
    public record CommitRequest(String message) {}
    public record CheckoutRequest(String branch, Boolean createNew) {}

    // ── Read tools (grant scope only) ─────────────────────────────────────

    @PostMapping("/read")
    public ResponseEntity<Map<String, Object>> read(@RequestBody ReadFileRequest req, HttpServletRequest request) {
        UUID briefId = requireTool("read_file", request);
        requirePath(req.path());
        String cid = sandboxManager.ensureReady(briefId);
        return stepRecorder.track(briefId, "read_file", "Read " + req.path(), () ->
                ResponseEntity.ok(Map.of("path", req.path(), "content", exec.readFile(cid, req.path()))));
    }

    @PostMapping("/list")
    public ResponseEntity<Map<String, Object>> list(@RequestBody ListFilesRequest req, HttpServletRequest request) {
        UUID briefId = requireTool("list_files", request);
        String cid = sandboxManager.ensureReady(briefId);
        String path = req.path() == null ? "" : req.path();
        return stepRecorder.track(briefId, "list_files", "List files: " + (path.isBlank() ? "/" : path), () ->
                ResponseEntity.ok(Map.of("path", path, "listing", exec.listFiles(cid, path))));
    }

    // ── Mutating tools (OPERATOR + grant scope) ───────────────────────────

    @PostMapping("/write")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Map<String, Object>> write(@RequestBody WriteFileRequest req, HttpServletRequest request) {
        UUID briefId = requireTool("write_file", request);
        requirePath(req.path());
        if (req.content() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content is required");
        }
        String cid = sandboxManager.ensureReady(briefId);
        return stepRecorder.track(briefId, "write_file", "Write " + req.path(), () -> {
            exec.writeFile(cid, req.path(), req.content());
            return ResponseEntity.ok(Map.of("path", req.path(), "written", true));
        });
    }

    @PostMapping("/edit")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Map<String, Object>> edit(@RequestBody EditFileRequest req, HttpServletRequest request) {
        UUID briefId = requireTool("edit_file", request);
        requirePath(req.path());
        if (req.find() == null || req.find().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "find is required");
        }
        String cid = sandboxManager.ensureReady(briefId);
        return stepRecorder.track(briefId, "edit_file", "Edit " + req.path(), () -> {
            String content = exec.readFile(cid, req.path());
            int first = content.indexOf(req.find());
            if (first < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "find text not present in " + req.path());
            }
            if (content.indexOf(req.find(), first + 1) >= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "find text is not unique in " + req.path() + " — include more context");
            }
            String updated = content.replace(req.find(), req.replace() == null ? "" : req.replace());
            exec.writeFile(cid, req.path(), updated);
            return ResponseEntity.ok(Map.of("path", req.path(), "edited", true));
        });
    }

    @PostMapping("/checkout")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Map<String, Object>> checkout(@RequestBody CheckoutRequest req, HttpServletRequest request) {
        UUID briefId = requireTool("checkout_branch", request);
        if (req.branch() == null || req.branch().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "branch is required");
        }
        boolean createNew = Boolean.TRUE.equals(req.createNew());
        return stepRecorder.track(briefId, "checkout_branch", "Checkout " + req.branch(), () -> {
            String actual = sandboxManager.checkout(briefId, req.branch(), createNew);
            return ResponseEntity.ok(Map.of(
                    "branch", actual,
                    "note", "Now on branch '" + actual + "'. Edits, commit_and_push and open_pull_request "
                            + "will use this branch until you check out another."));
        });
    }

    @PostMapping("/pull")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Map<String, Object>> pull(HttpServletRequest request) {
        UUID briefId = requireTool("pull_latest", request);
        String branch = sandboxManager.currentBranch(briefId);
        return stepRecorder.track(briefId, "pull_latest", "Pull latest (" + branch + ")", () -> {
            String result = sandboxManager.pull(briefId);
            return ResponseEntity.ok(Map.of("branch", branch, "result", result));
        });
    }

    @PostMapping("/run")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Map<String, Object>> run(@RequestBody RunCommandRequest req, HttpServletRequest request) {
        UUID briefId = requireTool("run_command", request);
        if (req.command() == null || req.command().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "command is required");
        }
        String cid = sandboxManager.ensureReady(briefId);
        int timeout = req.timeoutSec() != null ? req.timeoutSec() : 0; // 0 → service default
        return stepRecorder.track(briefId, "run_command", "$ " + req.command(), () -> {
            ExecResult r = exec.exec(cid, req.command(), timeout);
            // Non-zero exit is a valid result Cline must see (build/test failures) — return 200 with it.
            return ResponseEntity.ok(Map.of(
                    "command", req.command(),
                    "exitCode", r.exitCode(),
                    "timedOut", r.timedOut(),
                    "stdout", r.stdout(),
                    "stderr", r.stderr()));
        });
    }

    @PostMapping("/commit")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Map<String, Object>> commit(@RequestBody CommitRequest req, HttpServletRequest request) {
        UUID briefId = requireTool("commit_and_push", request);
        String message = (req.message() == null || req.message().isBlank())
                ? "Changes via Cline" : req.message();
        String cid = sandboxManager.ensureReady(briefId);
        // Push to whatever branch the sandbox is currently on (set by checkout_branch), not a fixed one.
        String branch = sandboxManager.currentBranch(briefId);
        return stepRecorder.track(briefId, "commit_and_push", "Commit & push (" + branch + ")", () -> {
            String b64 = Base64.getEncoder().encodeToString(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String script =
                    "MSG=$(printf %s '" + b64 + "' | base64 -d); git add -A; "
                    + "if git diff --cached --quiet; then echo __NO_CHANGES__; "
                    + "else git commit -m \"$MSG\" && git push origin HEAD:\"" + branch + "\"; fi";
            ExecResult r = exec.exec(cid, script);
            if (r.exitCode() != 0) {
                throw new SandboxException("commit/push failed: " + redact(r.combined()));
            }
            boolean noChanges = r.stdout().contains("__NO_CHANGES__");
            return ResponseEntity.ok(Map.of(
                    "committed", !noChanges,
                    "branch", branch,
                    "note", noChanges ? "Nothing to commit." : "Committed and pushed to " + branch + "."));
        });
    }

    @PostMapping("/stop")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Map<String, Object>> stop(HttpServletRequest request) {
        UUID briefId = requireTool("stop_sandbox", request);
        return stepRecorder.track(briefId, "stop_sandbox", "Stop sandbox", () -> {
            boolean stopped = sandboxManager.stopForBrief(briefId);
            return ResponseEntity.ok(Map.of(
                    "stopped", stopped,
                    "note", stopped
                            ? "Sandbox stopped and removed. A fresh one (re-cloned) is created on the next file/command."
                            : "No active sandbox for this project."));
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private UUID requireTool(String tool, HttpServletRequest request) {
        McpCallContext ctx = McpCallContext.current(request);
        if (ctx == null || ctx.briefId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No MCP scope");
        }
        if (!ctx.allowsTool(tool)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Grant does not permit " + tool);
        }
        return ctx.briefId();
    }

    private static void requirePath(String path) {
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
        }
    }

    private static String redact(String s) {
        return s == null ? "" : s.replaceAll("x-access-token:[^@]+@", "x-access-token:***@");
    }

    /** No repo yet, or sandbox capacity reached — a clean, expected condition. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(SandboxException.class)
    public ResponseEntity<Map<String, String>> handleSandbox(SandboxException ex) {
        log.warn("[McpSandbox] {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", ex.getMessage()));
    }
}
