package com.business.discovery.agents.architect.nodes;

import com.business.discovery.agents.architect.ArchitectAgentState;
import com.business.discovery.services.agent.AgentEventService;
import com.business.discovery.services.agent.architect.ArchitectAgentRunService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class SaveBriefNode implements NodeAction<ArchitectAgentState> {

    private static final String NODE_NAME = "SAVE_BRIEF";

    private final ArchitectAgentRunService agentRunService;
    private final AgentEventService eventService;

    @Override
    public Map<String, Object> apply(ArchitectAgentState state) {
        UUID runId = UUID.fromString(state.runId().orElseThrow());
        int briefsCount = state.briefsCount().orElse(0);

        eventService.stepStarted(runId, NODE_NAME);

        agentRunService.completeRun(
                runId,
                null,                               // no single briefId anymore
                state.scrapedCount().orElse(0),
                state.filteredCount().orElse(0)
        );

        log.info("[{}] Agent run completed — {} briefs generated", runId, briefsCount);
        eventService.stepCompleted(runId, NODE_NAME, 0L);

        return Map.of();
    }
}