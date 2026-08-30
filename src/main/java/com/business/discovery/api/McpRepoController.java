package com.business.discovery.api;

import com.business.discovery.model.DemoInstance;
import com.business.discovery.repository.ArchitectBriefRepository;
import com.business.discovery.security.McpCallContext;
import com.business.discovery.services.cline.ClineStepRecorder;
import com.business.discovery.services.cline.RepoScopeResolver;
import com.business.discovery.services.cline.RepoScopeResolver.RepoScope;
import com.business.discovery.services.demo.DemoService;
import com.business.discovery.services.github.GitHubContentsService;
import com.business.discovery.services.github.GitHubContentsService.FileContent;
import com.business.discovery.services.github.GitHubContentsService.GitHubApiException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP tool endpoints that let Cline read, edit and run a business's generated repo — backed by
 * {@link GitHubContentsService} (GitHub Contents API, no local clone) and {@link DemoService} (run).
 *
 * Security invariants (same MCP foundation as {@link McpBriefController} / {@link McpWebController}):
 *  - {@link com.business.discovery.security.McpAuthFilter} has validated the internal token + signed
 *    grant and set the acting user in the SecurityContext.
 *  - The target repo is resolved from the GRANT's {@code briefId} ({@link McpCallContext#briefId()}),
 *    NEVER from tool arguments — a prompt-injected Cline cannot touch another project's repo. Arguments
 *    only carry path/content/message/title/body.
 *  - The grant must list the specific tool (checked per method).
 *  - Mutating tools (create/write/pr/run) are additionally OPERATOR-gated, like update_architect_brief.
 *
 * Write policy: edits land on the working branch ({@code cline.repo.working-branch}, default
 * {@code cline/edits}); the default branch is only touched via an explicit pull request.
 */
@Slf4j
@RestController
@RequestMapping("/internal/mcp/repo")
@RequiredArgsConstructor
public class McpRepoController {

    private final RepoScopeResolver repoScopeResolver;
    private final GitHubContentsService github;
    private final ArchitectBriefRepository briefRepository;
    private final DemoService demoService;
    private final ClineStepRecorder stepRecorder;
    private final com.business.discovery.services.sandbox.SandboxManager sandboxManager;

    public record ListRequest(String path, String ref) {}
    public record ReadRequest(String path, String ref) {}
    public record WriteRequest(String path, String content, String message) {}
    public record PrRequest(String title, String body) {}

    // ── Read tools (grant-scope only) ─────────────────────────────────────

