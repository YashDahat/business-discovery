package com.business.discovery.agents.architect.nodes;

import com.business.discovery.agents.architect.ArchitectAgentState;
import com.business.discovery.services.agent.AgentEventService;
import com.business.discovery.services.agent.architect.ArchitectAgentRunService;
import com.business.discovery.services.research.TavilyResearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class ResearchCompetitorsNode implements NodeAction<ArchitectAgentState> {

    private static final String NODE_NAME = "RESEARCH_COMPETITORS";

    private final TavilyResearchService researchService;
    private final ArchitectAgentRunService agentRunService;
    private final AgentEventService eventService;

    @Override
    public Map<String, Object> apply(ArchitectAgentState state) {
        UUID runId = UUID.fromString(state.runId().orElseThrow());
        String category = state.category().orElseThrow();
        String location = state.location().orElseThrow();

        long start = System.currentTimeMillis();
        agentRunService.updateRunStatus(
                runId,
                com.business.discovery.model.AgentRun.AgentRunStatus.IN_PROGRESS,
                NODE_NAME
        );
        eventService.stepStarted(runId, NODE_NAME);

        try {
            log.info("[{}] Researching competitors: {} in {}", runId, category, location);

            eventService.toolCall(runId, NODE_NAME, "TavilySearch",
                    Map.of("category", category, "location", location));

            String research = researchService.researchCompetitors(category, location);

            long duration = System.currentTimeMillis() - start;
            eventService.stepCompleted(runId, NODE_NAME, duration);

            log.info("[{}] Competitor research complete — {}ms", runId, duration);

            return Map.of(ArchitectAgentState.COMPETITOR_RESEARCH, research);

        } catch (Exception e) {
            eventService.stepFailed(runId, NODE_NAME, e.getMessage());
            return Map.of(ArchitectAgentState.ERROR, e.getMessage());
        }
    }
}