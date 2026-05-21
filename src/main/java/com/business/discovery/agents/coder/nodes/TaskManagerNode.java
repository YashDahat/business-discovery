package com.business.discovery.agents.coder.nodes;

import com.business.discovery.agents.coder.CoderAgentState;
import com.business.discovery.model.ArchitectBrief;
import com.business.discovery.model.ContainerTask;
import com.business.discovery.model.ContainerTask.ContainerTaskStatus;
import com.business.discovery.repository.ArchitectBriefRepository;
import com.business.discovery.repository.ContainerTaskRepository;
import com.business.discovery.services.agent.AgentEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class TaskManagerNode implements NodeAction<CoderAgentState> {

    private static final String NODE_NAME = "TASK_MANAGER";

    private final ArchitectBriefRepository briefRepository;
    private final ContainerTaskRepository containerTaskRepository;
    private final AgentEventService eventService;
    private final ObjectMapper objectMapper;

    @Override
    public Map<String, Object> apply(CoderAgentState state) {
        UUID briefId = UUID.fromString(state.briefId().orElseThrow());
        UUID runId = UUID.fromString(state.runId().orElseThrow());

        log.info("[{}] TaskManager processing briefId: {}", NODE_NAME, briefId);
        eventService.stepStarted(runId, NODE_NAME);

        try {
            // ── 1. Load the ArchitectBrief ─────────────────────
            ArchitectBrief brief = briefRepository.findById(briefId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Brief not found: " + briefId));

            // ── 2. Check if task already exists ───────────────
            // Prevents duplicate tasks for the same brief
            List<ContainerTask> existing = containerTaskRepository
                    .findByBriefId(briefId);

            boolean hasActiveTask = existing.stream()
                    .anyMatch(t -> t.getStatus() == ContainerTaskStatus.PENDING
                            || t.getStatus() == ContainerTaskStatus.RUNNING
                            || t.getStatus() == ContainerTaskStatus.RETRYING
                            || t.getStatus() == ContainerTaskStatus.COMPLETED);

            if (hasActiveTask) {
                log.info("[{}] Task already exists for briefId: {} — skipping",
                        NODE_NAME, briefId);
                return Map.of(
                        CoderAgentState.SKIP_REASON,
                        "Task already exists for this brief"
                );
            }

            // ── 3. Validate brief has minimum required fields ──
            if (brief.getWebsiteType() == null
                    || brief.getRecommendedPages() == null
                    || brief.getRecommendedPages().isEmpty()
                    || brief.getMustHaveFeatures() == null
                    || brief.getMustHaveFeatures().isEmpty()) {

                log.warn("[{}] Brief {} is incomplete — skipping", NODE_NAME, briefId);
                return Map.of(
                        CoderAgentState.SKIP_REASON,
                        "Brief is incomplete — missing required fields"
                );
            }

            // ── 4. Build task description from brief ──────────
            // This is what gets passed to sub-container as TASK_DESC ENV
            Map<String, Object> taskDescription = buildTaskDescription(brief);

            // ── 5. Generate GitHub branch name ────────────────
            String branch = buildBranchName(brief);

            // ── 6. Create ContainerTask record ────────────────
            ContainerTask task = ContainerTask.builder()
                    .briefId(briefId)
                    .businessId(brief.getBusinessId())
                    .runId(runId)
                    .taskDescription(taskDescription)
                    .githubBranch(branch)
                    .status(ContainerTaskStatus.PENDING)
                    .build();

            ContainerTask saved = containerTaskRepository.save(task);
            log.info("[{}] ContainerTask created — taskId: {}, branch: {}",
                    NODE_NAME, saved.getId(), branch);

            eventService.stepCompleted(runId, NODE_NAME, 0L);
            eventService.toolCall(runId, NODE_NAME, "ContainerTaskRepository",
                    Map.of("taskId", saved.getId().toString(),
                            "briefId", briefId.toString(),
                            "branch", branch));

            return Map.of(
                    CoderAgentState.TASK_ID, saved.getId().toString(),
                    CoderAgentState.TASK_STATUS, ContainerTaskStatus.PENDING.name(),
                    CoderAgentState.GITHUB_BRANCH, branch
            );

        } catch (Exception e) {
            log.error("[{}] Failed for briefId: {}", NODE_NAME, briefId, e);
            eventService.stepFailed(runId, NODE_NAME, e.getMessage());
            return Map.of(CoderAgentState.ERROR, e.getMessage());
        }
    }

    // ── Build task description passed to sub-container ──────

    private Map<String, Object> buildTaskDescription(ArchitectBrief brief) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("briefId",            brief.getId().toString());
        map.put("businessId",         brief.getBusinessId().toString());
        map.put("businessCategory",   brief.getBusinessCategory());
        map.put("location",           brief.getLocation());
        map.put("websiteType",        brief.getWebsiteType().name());
        map.put("recommendedPages",   brief.getRecommendedPages());
        map.put("mustHaveFeatures",   brief.getMustHaveFeatures());
        map.put("niceToHaveFeatures", brief.getNiceToHaveFeatures() != null
                ? brief.getNiceToHaveFeatures() : List.<String>of());
        map.put("techStack",          brief.getRecommendedTechStack() != null
                ? brief.getRecommendedTechStack() : Map.<String, String>of());
        map.put("seoKeywords",        brief.getSeoKeywords() != null
                ? brief.getSeoKeywords() : List.<String>of());
        map.put("designDirection",    brief.getDesignDirection());
        map.put("colorScheme",        brief.getColorScheme());
        map.put("tone",               brief.getTone());
        map.put("architecturalNotes", brief.getArchitecturalNotes());
        map.put("competitorInsights", brief.getCompetitorInsights());
        map.put("industryInsights",   brief.getIndustryInsights());
        return map;
    }

    // ── Generate branch name from brief ──────────────────────
    // e.g. "feature/venus-traders-ecommerce-abc12345"

    private String buildBranchName(ArchitectBrief brief) {
        String category = brief.getBusinessCategory() != null
                ? brief.getBusinessCategory()
                .toLowerCase()
                .replaceAll("[^a-z0-9]", "-")
                .replaceAll("-+", "-")
                : "unknown";

        String type = brief.getWebsiteType() != null
                ? brief.getWebsiteType().name().toLowerCase()
                : "site";

        String shortId = brief.getId().toString().substring(0, 8);

        return "feature/%s-%s-%s".formatted(category, type, shortId);
    }
}