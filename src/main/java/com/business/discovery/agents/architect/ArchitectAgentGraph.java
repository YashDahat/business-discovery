package com.business.discovery.agents.architect;

import com.business.discovery.agents.architect.nodes.*;
import com.business.discovery.repository.ArchitectBriefRepository;
import com.business.discovery.repository.BusinessEntityRepository;
import com.business.discovery.services.agent.AgentEventService;
import com.business.discovery.services.agent.architect.ArchitectAgentRunService;
import com.business.discovery.services.business.BusinessScoringService;
import com.business.discovery.services.research.TavilyResearchService;
import com.business.discovery.services.scraper.GoogleMapsScraperService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Slf4j
@Component
@RequiredArgsConstructor
public class ArchitectAgentGraph {

    // ─── Dependencies injected by Spring ──────────────────
    private final GoogleMapsScraperService scraperService;
    private final BusinessEntityRepository businessRepository;
    private final BusinessScoringService scoringService;
    private final TavilyResearchService researchService;
    private final ArchitectBriefRepository briefRepository;
    private final ArchitectAgentRunService agentRunService;
    private final AgentEventService eventService;
    private final BaseCheckpointSaver checkpointSaver;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    private CompiledGraph<ArchitectAgentState> compiledGraph;

    @PostConstruct
    public void init() throws Exception {
        log.info("Compiling ArchitectAgent graph...");

        // Instantiate nodes
        var scrapeNode = new ScrapeBusinessesNode(
                scraperService, agentRunService, eventService);
        var filterNode = new FilterAndScoreNode(
                businessRepository, scoringService, agentRunService, eventService);
        var industryNode = new ResearchIndustryNode(
                researchService, agentRunService, eventService);
        var competitorNode = new ResearchCompetitorsNode(
                researchService, agentRunService, eventService);
        var seoNode = new ResearchSeoNode(
                researchService, agentRunService, eventService);
        var techStackNode = new ResearchTechStackNode(
                researchService, agentRunService, eventService);
        var synthesizeNode = new SynthesizeBriefNode(
                chatModel,
                briefRepository,
                agentRunService,
                eventService,
                objectMapper,
                businessRepository,
                researchService
        );
        var saveNode = new SaveBriefNode(
                agentRunService, eventService);

        // Build the StateGraph
        compiledGraph = new StateGraph<>(ArchitectAgentState::new)
                .addNode("scrape_businesses",    node_async(scrapeNode))
                .addNode("filter_and_score",     node_async(filterNode))
                .addNode("research_industry",    node_async(industryNode))
                .addNode("research_competitors", node_async(competitorNode))
                .addNode("research_seo",         node_async(seoNode))
                .addNode("research_tech_stack",  node_async(techStackNode))
                .addNode("synthesize_brief",     node_async(synthesizeNode))
                .addNode("save_brief",           node_async(saveNode))
                // ─── Edges ────────────────────────────────
                .addEdge(START,                "scrape_businesses")
                .addEdge("scrape_businesses",  "filter_and_score")
                .addEdge("filter_and_score",   "research_industry")
                .addEdge("research_industry",  "research_competitors")
                .addEdge("research_competitors","research_seo")
                .addEdge("research_seo",       "research_tech_stack")
                .addEdge("research_tech_stack","synthesize_brief")
                .addEdge("synthesize_brief",   "save_brief")
                .addEdge("save_brief",          END)
                // ─── Compile with PostgreSQL checkpointing ─
                .compile(CompileConfig.builder()
                        .checkpointSaver(checkpointSaver)
                        .build());

        log.info("ArchitectAgent graph compiled successfully");
    }

    // Called by ArchitectAgentService.executeAsync()
    public void execute(UUID runId, String keyword,
                        String category, String location) throws Exception {

        Map<String, Object> initialState = Map.of(
                ArchitectAgentState.RUN_ID,   runId.toString(),
                ArchitectAgentState.KEYWORD,  keyword,
                ArchitectAgentState.CATEGORY, category,
                ArchitectAgentState.LOCATION, location
        );

        // threadId = runId — each agent run is an isolated graph thread
        var config = org.bsc.langgraph4j.RunnableConfig.builder()
                .threadId(runId.toString())
                .build();

        log.info("Invoking ArchitectAgent graph — runId: {}, keyword: {}",
                runId, keyword);

        // invoke() runs the full graph synchronously in the async thread
        compiledGraph.invoke(initialState, config);

        log.info("ArchitectAgent graph execution complete — runId: {}", runId);
    }
}