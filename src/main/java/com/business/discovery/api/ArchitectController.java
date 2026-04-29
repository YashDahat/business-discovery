package com.business.discovery.api;

import com.business.discovery.dto.ArchitectRunRequest;
import com.business.discovery.dto.ArchitectRunResponse;
import com.business.discovery.dto.architect.ArchitectRunStatusResponse;
import com.business.discovery.model.AgentEvent;
import com.business.discovery.model.AgentRun;
import com.business.discovery.model.ArchitectBrief;
import com.business.discovery.services.agent.AgentEventService;

import com.business.discovery.services.agent.architect.ArchitectAgentBriefService;
import com.business.discovery.services.agent.architect.ArchitectAgentGraphService;
import com.business.discovery.services.agent.architect.ArchitectAgentRunService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v2/architect")
@RequiredArgsConstructor
public class ArchitectController {
    private final ArchitectAgentBriefService architectAgentBriefService;
    private final ArchitectAgentGraphService architectAgentGraphService;
    private final ArchitectAgentRunService architectAgentRunService;
    private final AgentEventService agentEventService;

    // Trigger a new agent run — returns immediately with runId
    @PostMapping("/run")
    public ResponseEntity<ArchitectRunResponse> startRun(
            @RequestBody ArchitectRunRequest request) {

        log.info("Architect run requested — keyword: {}", request.keyword());

        AgentRun run = architectAgentRunService.startRun(request);

        Map<String, Object> scraperConfig = new HashMap<>();
        scraperConfig.put("lang",      request.lang());
        scraperConfig.put("depth",     request.depth());
        scraperConfig.put("zoom",      request.zoom());
        scraperConfig.put("lat",       request.lat());
        scraperConfig.put("lon",       request.lon());
        scraperConfig.put("fastMode",  request.fastMode());
        scraperConfig.put("radius",    request.radius());
        scraperConfig.put("email",     request.email());
        scraperConfig.put("maxTime",   request.maxTime());
        scraperConfig.put("proxies",   request.proxies());

        architectAgentGraphService.executeAsync(run.getId(), run.getKeyword(), run.getCategory(), run.getLocation(), scraperConfig);

        return ResponseEntity.accepted().body(new ArchitectRunResponse(
                run.getId(),
                run.getStatus().name(),
                "Agent started autonomously. Poll /run/%s/status for progress."
                        .formatted(run.getId())
        ));
    }

    // Poll run status
    @GetMapping("/run/{runId}/status")
    public ResponseEntity<ArchitectRunStatusResponse> getStatus(
            @PathVariable UUID runId) {
        return ResponseEntity.ok(architectAgentRunService.getRunStatus(runId));
    }

    // Get produced brief once run is COMPLETED
    // Replace single brief endpoint
    @GetMapping("/run/{runId}/brief")
    public ResponseEntity<ArchitectBrief> getBrief(@PathVariable UUID runId) {
        return architectAgentBriefService.getBrief(runId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // With multiple briefs endpoint
    @GetMapping("/run/{runId}/briefs")
    public ResponseEntity<List<ArchitectBrief>> getBriefs(@PathVariable UUID runId) {
        return ResponseEntity.ok(architectAgentBriefService.getBriefsByRunId(runId));
    }

    // Get full event log for a run — your observability window
    @GetMapping("/run/{runId}/events")
    public ResponseEntity<List<AgentEvent>> getEvents(@PathVariable UUID runId) {
        return ResponseEntity.ok(agentEventService.getRunEvents(runId));
    }

    // List all runs — simple dashboard
    @GetMapping("/runs")
    public ResponseEntity<List<AgentRun>> getAllRuns() {
        return ResponseEntity.ok(architectAgentRunService.getAllRuns());
    }
}