package com.business.discovery.services.agent.architect;

import com.business.discovery.model.AgentRun;
import com.business.discovery.repository.AgentRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArchitectAgentRunService {

    private final AgentRunRepository agentRunRepository;

    // Creates the AgentRun record and triggers the graph asynchronously
    public AgentRun startRun(String keyword) {
        // Parse category and location from keyword
        // e.g. "restaurants in Khadki, Pune" → category=restaurants, location=Khadki, Pune
        String[] parts = parseKeyword(keyword);
        String category = parts[0];
        String location = parts[1];

        AgentRun run = AgentRun.builder()
                .keyword(keyword)
                .category(category)
                .location(location)
                .status(AgentRun.AgentRunStatus.PENDING)
                .build();

        AgentRun saved = agentRunRepository.save(run);
        log.info("AgentRun created — id: {}, keyword: {}", saved.getId(), keyword);

        // Trigger async — returns immediately to the controller
        //executeAsync(saved.getId(), keyword, category, location);

        return saved;
    }

    public com.business.discovery.dto.architect.ArchitectRunStatusResponse getRunStatus(UUID runId) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Run not found: " + runId));

        return new com.business.discovery.dto.architect.ArchitectRunStatusResponse(
                run.getId(),
                run.getKeyword(),
                run.getCategory(),
                run.getLocation(),
                run.getStatus(),
                run.getCurrentStep(),
                run.getScrapedCount(),
                run.getFilteredCount(),
                run.getBriefId(),
                run.getErrorMessage(),
                run.getCreatedAt(),
                run.getCompletedAt()
        );
    }

    public List<AgentRun> getAllRuns() {
        return agentRunRepository.findAllByOrderByCreatedAtDesc();
    }

    public void updateRunStatus(UUID runId, AgentRun.AgentRunStatus status, String currentStep) {
        agentRunRepository.findById(runId).ifPresent(run -> {
            run.setStatus(status);
            run.setCurrentStep(currentStep);
            run.setLastHeartbeat(LocalDateTime.now());
            agentRunRepository.save(run);
        });
    }

    public void completeRun(UUID runId, UUID briefId,
                            int scrapedCount, int filteredCount) {
        agentRunRepository.findById(runId).ifPresent(run -> {
            run.setStatus(AgentRun.AgentRunStatus.COMPLETED);
            run.setCurrentStep("COMPLETED");
            run.setBriefId(briefId);
            run.setScrapedCount(scrapedCount);
            run.setFilteredCount(filteredCount);
            run.setCompletedAt(LocalDateTime.now());
            agentRunRepository.save(run);
        });
    }

    public void failRun(UUID runId, String errorMessage) {
        agentRunRepository.findById(runId).ifPresent(run -> {
            run.setStatus(AgentRun.AgentRunStatus.FAILED);
            run.setCurrentStep("FAILED");
            run.setErrorMessage(errorMessage);
            agentRunRepository.save(run);
        });
    }

    // Simple keyword parser — LLM will handle this more intelligently later
    private String[] parseKeyword(String keyword) {
        // "restaurants in Khadki, Pune" → ["restaurants", "Khadki, Pune"]
        String lower = keyword.toLowerCase();
        int inIndex = lower.indexOf(" in ");

        if (inIndex != -1) {
            return new String[]{
                    keyword.substring(0, inIndex).trim(),
                    keyword.substring(inIndex + 4).trim()
            };
        }

        // Fallback — treat whole string as category, location unknown
        return new String[]{keyword, "unknown"};
    }
}
