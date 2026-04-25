package com.business.discovery.agents.architect.nodes;

import com.business.discovery.agents.architect.ArchitectAgentState;
import com.business.discovery.model.BusinessEntity;
import com.business.discovery.repository.BusinessEntityRepository;
import com.business.discovery.services.agent.AgentEventService;
import com.business.discovery.services.agent.architect.ArchitectAgentRunService;
import com.business.discovery.services.business.BusinessScoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class FilterAndScoreNode implements NodeAction<ArchitectAgentState> {

    private static final String NODE_NAME = "FILTER_AND_SCORE";

    private final BusinessEntityRepository businessRepository;
    private final BusinessScoringService scoringService;
    private final ArchitectAgentRunService agentRunService;
    private final AgentEventService eventService;

    @Override
    public Map<String, Object> apply(ArchitectAgentState state) {
        UUID runId = UUID.fromString(state.runId().orElseThrow());

        long start = System.currentTimeMillis();
        agentRunService.updateRunStatus(runId, com.business.discovery.model.AgentRun.AgentRunStatus.IN_PROGRESS, NODE_NAME);
        eventService.stepStarted(runId, NODE_NAME);

        try {
            // Load businesses scraped in previous node
            List<BusinessEntity> all = businessRepository.findByRunId(runId);
            log.info("[{}] Scoring {} businesses", runId, all.size());

            List<BusinessEntity> filtered = scoringService.scoreAndFilter(all);
            Map<String, Object> stats = scoringService.computeSummaryStats(all);

            // Persist updated tier/score fields
            businessRepository.saveAll(filtered);

            long duration = System.currentTimeMillis() - start;
            eventService.stepCompleted(runId, NODE_NAME, duration);
            eventService.log(runId, NODE_NAME,
                    com.business.discovery.model.AgentEvent.EventType.BUSINESSES_SCORED,
                    "Scored businesses", stats, duration);

            log.info("[{}] Filtered to {} targeted businesses — stats: {}",
                    runId, filtered.size(), stats);

            return Map.of(
                    ArchitectAgentState.FILTERED_COUNT, filtered.size(),
                    ArchitectAgentState.SCORING_STATS, stats
            );

        } catch (Exception e) {
            eventService.stepFailed(runId, NODE_NAME, e.getMessage());
            return Map.of(ArchitectAgentState.ERROR, e.getMessage());
        }
    }
}