    @PostMapping("/status")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        RepoScope scope = scope("repo_status", request);
        return stepRecorder.track(scope.briefId(), "repo_status", "Check repository status", () -> {
            Map<String, Object> body = new HashMap<>();
            body.put("hasRepo", scope.hasRepo());
            body.put("repoUrl", scope.repoUrl());
            body.put("repoName", scope.repoName());
            body.put("businessTitle", scope.businessTitle());
            body.put("workingBranch", scope.workingBranch());
            body.put("defaultBranch", scope.hasRepo() ? github.getDefaultBranch(scope.repoName()) : null);
            return ResponseEntity.ok(body);
        });
    }

    @PostMapping("/list")
    public ResponseEntity<String> list(@RequestBody ListRequest req, HttpServletRequest request) {
        RepoScope scope = requireExistingRepo("list_repo_files", request);
        String path = req.path() == null ? "" : req.path();
        return stepRecorder.track(scope.briefId(), "list_repo_files",
                "List files: " + (path.isBlank() ? "/" : path),
                () -> json(github.listFiles(scope.repoName(), path, req.ref())));
    }

    @PostMapping("/read")
    public ResponseEntity<Map<String, Object>> read(@RequestBody ReadRequest req, HttpServletRequest request) {
        RepoScope scope = requireExistingRepo("read_repo_file", request);
        if (req.path() == null || req.path().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
        }
        return stepRecorder.track(scope.briefId(), "read_repo_file", "Read " + req.path(), () -> {
            FileContent fc = github.readFile(scope.repoName(), req.path(), req.ref());
            return ResponseEntity.ok(Map.of("path", fc.path(), "sha", fc.sha(), "content", fc.content()));
        });
    }

    // ── Mutating tools (OPERATOR + grant scope) ───────────────────────────

    @PostMapping("/create")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Map<String, Object>> create(HttpServletRequest request) {
        RepoScope scope = scope("create_repo", request);
        if (scope.hasRepo()) {
            return ResponseEntity.ok(Map.of(
                    "repoUrl", scope.repoUrl(), "repoName", scope.repoName(),
                    "created", false, "note", "Repo already exists for this project."));
        }
        return stepRecorder.track(scope.briefId(), "create_repo",
                "Create repository " + scope.repoName(), () -> {
            String description = "Website for "
                    + (scope.businessTitle() != null ? scope.businessTitle() : "this business");
            String repoUrl = github.createRepo(scope.repoName(), description);
            briefRepository.updateGithubRepoUrl(scope.briefId(), repoUrl);
            log.info("[McpRepo] Created repo '{}' at {} for brief {}", scope.repoName(), repoUrl, scope.briefId());
            return ResponseEntity.ok(Map.of(
                    "repoUrl", repoUrl, "repoName", scope.repoName(), "created", true));
        });
    }

    @PostMapping("/write")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Map<String, Object>> write(@RequestBody WriteRequest req, HttpServletRequest request) {
        RepoScope scope = requireExistingRepo("write_repo_file", request);
        if (req.path() == null || req.path().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
        }
        if (req.content() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content is required");
        }
        return stepRecorder.track(scope.briefId(), "write_repo_file", "Write " + req.path(), () -> {
            String commitUrl = github.writeFile(scope.repoName(), req.path(), req.content(),
                    req.message(), scope.workingBranch());
            log.info("[McpRepo] Wrote '{}' on {} branch {} (brief {})",
                    req.path(), scope.repoName(), scope.workingBranch(), scope.briefId());
            return ResponseEntity.ok(Map.of(
                    "path", req.path(), "branch", scope.workingBranch(), "commitUrl", commitUrl));
        });
    }

    @PostMapping("/pr")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Map<String, Object>> pr(@RequestBody PrRequest req, HttpServletRequest request) {
        RepoScope scope = requireExistingRepo("open_pull_request", request);
        // Head = the branch the sandbox is currently on (set by checkout_branch), falling back to the
        // default working branch — so the PR matches what Cline actually committed.
        String head = sandboxManager.currentBranch(scope.briefId());
        return stepRecorder.track(scope.briefId(), "open_pull_request", "Open pull request (" + head + ")", () -> {
            String base = github.getDefaultBranch(scope.repoName());
            String prUrl = github.openPullRequest(scope.repoName(), head, base, req.title(), req.body());
            log.info("[McpRepo] PR {} -> {} on {} : {}", head, base, scope.repoName(), prUrl);
            return ResponseEntity.ok(Map.of("prUrl", prUrl, "head", head, "base", base));
        });
    }

    @PostMapping("/run")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<?> run(HttpServletRequest request) {
        RepoScope scope = scope("run_demo", request);
        try {
            DemoInstance demo = stepRecorder.track(scope.briefId(), "run_demo", "Start demo",
                    () -> demoService.start(scope.briefId()));
            return ResponseEntity.ok(demo);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // e.g. demo already running, or no published image yet — a clean, expected condition.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Verify the grant permits {@code tool}, then resolve the repo scope from the grant's brief. */
    private RepoScope scope(String tool, HttpServletRequest request) {
        McpCallContext ctx = McpCallContext.current(request);
        if (ctx == null || ctx.briefId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No MCP scope");
        }
        if (!ctx.allowsTool(tool)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Grant does not permit " + tool);
        }
        return repoScopeResolver.resolve(ctx.briefId());
    }

    private RepoScope requireExistingRepo(String tool, HttpServletRequest request) {
        RepoScope scope = scope(tool, request);
        if (!scope.hasRepo()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No repository for this project yet — call create_repo first.");
        }
        return scope;
    }

    private static ResponseEntity<String> json(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    /** Surface GitHub failures with their real status + message so Cline can react. */
    @ExceptionHandler(GitHubApiException.class)
    public ResponseEntity<Map<String, String>> handleGitHub(GitHubApiException ex) {
        int status = ex.status() >= 400 && ex.status() < 600 ? ex.status() : 502;
        log.warn("[McpRepo] GitHub error HTTP {}: {}", status, ex.getMessage());
        return ResponseEntity.status(status).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadInput(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
