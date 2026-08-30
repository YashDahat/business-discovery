package com.business.discovery.api;

import com.business.discovery.model.ArchitectBrief;
import com.business.discovery.model.PlatformUser;
import com.business.discovery.repository.ArchitectBriefRepository;
import com.business.discovery.repository.PlatformUserRepository;
import com.business.discovery.services.chat.ChatService;
import com.business.discovery.services.cline.ClineChatService;
import com.business.discovery.services.cline.ClineStepRecorder;
import com.business.discovery.services.cline.mcp.McpGrantService;
import com.business.discovery.services.user.PlatformUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cline-backed, read-only (Plan-mode) Q&A about a project, layered on the existing per-brief chat.
 *
 * Kept separate from {@code /api/v3/coder/brief/{briefId}/chat} (the change-request endpoint that
 * resets the worker task to PENDING) so this Q&A path never triggers a regeneration. Turns are
 * persisted into the same Postgres chat_memory session tied to the brief, so history is shared.
 */
@Slf4j
@RestController
@RequestMapping("/api/v4/cline")
@RequiredArgsConstructor
public class ClineChatController {

    private final ClineChatService clineChatService;
    private final ChatService chatService;
    private final ArchitectBriefRepository briefRepository;
    private final PlatformUserRepository userRepository;
    private final McpGrantService mcpGrantService;
    private final ClineStepRecorder stepRecorder;

    // Tools this Cline session may call (grant-scoped), all proxied through Spring Boot pinned to this
    // session's brief: read context + apply brief changes; Tavily web (search/extract/crawl/map); repo
    // lifecycle (status/create/PR) + run_demo; and the execution sandbox (file edit + run scripts).
    // Repo file editing is done in the sandbox (write_file/edit_file/read_file/list_files) + commit_and_push,
    // which supersedes the earlier Contents-API file tools.
    private static final List<String> DEFAULT_MCP_TOOLS =
            List.of("get_project_context", "update_architect_brief",
                    "web_search", "web_extract", "web_crawl", "web_map",
                    "repo_status", "create_repo", "open_pull_request", "run_demo",
                    "write_file", "edit_file", "read_file", "list_files",
                    "run_command", "commit_and_push", "checkout_branch", "pull_latest", "stop_sandbox");

    public record ChatSendRequest(String message) {}

    @PostMapping("/brief/{briefId}/chat")
    public ResponseEntity<Map<String, Object>> chat(
            @PathVariable UUID briefId,
            @RequestBody ChatSendRequest request,
            @AuthenticationPrincipal PlatformUserDetails principal) {

        ArchitectBrief brief = briefRepository.findById(briefId)
                .orElseThrow(() -> new IllegalArgumentException("ArchitectBrief not found: " + briefId));

        // Resolve (or create + link) the brief's chat session — same convention as CoderAgentController.
        Long sessionId = brief.getChatSessionId();
        if (sessionId == null) {
            sessionId = chatService.createSession();
            briefRepository.updateChatSessionId(briefId, sessionId);
        }

        PlatformUser user = principal == null ? null
                : userRepository.findById(principal.getId()).orElse(null);

        // Reset the step timeline for this brief so the stepper reflects only this turn's tool calls.
        stepRecorder.startTurn(briefId);

        // Mint a short-lived, brief-pinned grant so any MCP tool Cline calls this turn is authorized
        // as this user, scoped to this project. Null when unauthenticated (no MCP access).
        String grant = user == null ? null
                : mcpGrantService.mint(user, briefId, DEFAULT_MCP_TOOLS);

        ClineChatService.ClineChatResult result =
                clineChatService.chat(sessionId, briefId, user, request.message(), grant);

        return ResponseEntity.ok(Map.of(
                "sessionId", result.sessionId(),
                "reply", result.reply()
        ));
    }

    /**
     * Start a fresh chat session for this brief (used by "New session" / clear-chat). The old session's
     * messages stay in chat_memory (and their vectors keep their old sessionId), so nothing is lost —
     * meaningful decisions are meant to be persisted to the brief itself via update_architect_brief.
     */
    @PostMapping("/brief/{briefId}/chat/new")
    public ResponseEntity<Map<String, Object>> newSession(@PathVariable UUID briefId) {
        briefRepository.findById(briefId)
                .orElseThrow(() -> new IllegalArgumentException("ArchitectBrief not found: " + briefId));
        Long sessionId = chatService.createSession();
        briefRepository.updateChatSessionId(briefId, sessionId);
        return ResponseEntity.ok(Map.of("sessionId", sessionId));
    }

    /**
     * Live stepper feed: the MCP tool operations Cline is performing (or just performed) this turn.
     * The ProjectChatPanel short-polls this while a message is in flight. Read-only, current turn only.
     */
    @GetMapping("/brief/{briefId}/steps")
    public ResponseEntity<ClineStepRecorder.TurnView> steps(@PathVariable UUID briefId) {
        return ResponseEntity.ok(stepRecorder.snapshot(briefId));
    }

    @GetMapping("/brief/{briefId}/chat")
    public ResponseEntity<List<ChatService.ChatMessageView>> getChat(@PathVariable UUID briefId) {
        ArchitectBrief brief = briefRepository.findById(briefId)
                .orElseThrow(() -> new IllegalArgumentException("ArchitectBrief not found: " + briefId));
        if (brief.getChatSessionId() == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(chatService.getHistory(brief.getChatSessionId()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
