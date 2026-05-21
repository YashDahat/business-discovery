package com.business.discovery.api;

import com.business.discovery.agents.coder.CoderAgentGraph;
import com.business.discovery.model.ContainerTask;
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

    public record CoderRunRequest(
            UUID runId,
            UUID briefId,
            UUID businessId
    ) {}
}