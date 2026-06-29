package com.business.discovery.api;

import com.business.discovery.model.AgentEvent;
import com.business.discovery.model.AgentRun;
import com.business.discovery.model.ContainerTask;
import com.business.discovery.repository.AgentEventRepository;
import com.business.discovery.repository.AgentRunRepository;
import com.business.discovery.repository.ArchitectBriefRepository;
import com.business.discovery.repository.ContainerTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

@RestController
@RequestMapping("/api/ops")
@RequiredArgsConstructor
public class OpsController {

    private final AgentRunRepository agentRunRepository;
    private final AgentEventRepository agentEventRepository;
    private final ArchitectBriefRepository architectBriefRepository;
    private final ContainerTaskRepository containerTaskRepository;

    // One card per phase — status + key metrics
    @GetMapping("/agents/summary")
    public ResponseEntity<AgentsSummaryResponse> getAgentsSummary() {
        List<AgentRun> allRuns = agentRunRepository.findAllByOrderByCreatedAtDesc();

        // Phase 2 — Architect Agent
        boolean architectRunning = allRuns.stream()
                .anyMatch(r -> r.getStatus() == AgentRun.AgentRunStatus.IN_PROGRESS);
        OptionalDouble avgScraped = allRuns.stream()
                .filter(r -> r.getScrapedCount() != null && r.getScrapedCount() > 0)
                .mapToInt(AgentRun::getScrapedCount)
                .average();
        OptionalDouble avgTier1Rate = allRuns.stream()
                .filter(r -> r.getScrapedCount() != null && r.getScrapedCount() > 0
                          && r.getFilteredCount() != null)
                .mapToDouble(r -> (double) r.getFilteredCount() / r.getScrapedCount() * 100)
                .average();
        long briefCount = architectBriefRepository.count();

        // Phase 3 — Coder Agent
        long runningContainers = containerTaskRepository.countByStatus(ContainerTask.ContainerTaskStatus.RUNNING);
        long completedTasks    = containerTaskRepository.countByStatus(ContainerTask.ContainerTaskStatus.COMPLETED);
        long totalTasks        = containerTaskRepository.count();
        double prSuccessRate   = totalTasks > 0 ? (double) completedTasks / totalTasks * 100 : 0;

        boolean coderRunning = runningContainers > 0;

        int totalRunning = (architectRunning ? 1 : 0) + (coderRunning ? 1 : 0);

        List<AgentCard> agents = List.of(
                new AgentCard(1, "Chat Memory", "Conversational Intelligence",
                        "RUNNING", Map.of()),
                new AgentCard(2, "Architect Agent", "Business Intelligence Engine",
                        architectRunning ? "RUNNING" : "IDLE",
                        Map.of(
                                "totalRuns",      allRuns.size(),
                                "avgScrapedPerRun", Math.round(avgScraped.orElse(0)),
                                "tier1RatePct",   Math.round(avgTier1Rate.orElse(0) * 10.0) / 10.0,
                                "briefsGenerated", briefCount
                        )),
                new AgentCard(3, "Coder Agent", "Automated Website Generation",
                        coderRunning ? "RUNNING" : "IDLE",
                        Map.of(
                                "liveContainers",  runningContainers,
                                "completedSites",  completedTasks,
                                "prSuccessRatePct", Math.round(prSuccessRate * 10.0) / 10.0,
                                "totalTasks",      totalTasks
                        )),
                new AgentCard(4, "Demo & Deploy", "Demo & Deployment Agent",
                        "IDLE", Map.of()),
                new AgentCard(5, "Live Analytics", "Performance Collection",
                        "IDLE", Map.of())
        );

        return ResponseEntity.ok(new AgentsSummaryResponse(agents, totalRunning));
    }

    // Live event feed across all runs — last 50, newest first
    @GetMapping("/events/feed")
    public ResponseEntity<List<AgentEvent>> getEventsFeed() {
        return ResponseEntity.ok(agentEventRepository.findTop50ByOrderByCreatedAtDesc());
    }

    public record AgentCard(
            int phase,
            String name,
            String description,
            String status,
            Map<String, Object> metrics
    ) {}

    public record AgentsSummaryResponse(
            List<AgentCard> agents,
            int totalRunning
    ) {}
}
