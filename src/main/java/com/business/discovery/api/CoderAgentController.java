package com.business.discovery.api;

import com.business.discovery.agents.coder.CoderAgentGraph;
import com.business.discovery.model.ArchitectBrief;
import com.business.discovery.model.ContainerTask;
import com.business.discovery.model.ContainerTask.ContainerTaskStatus;
import com.business.discovery.repository.ArchitectBriefRepository;
import com.business.discovery.repository.ContainerTaskRepository;
import com.business.discovery.services.chat.ChatService;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v3/coder")
@RequiredArgsConstructor
public class CoderAgentController {

    private final CoderAgentGraph coderAgentGraph;
    private final ContainerTaskRepository containerTaskRepository;
    private final ArchitectBriefRepository architectBriefRepository;
    private final ChatService chatService;

    // Manually trigger coder agent for a specific brief
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> triggerRun(
            @RequestBody CoderRunRequest request) {

        log.info("Coder agent triggered — briefId: {}", request.briefId());

        coderAgentGraph.execute(
                request.runId(),
                request.briefId(),
                request.businessId()
        );

        return ResponseEntity.accepted().body(Map.of(
                "status", "TRIGGERED",
                "briefId", request.briefId().toString(),
                "message", "CoderAgent started — check /api/v3/containers/tasks for status"
        ));
    }

    // List all tasks for a specific brief
    @GetMapping("/brief/{briefId}/tasks")
    public ResponseEntity<List<ContainerTask>> getTasksForBrief(
            @PathVariable UUID briefId) {
        return ResponseEntity.ok(containerTaskRepository.findByBriefId(briefId));
    }

    // Full change-request conversation for a brief — backed by the same
    // Postgres-persisted chat memory used by /api/chat (one session per brief).
    @GetMapping("/brief/{briefId}/chat")
    public ResponseEntity<List<ChatService.ChatMessageView>> getChat(@PathVariable UUID briefId) {
        ArchitectBrief brief = architectBriefRepository.findById(briefId)
                .orElseThrow(() -> new IllegalArgumentException("ArchitectBrief not found: " + briefId));

        if (brief.getChatSessionId() == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(chatService.getHistory(brief.getChatSessionId()));
    }

    // Send a change request: appends to the persisted chat thread, writes the
    // instruction to architect_brief.requested_changes, and resets the latest
    // task to PENDING so the worker re-runs with the change applied. The
    // "Queued" note is posted here; ContainerMonitorService posts the
    // applied/failed follow-up once the worker container exits.
    @PostMapping("/brief/{briefId}/chat")
    public ResponseEntity<Map<String, Object>> sendChatMessage(
            @PathVariable UUID briefId,
            @RequestBody ChatSendRequest request) {

        ArchitectBrief brief = architectBriefRepository.findById(briefId)
                .orElseThrow(() -> new IllegalArgumentException("ArchitectBrief not found: " + briefId));

        ContainerTask task = containerTaskRepository
                .findTopByBriefIdOrderByCreatedAtDesc(briefId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No ContainerTask found for briefId: " + briefId));

        Long sessionId = brief.getChatSessionId();
        if (sessionId == null) {
            sessionId = chatService.createSession();
            architectBriefRepository.updateChatSessionId(briefId, sessionId);
        }

        // Deterministic pipeline effect — happens even if the LLM reply below fails.
        architectBriefRepository.updateRequestedChanges(briefId, request.message());
        task.setStatus(ContainerTaskStatus.PENDING);
        containerTaskRepository.save(task);

        ChatService.ChatResult result =
                chatService.chat(sessionId, List.of(UserMessage.from(request.message())));
        chatService.appendSystemNote(sessionId, "Queued — will regenerate on next cycle");

        log.info("Chat change request for briefId: {} — taskId: {} reset to PENDING", briefId, task.getId());

        return ResponseEntity.accepted().body(Map.of(
                "sessionId", result.sessionId(),
                "reply", result.reply(),
                "taskId", task.getId().toString()
        ));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(IllegalArgumentException ex) {
        return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
    }

    public record CoderRunRequest(
            UUID runId,
            UUID briefId,
            UUID businessId
    ) {}

    public record ChatSendRequest(String message) {}
}