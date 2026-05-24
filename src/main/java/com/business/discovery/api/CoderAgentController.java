package com.business.discovery.api;

import com.business.discovery.agents.coder.CoderAgentGraph;
import com.business.discovery.model.ContainerTask;
import com.business.discovery.model.ContainerTask.ContainerTaskStatus;
import com.business.discovery.repository.ArchitectBriefRepository;
import com.business.discovery.repository.ContainerTaskRepository;
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

    // Submit client-requested changes: writes to architect_brief.requested_changes
    // and resets the latest task to PENDING so the worker re-runs with the changes.
    @PostMapping("/brief/{briefId}/changes")
    public ResponseEntity<Map<String, Object>> submitChanges(
            @PathVariable UUID briefId,
            @RequestBody ChangesRequest request) {

        architectBriefRepository.findById(briefId)
                .orElseThrow(() -> new IllegalArgumentException("ArchitectBrief not found: " + briefId));

        architectBriefRepository.updateRequestedChanges(briefId, request.requestedChanges());

        ContainerTask task = containerTaskRepository
                .findTopByBriefIdOrderByCreatedAtDesc(briefId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No ContainerTask found for briefId: " + briefId));

        task.setStatus(ContainerTaskStatus.PENDING);
        containerTaskRepository.save(task);

        log.info("Changes submitted for briefId: {} — taskId: {} reset to PENDING", briefId, task.getId());

        return ResponseEntity.accepted().body(Map.of(
                "status", "CHANGES_SUBMITTED",
                "briefId", briefId.toString(),
                "taskId", task.getId().toString(),
                "message", "Changes saved — container will re-run on next queue cycle"
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

    public record ChangesRequest(String requestedChanges) {}
}