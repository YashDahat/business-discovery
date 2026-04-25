package com.business.discovery.agents.architect.nodes;

import com.business.discovery.agents.architect.ArchitectAgentState;
import com.business.discovery.model.AgentRun;
import com.business.discovery.model.ArchitectBrief;
import com.business.discovery.model.BusinessEntity;
import com.business.discovery.repository.ArchitectBriefRepository;
import com.business.discovery.repository.BusinessEntityRepository;
import com.business.discovery.services.agent.AgentEventService;
import com.business.discovery.services.agent.architect.ArchitectAgentRunService;
import com.business.discovery.services.research.TavilyResearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class SynthesizeBriefNode implements NodeAction<ArchitectAgentState> {

    private static final String NODE_NAME = "SYNTHESIZE_BRIEF";

    private final ChatModel chatModel;
    private final ArchitectBriefRepository briefRepository;
    private final ArchitectAgentRunService agentRunService;
    private final AgentEventService eventService;
    private final ObjectMapper objectMapper;
    private final BusinessEntityRepository businessEntityRepository;
    private final TavilyResearchService tavilyResearchService;

    @Override
    public Map<String, Object> apply(ArchitectAgentState state) {
        UUID runId = UUID.fromString(state.runId().orElseThrow());

        long start = System.currentTimeMillis();
        agentRunService.updateRunStatus(runId,
                AgentRun.AgentRunStatus.IN_PROGRESS, NODE_NAME);
        eventService.stepStarted(runId, NODE_NAME);

        try {
            // Load only Tier 1 businesses for this run
            List<BusinessEntity> tier1Businesses = businessEntityRepository
                    .findByRunIdAndBusinessTier(runId, "TIER_1");

            log.info("[{}] Synthesizing briefs for {} Tier 1 businesses",
                    runId, tier1Businesses.size());

            List<UUID> briefIds = new ArrayList<>();

            for (BusinessEntity business : tier1Businesses) {
                log.info("[{}] Synthesizing brief for: {}", runId, business.getTitle());

                // Skip if brief already exists — idempotent
                if (briefRepository.existsByBusinessId(business.getId())) {
                    log.info("[{}] Brief already exists for {}, skipping",
                            runId, business.getTitle());
                    continue;
                }

                try {
                    // Research this specific business by name
                    String businessResearch = researchSpecificBusiness(business);

                    // Build prompt for this specific business
                    String prompt = buildPrompt(state, business, businessResearch);

                    eventService.llmCall(runId, NODE_NAME, prompt.length() / 4);
                    String rawResponse = chatModel.chat(prompt);

                    // Parse and save brief for this business
                    ArchitectBrief brief = parseBrief(rawResponse, state, runId, business);
                    ArchitectBrief saved = briefRepository.save(brief);
                    briefIds.add(saved.getId());

                    log.info("[{}] Brief saved for {} — briefId: {}",
                            runId, business.getTitle(), saved.getId());

                    // Small delay between LLM calls — be a good API citizen
                    Thread.sleep(2000);

                } catch (Exception e) {
                    // Don't fail the entire run for one business
                    log.error("[{}] Failed to synthesize brief for {}: {}",
                            runId, business.getTitle(), e.getMessage());
                    eventService.stepFailed(runId, NODE_NAME,
                            "Brief failed for " + business.getTitle() + ": " + e.getMessage());
                }
            }

            long duration = System.currentTimeMillis() - start;
            eventService.stepCompleted(runId, NODE_NAME, duration);

            log.info("[{}] Synthesized {} briefs in {}ms",
                    runId, briefIds.size(), duration);

            return Map.of(
                    ArchitectAgentState.ARCHITECT_BRIEF_IDS, briefIds,
                    ArchitectAgentState.BRIEFS_COUNT, briefIds.size()
            );

        } catch (Exception e) {
            eventService.stepFailed(runId, NODE_NAME, e.getMessage());
            return Map.of(ArchitectAgentState.ERROR, e.getMessage());
        }
    }

    private String researchSpecificBusiness(BusinessEntity business) {
        // Search for this specific business online
        String query = "%s %s %s site reviews online presence"
                .formatted(business.getTitle(), business.getCategory(),
                        business.getAddress());
        return tavilyResearchService.search(query);
    }

    private String buildPrompt(ArchitectAgentState state, BusinessEntity business,
                               String businessResearch) {
        return """
        You are a senior web architect. Analyze this specific business and
        produce a tailored website architect brief for them.

        === BUSINESS DETAILS ===
        Name: %s
        Category: %s
        Address: %s
        Phone: %s
        Rating: %s (%s reviews)
        Price Range: %s
        Current Website: %s
        Has Reservation System: %s
        Has Online Ordering: %s
        Has Menu Link: %s
        Website Scope Score: %s
        Estimated Revenue: %s

        === BUSINESS SPECIFIC RESEARCH ===
        %s

        === INDUSTRY CONTEXT (for %s in %s) ===
        %s

        === COMPETITOR INSIGHTS ===
        %s

        === SEO RESEARCH ===
        %s

        === TECH STACK RESEARCH ===
        %s

        === YOUR TASK ===
        Produce a JSON architect brief SPECIFICALLY for %s with EXACTLY this structure:
        {
          "websiteType": "INFORMATIONAL | BOOKING | ECOMMERCE | FULL_PLATFORM",
          "recommendedPages": ["page1", "page2"],
          "mustHaveFeatures": ["feature1", "feature2"],
          "niceToHaveFeatures": ["feature1", "feature2"],
          "recommendedTechStack": {
            "frontend": "...",
            "backend": "...",
            "database": "...",
            "hosting": "..."
          },
          "seoKeywords": ["keyword1", "keyword2"],
          "designDirection": "...",
          "colorScheme": "...",
          "tone": "...",
          "competitorInsights": "...",
          "industryInsights": "...",
          "architecturalNotes": "..."
        }

        RULES:
        - Base decisions on THIS specific business data, not general knowledge
        - Consider their rating and review count when recommending complexity
        - If no website exists, recommend starting with their strongest feature
        - Respond with ONLY valid JSON — no markdown, no explanation
       \s
        === SYSTEM CONSTRAINTS ===
        This brief will be consumed by an automated AI code generation agent.
        The tech stack MUST be chosen from these options only:
       \s
        Frontend: React 19 + Tailwind CSS + Shadcn/UI
        Backend: Spring Boot 3.x + Spring Data JPA + Spring Security
        Database: PostgreSQL
        Hosting: AWS App Runner (backend) + Vercel (frontend)
        Payment: Razorpay (for Indian businesses requiring payments)
       \s
        Do NOT recommend WordPress, Wix, Shopify, or any CMS platform.
        Do NOT recommend PHP, MySQL, or any non-Java backend.
        The generated code must be deployable via Docker and GitHub Actions.
       \s""".formatted(
                business.getTitle(),
                business.getCategory(),
                business.getAddress(),
                business.getPhone() != null ? business.getPhone() : "N/A",
                business.getRating(),
                business.getReviewCount(),
                business.getPriceRange() != null ? business.getPriceRange() : "N/A",
                business.getWebsite() != null ? business.getWebsite() : "None",
                business.getReservationLink() != null ? "Yes" : "No",
                business.getOrderOnlineLink() != null ? "Yes" : "No",
                business.getMenuLink() != null ? "Yes" : "No",
                business.getWebsiteScopeScore(),
                business.getRevenueEstimate() != null ? business.getRevenueEstimate() : "N/A",
                businessResearch,
                state.category().orElse(""),
                state.location().orElse(""),
                state.industryResearch().orElse("No data"),
                state.competitorResearch().orElse("No data"),
                state.seoResearch().orElse("No data"),
                state.techStackResearch().orElse("No data"),
                business.getTitle()
        );
    }

    private ArchitectBrief parseBrief(String raw, ArchitectAgentState state,
                                      UUID runId, BusinessEntity business) throws Exception {
        // Strip markdown fences if LLM adds them
        String json = raw.replaceAll("```json", "")
                .replaceAll("```", "")
                .trim();

        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);

        return ArchitectBrief.builder()
                .runId(runId)
                .businessId(business.getId())               // ← link to specific business
                .businessCategory(business.getCategory())   // ← from business, not state
                .location(business.getAddress())            // ← business specific address
                .businessCount(1)                           // ← always 1, per-business brief
                .averageRating(business.getRating())        // ← this business's rating
                .websiteAdoptionRate(
                        business.getWebsite() != null ? 100.0 : 0.0)  // ← has website or not
                .tier1Count(
                        "TIER_1".equals(business.getBusinessTier()) ? 1 : 0)
                .tier2Count(
                        "TIER_2".equals(business.getBusinessTier()) ? 1 : 0)
                .websiteType(parseWebsiteType(parsed))
                .recommendedPages(toList(parsed.get("recommendedPages")))
                .mustHaveFeatures(toList(parsed.get("mustHaveFeatures")))
                .niceToHaveFeatures(toList(parsed.get("niceToHaveFeatures")))
                .recommendedTechStack(toMap(parsed.get("recommendedTechStack")))
                .seoKeywords(toList(parsed.get("seoKeywords")))
                .designDirection(str(parsed.get("designDirection")))
                .colorScheme(str(parsed.get("colorScheme")))
                .tone(str(parsed.get("tone")))
                .competitorInsights(str(parsed.get("competitorInsights")))
                .industryInsights(str(parsed.get("industryInsights")))
                .architecturalNotes(str(parsed.get("architecturalNotes")))
                .rawLlmOutput(raw)
                .build();
    }

    // ─── Helpers ──────────────────────────────────────────

    private ArchitectBrief.WebsiteType parseWebsiteType(Map<String, Object> parsed) {
        try {
            return ArchitectBrief.WebsiteType.valueOf(
                    str(parsed.get("websiteType")).toUpperCase()
            );
        } catch (Exception e) {
            return ArchitectBrief.WebsiteType.INFORMATIONAL;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> toList(Object obj) {
        return obj instanceof List ? (List<String>) obj : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> toMap(Object obj) {
        return obj instanceof Map ? (Map<String, String>) obj : Map.of();
    }

    private String str(Object obj) {
        return obj != null ? obj.toString() : "";
    }

    private Double toDouble(Object obj) {
        if (obj instanceof Number) return ((Number) obj).doubleValue();
        try { return Double.parseDouble(obj.toString()); }
        catch (Exception e) { return 0.0; }
    }

    private Integer toInt(Object obj) {
        if (obj instanceof Number) return ((Number) obj).intValue();
        try { return Integer.parseInt(obj.toString()); }
        catch (Exception e) { return 0; }
    }
}