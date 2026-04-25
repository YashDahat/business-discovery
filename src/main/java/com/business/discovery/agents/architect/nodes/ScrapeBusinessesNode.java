package com.business.discovery.agents.architect.nodes;

import com.business.discovery.agents.architect.ArchitectAgentState;
import com.business.discovery.model.BusinessEntity;
import com.business.discovery.services.agent.AgentEventService;
import com.business.discovery.services.agent.architect.ArchitectAgentRunService;
import com.business.discovery.services.scraper.GoogleMapsScraperService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class ScrapeBusinessesNode implements NodeAction<ArchitectAgentState> {

    private static final String NODE_NAME = "SCRAPE_BUSINESSES";

    private final GoogleMapsScraperService scraperService;
    private final ArchitectAgentRunService agentRunService;
    private final AgentEventService eventService;

    @Override
    public Map<String, Object> apply(ArchitectAgentState state) {
        UUID runId = UUID.fromString(state.runId().orElseThrow());
        String keyword = state.keyword().orElseThrow();

        long start = System.currentTimeMillis();
        agentRunService.updateRunStatus(runId, com.business.discovery.model.AgentRun.AgentRunStatus.IN_PROGRESS, NODE_NAME);
        eventService.stepStarted(runId, NODE_NAME);

        try {
            log.info("[{}] Scraping businesses for keyword: {}", runId, keyword);
            eventService.toolCall(runId, NODE_NAME, "GoogleMapsScraper",
                        Map.of("keyword", keyword));

            List<BusinessEntity> businesses = scraperService.scrapeAndPersist(keyword, runId);

            long duration = System.currentTimeMillis() - start;
            eventService.stepCompleted(runId, NODE_NAME, duration);

            log.info("[{}] Scraped {} businesses in {}ms", runId, businesses.size(), duration);

            return Map.of(
                    ArchitectAgentState.SCRAPED_COUNT, businesses.size()
            );

        } catch (Exception e) {
            eventService.stepFailed(runId, NODE_NAME, e.getMessage());
            return Map.of(ArchitectAgentState.ERROR, e.getMessage());
        }
    }
}