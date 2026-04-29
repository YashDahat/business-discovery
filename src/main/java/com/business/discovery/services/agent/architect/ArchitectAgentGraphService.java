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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArchitectAgentGraphService {


    private final ArchitectAgentGraph architectAgentGraph;


    @Async
    public void executeAsync(UUID runId, String keyword,
                             String category, String location,
                             Map<String, Object> scraperConfig) {
        MDC.put("runId", runId.toString());
        try {
            architectAgentGraph.execute(runId, keyword, category, location, scraperConfig);
        } catch (Exception e) {
            log.error("Agent run failed — runId: {}", runId, e);
        } finally {
            MDC.remove("runId");
        }
    }

}