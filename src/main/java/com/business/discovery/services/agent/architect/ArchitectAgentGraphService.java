package com.business.discovery.services.agent.architect;

import com.business.discovery.agents.architect.ArchitectAgentGraph;
import com.business.discovery.dto.architect.ArchitectRunStatusResponse;
import com.business.discovery.model.AgentRun;
import com.business.discovery.model.AgentRun.AgentRunStatus;
import com.business.discovery.model.ArchitectBrief;
import com.business.discovery.repository.AgentRunRepository;
import com.business.discovery.repository.ArchitectBriefRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArchitectAgentGraphService {


    private final ArchitectAgentGraph architectAgentGraph;


    @Async
    public void executeAsync(UUID runId, String keyword,
                             String category, String location) {
        // Add runId to MDC so all log lines from this run are tagged
        MDC.put("runId", runId.toString());

        try {
            log.info("Agent run starting — runId: {}", runId);

            // Mark as IN_PROGRESS
            //updateRunStatus(runId, AgentRunStatus.IN_PROGRESS, "INITIALIZING");

            // Execute the LangGraph4j workflow
            architectAgentGraph.execute(runId, keyword, category, location);

        } catch (Exception e) {
            log.error("Agent run failed — runId: {}", runId, e);
            //failRun(runId, e.getMessage());
        } finally {
            MDC.remove("runId");
        }
    }

}