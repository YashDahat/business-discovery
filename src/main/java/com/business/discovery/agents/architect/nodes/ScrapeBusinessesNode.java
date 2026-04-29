package com.business.discovery.agents.architect.nodes;

import com.business.discovery.agents.architect.ArchitectAgentState;
import com.business.discovery.dto.GoogleMapsScraperJobRequest;
import com.business.discovery.model.AgentRun;
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

        // Read scraper config from state
        Map<String, Object> config = state.scraperConfig().orElse(Map.of());

        long start = System.currentTimeMillis();
        agentRunService.updateRunStatus(runId, AgentRun.AgentRunStatus.IN_PROGRESS, NODE_NAME);
        eventService.stepStarted(runId, NODE_NAME);

        try {
            log.info("[{}] Scraping with config: {}", runId, config);

            // Build request from state config
            GoogleMapsScraperJobRequest scraperRequest = GoogleMapsScraperJobRequest.builder()
                    .name(keyword)
                    .keywords(List.of(keyword))
                    .lang((String)  config.getOrDefault("lang",     "en"))
                    .depth((Integer) config.getOrDefault("depth",   5))
                    .zoom((Integer)  config.getOrDefault("zoom",    15))
                    .fastMode((Boolean) config.getOrDefault("fastMode", false))
                    .radius((Integer) config.getOrDefault("radius", 10000))
                    .email((Boolean) config.getOrDefault("email",   false))
                    .maxTime((Integer) config.getOrDefault("maxTime", 0))
                    .lat((String)   config.get("lat"))
                    .lon((String)   config.get("lon"))
                    .proxies((List<String>) config.get("proxies"))
                    .build();

            List<BusinessEntity> businesses = scraperService.scrapeAndPersist(
                    scraperRequest, runId);

            long duration = System.currentTimeMillis() - start;
            eventService.stepCompleted(runId, NODE_NAME, duration);

            return Map.of(ArchitectAgentState.SCRAPED_COUNT, businesses.size());

        } catch (Exception e) {
            eventService.stepFailed(runId, NODE_NAME, e.getMessage());
            return Map.of(ArchitectAgentState.ERROR, e.getMessage());
        }
    }
}