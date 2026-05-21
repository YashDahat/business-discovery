package com.business.discovery.agents.coder;

import com.business.discovery.agents.coder.nodes.ContainerSpawnerNode;
import com.business.discovery.agents.coder.nodes.TaskManagerNode;
import com.business.discovery.repository.ArchitectBriefRepository;
import com.business.discovery.repository.ContainerTaskRepository;
import com.business.discovery.services.agent.AgentEventService;
import com.business.discovery.services.container.ContainerPoolManager;
import com.business.discovery.services.container.DockerContainerService;
import com.business.discovery.agents.architect.ArchitectBriefCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Slf4j
@Component
@RequiredArgsConstructor
public class CoderAgentGraph {

    private final ArchitectBriefRepository briefRepository;
    private final ContainerTaskRepository containerTaskRepository;
    private final ContainerPoolManager poolManager;
    private final DockerContainerService dockerContainerService;
    private final AgentEventService eventService;
    private final BaseCheckpointSaver checkpointSaver;
    private final ObjectMapper objectMapper;

    private CompiledGraph<CoderAgentState> compiledGraph;

    @PostConstruct
    public void init() throws Exception {
        log.info("Compiling CoderAgent graph...");

        var taskManagerNode = new TaskManagerNode(
                briefRepository,
                containerTaskRepository,
                eventService,
                objectMapper
        );

        var containerSpawnerNode = new ContainerSpawnerNode(
                containerTaskRepository,
                poolManager,
                dockerContainerService,
                eventService
        );

        compiledGraph = new StateGraph<>(CoderAgentState::new)
                .addNode("task_manager",       node_async(taskManagerNode))
                .addNode("container_spawner",  node_async(containerSpawnerNode))
                .addEdge(START,                "task_manager")
                .addEdge("task_manager",       "container_spawner")
                .addEdge("container_spawner",  END)
                .compile(CompileConfig.builder()
                        .checkpointSaver(checkpointSaver)
                        .build());

        log.info("CoderAgent graph compiled successfully");
    }

    // ── Triggered by Phase 2 ArchitectBriefCompletedEvent ──

    @EventListener
    public void onBriefCompleted(ArchitectBriefCompletedEvent event) {
        log.info("Brief completed event received — briefId: {}", event.getBriefId());
        execute(event.getRunId(), event.getBriefId(), event.getBusinessId());
    }

    // ── Manual trigger ─────────────────────────────────────

    public void execute(UUID runId, UUID briefId, UUID businessId) {
        Map<String, Object> initialState = Map.of(
                CoderAgentState.RUN_ID,      runId.toString(),
                CoderAgentState.BRIEF_ID,    briefId.toString(),
                CoderAgentState.BUSINESS_ID, businessId.toString()
        );

        var config = org.bsc.langgraph4j.RunnableConfig.builder()
                .threadId(briefId.toString())
                .build();

        log.info("Invoking CoderAgent — briefId: {}", briefId);

        try {
            compiledGraph.invoke(initialState, config);
        } catch (Exception e) {
            log.error("CoderAgent graph failed — briefId: {}", briefId, e);
        }
    }
}