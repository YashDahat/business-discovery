package com.business.discovery.api;

import com.business.discovery.dto.ArchitectRunRequest;
import com.business.discovery.dto.ArchitectRunResponse;
import com.business.discovery.dto.architect.ArchitectRunStatusResponse;
import com.business.discovery.model.AgentEvent;
import com.business.discovery.model.AgentRun;
import com.business.discovery.model.ArchitectBrief;
import com.business.discovery.model.ContainerTask;
import com.business.discovery.repository.ArchitectBriefRepository;
import com.business.discovery.repository.ContainerTaskRepository;
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
    private final ArchitectBriefRepository architectBriefRepository;
    private final ContainerTaskRepository containerTaskRepository;
    private final com.business.discovery.services.agent.architect.SingleBusinessBriefService singleBusinessBriefService;

    private static final Map<String, Integer> NODE_STAGE = Map.of(
            "ScrapeBusinessesNode", 0,
            "FilterAndScoreNode", 1,
            "ResearchIndustryNode", 2, "ResearchCompetitorsNode", 2,
            "ResearchSeoNode", 2, "ResearchTechStackNode", 2,
            "SynthesizeBriefNode", 3, "SaveBriefNode", 3
    );

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

    // Generate a brief for a specific business already in the DB (no scraping)
    @PostMapping("/business/{businessId}/brief")
    public ResponseEntity<Map<String, Object>> generateBriefForBusiness(
            @PathVariable UUID businessId) {

        if (architectBriefRepository.existsByBusinessId(businessId)) {
            ArchitectBrief existing = architectBriefRepository.findByBusinessId(businessId).orElseThrow();
            return ResponseEntity.ok(Map.of(
                    "status", "ALREADY_EXISTS",
                    "briefId", existing.getId().toString(),
                    "message", "Brief already exists for this business"
            ));
        }

        // Clear any previous failure so user can retry
        singleBusinessBriefService.clearError(businessId);

        log.info("Single-business brief requested for businessId={}", businessId);
        singleBusinessBriefService.generateAsync(businessId);

        return ResponseEntity.accepted().body(Map.of(
                "status", "GENERATING",
                "businessId", businessId.toString(),
                "message", "Brief generation started — poll GET /api/v2/architect/business/%s/brief/status"
                        .formatted(businessId)
        ));
    }

    // Poll for the brief once generated
    @GetMapping("/business/{businessId}/brief")
    public ResponseEntity<ArchitectBrief> getBriefForBusiness(@PathVariable UUID businessId) {
        return architectBriefRepository.findByBusinessId(businessId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Poll generation status — returns GENERATING, COMPLETED, FAILED, or NOT_STARTED
    @GetMapping("/business/{businessId}/brief/status")
    public ResponseEntity<Map<String, Object>> getBriefGenerationStatus(@PathVariable UUID businessId) {
        var state = singleBusinessBriefService.getState(businessId);
        var body = new java.util.HashMap<String, Object>();
        body.put("status", state.status().name());
        body.put("businessId", businessId.toString());
        if (state.error() != null) body.put("error", state.error());
        return ResponseEntity.ok(body);
    }

    // List all runs — simple dashboard
    @GetMapping("/runs")
    public ResponseEntity<List<AgentRun>> getAllRuns() {
        return ResponseEntity.ok(architectAgentRunService.getAllRuns());
    }

    // Pipeline drill-down — node statuses + counts for a single run
    @GetMapping("/run/{runId}/pipeline")
    public ResponseEntity<PipelineResponse> getPipeline(@PathVariable UUID runId) {
        ArchitectRunStatusResponse status = architectAgentRunService.getRunStatus(runId);

        List<ArchitectBrief> briefs = architectBriefRepository.findAllByRunId(runId);
        List<ContainerTask> tasks = containerTaskRepository.findByRunId(runId);

        int sitesLive = (int) tasks.stream()
                .filter(t -> t.getStatus() == ContainerTask.ContainerTaskStatus.COMPLETED
                          && t.getGithubPrUrl() != null)
                .count();

        List<PipelineStage> stages = List.of(
                new PipelineStage("Scrape",       "gosom · Google Maps",      architectStageStatus(0, status)),
                new PipelineStage("Score",         "revenue tiering",           architectStageStatus(1, status)),
                new PipelineStage("Research",      "Tavily web search",         architectStageStatus(2, status)),
                new PipelineStage("Synthesis",     "Gemini ArchitectBrief",     architectStageStatus(3, status)),
                new PipelineStage("Codegen",       "Coder containers",          codegenStatus(tasks, status)),
                new PipelineStage("Validate",      "compile check",             validateStatus(tasks, status)),
                new PipelineStage("Pull Request",  "GitHub App",                prStatus(tasks, status)),
                new PipelineStage("Demo Deploy",   "EC2 demo env",              "pending")
        );

        return ResponseEntity.ok(new PipelineResponse(
                runId,
                status.status().name(),
                status.keyword(),
                status.location(),
                status.scrapedCount() != null ? status.scrapedCount() : 0,
                status.filteredCount() != null ? status.filteredCount() : 0,
                briefs.size(),
                sitesLive,
                stages
        ));
    }

    private String architectStageStatus(int stageIdx, ArchitectRunStatusResponse status) {
        boolean completed = status.status() == AgentRun.AgentRunStatus.COMPLETED;
        boolean failed    = status.status() == AgentRun.AgentRunStatus.FAILED;
        int currentIdx = NODE_STAGE.getOrDefault(status.currentStep(), -1);

        if (completed) return "passed";
        if (failed) {
            if (stageIdx < currentIdx)  return "passed";
            if (stageIdx == currentIdx) return "failed";
            return "pending";
        }
        if (stageIdx < currentIdx)  return "passed";
        if (stageIdx == currentIdx) return "running";
        return "pending";
    }

    private String codegenStatus(List<ContainerTask> tasks, ArchitectRunStatusResponse status) {
        if (status.status() != AgentRun.AgentRunStatus.COMPLETED) return "pending";
        if (tasks.isEmpty()) return "pending";
        boolean anyRunning = tasks.stream().anyMatch(t ->
                t.getStatus() == ContainerTask.ContainerTaskStatus.RUNNING ||
                t.getStatus() == ContainerTask.ContainerTaskStatus.RETRYING);
        boolean anyDone = tasks.stream().anyMatch(t ->
                t.getStatus() == ContainerTask.ContainerTaskStatus.COMPLETED);
        if (anyRunning) return "running";
        if (anyDone)    return "passed";
        return "pending";
    }

    private String validateStatus(List<ContainerTask> tasks, ArchitectRunStatusResponse status) {
        return codegenStatus(tasks, status); // validate is part of the codegen container lifecycle
    }

    private String prStatus(List<ContainerTask> tasks, ArchitectRunStatusResponse status) {
        if (status.status() != AgentRun.AgentRunStatus.COMPLETED) return "pending";
        return tasks.stream().anyMatch(t -> t.getGithubPrUrl() != null) ? "passed" : "pending";
    }

    public record PipelineResponse(
            UUID runId,
            String status,
            String query,
            String location,
            int scraped,
            int tier1,
            int briefs,
            int sitesLive,
            List<PipelineStage> stages
    ) {}

    public record PipelineStage(String name, String description, String status) {}
}