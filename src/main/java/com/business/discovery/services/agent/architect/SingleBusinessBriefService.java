package com.business.discovery.services.agent.architect;

import com.business.discovery.model.ArchitectBrief;
import com.business.discovery.model.BusinessEntity;
import com.business.discovery.repository.ArchitectBriefRepository;
import com.business.discovery.repository.BusinessEntityRepository;
import com.business.discovery.services.research.TavilyResearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates an ArchitectBrief for a single business that is already in the DB.
 * Skips scraping and scoring — runs Tavily research + Gemini synthesis directly.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SingleBusinessBriefService {

    private final BusinessEntityRepository businessRepo;
    private final ArchitectBriefRepository briefRepo;
    private final TavilyResearchService tavilyResearchService;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    // Tracks in-progress and failed generations so the poll endpoint can surface errors.
    private final ConcurrentHashMap<UUID, String> generationErrors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> inProgress = new ConcurrentHashMap<>();

    public enum GenerationStatus { GENERATING, COMPLETED, FAILED, NOT_STARTED }

    public record GenerationState(GenerationStatus status, String error) {}

    public GenerationState getState(UUID businessId) {
        if (briefRepo.existsByBusinessId(businessId)) return new GenerationState(GenerationStatus.COMPLETED, null);
        if (generationErrors.containsKey(businessId)) return new GenerationState(GenerationStatus.FAILED, generationErrors.get(businessId));
        if (inProgress.containsKey(businessId)) return new GenerationState(GenerationStatus.GENERATING, null);
        return new GenerationState(GenerationStatus.NOT_STARTED, null);
    }

    public void clearError(UUID businessId) {
        generationErrors.remove(businessId);
        inProgress.remove(businessId);
    }

    /**
     * Async entry point — returns immediately.
     * Caller polls GET /api/v2/architect/business/{businessId}/brief/status for progress.
     */
    @Async
    public CompletableFuture<UUID> generateAsync(UUID businessId) {
        inProgress.put(businessId, true);
        generationErrors.remove(businessId);
        try {
            UUID briefId = generate(businessId);
            inProgress.remove(businessId);
            return CompletableFuture.completedFuture(briefId);
        } catch (Exception e) {
            inProgress.remove(businessId);
            String msg = extractMessage(e);
            generationErrors.put(businessId, msg);
            log.error("[SingleBrief] Generation failed for businessId={}: {}", businessId, msg);
            return CompletableFuture.failedFuture(e);
        }
    }

    private String extractMessage(Exception e) {
        String msg = e.getMessage();
        if (msg != null && msg.contains("\"message\"")) {
            try {
                int start = msg.indexOf("\"message\"") + 11;
                int end = msg.indexOf("\"", start + 1);
                if (end > start) return msg.substring(start, end).replace("\"", "").trim();
            } catch (Exception ignored) {}
        }
        return msg != null ? msg : "Unknown error";
    }

    public UUID generate(UUID businessId) {
        BusinessEntity business = businessRepo.findById(businessId)
                .orElseThrow(() -> new NoSuchElementException("Business not found: " + businessId));

        if (briefRepo.existsByBusinessId(businessId)) {
            log.info("[SingleBrief] Brief already exists for {} — returning existing", business.getTitle());
            return briefRepo.findByBusinessId(businessId)
                    .orElseThrow().getId();
        }

        log.info("[SingleBrief] Generating brief for: {}", business.getTitle());

        String category = business.getCategory() != null ? business.getCategory() : "local business";
        String location = business.getAddress() != null ? business.getAddress() : "India";

        String businessResearch   = runSearch("%s %s %s reviews online presence".formatted(
                business.getTitle(), category, location));
        String industryResearch   = tavilyResearchService.researchBusinessCategory(category, location);
        String competitorResearch = tavilyResearchService.researchCompetitors(category, location);
        String seoResearch        = tavilyResearchService.researchSeoKeywords(category, location);
        String techStackResearch  = tavilyResearchService.researchTechStack(category);

        String prompt = buildPrompt(business, businessResearch, industryResearch,
                competitorResearch, seoResearch, techStackResearch);

        log.info("[SingleBrief] Calling Gemini for: {}", business.getTitle());
        String rawResponse = chatModel.chat(prompt);

        ArchitectBrief brief = parseBrief(rawResponse, business);
        ArchitectBrief saved = briefRepo.save(brief);

        log.info("[SingleBrief] Brief saved — id={} businessId={}", saved.getId(), businessId);
        return saved.getId();
    }

    private String runSearch(String query) {
        try {
            return tavilyResearchService.search(query);
        } catch (Exception e) {
            log.warn("[SingleBrief] Tavily search failed for '{}': {}", query, e.getMessage());
            return "Research unavailable";
        }
    }

    private String buildPrompt(BusinessEntity b,
                               String businessResearch,
                               String industryResearch,
                               String competitorResearch,
                               String seoResearch,
                               String techStackResearch) {
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
                Estimated Revenue: %s

                === BUSINESS SPECIFIC RESEARCH ===
                %s

                === INDUSTRY CONTEXT ===
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
                  "designDirection": "2-3 sentences on visual style and layout.",
                  "colorScheme": "Specific palette with hex codes.",
                  "tone": "Communication personality in 1-2 sentences.",
                  "competitorInsights": "...",
                  "industryInsights": "...",
                  "architecturalNotes": "..."
                }

                Rules:
                - Base all decisions on THIS specific business, not generic knowledge
                - Consider their review count and rating when recommending complexity
                - Return ONLY the JSON object, no markdown fences
                """.formatted(
                b.getTitle(),
                b.getCategory(),
                b.getAddress(),
                b.getPhone(),
                b.getRating(),
                b.getReviewCount(),
                b.getPriceRange(),
                b.getWebsite() != null ? b.getWebsite() : "none",
                b.getReservationLink() != null ? "yes" : "no",
                b.getOrderOnlineLink() != null ? "yes" : "no",
                b.getMenuLink() != null ? "yes" : "no",
                b.getRevenueEstimate(),
                businessResearch,
                industryResearch,
                competitorResearch,
                seoResearch,
                techStackResearch,
                b.getTitle()
        );
    }

    @SuppressWarnings("unchecked")
    private ArchitectBrief parseBrief(String raw, BusinessEntity business) {
        String json = raw.replaceAll("```json", "").replaceAll("```", "").trim();
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            return ArchitectBrief.builder()
                    .runId(UUID.nameUUIDFromBytes(("single-brief-" + business.getId()).getBytes()))
                    .businessId(business.getId())
                    .businessCategory(business.getCategory())
                    .location(business.getAddress())
                    .businessCount(1)
                    .averageRating(business.getRating())
                    .websiteAdoptionRate(business.getWebsite() != null ? 100.0 : 0.0)
                    .tier1Count("TIER_1".equals(business.getBusinessTier()) ? 1 : 0)
                    .tier2Count("TIER_2".equals(business.getBusinessTier()) ? 1 : 0)
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
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Gemini brief response: " + e.getMessage(), e);
        }
    }

    private ArchitectBrief.WebsiteType parseWebsiteType(Map<String, Object> parsed) {
        try {
            return ArchitectBrief.WebsiteType.valueOf(str(parsed.get("websiteType")).toUpperCase());
        } catch (Exception e) {
            return ArchitectBrief.WebsiteType.INFORMATIONAL;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> toList(Object val) {
        if (val instanceof List<?> list) return list.stream().map(Object::toString).toList();
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> toMap(Object val) {
        if (val instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            e -> e.getKey().toString(), e -> e.getValue().toString()));
        }
        return Map.of();
    }

    private String str(Object val) {
        return val != null ? val.toString() : "";
    }
}
