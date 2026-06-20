package com.business.discovery.worker.service.llm;

import java.util.List;
import java.util.Map;

/**
 * Lightweight projection of the data nodes pass to LLM service methods.
 * Decouples LlmGeneratorService from JPA entities (which are in the model package).
 */
public record BriefContext(
        String businessName,
        String category,
        String location,
        String websiteType,
        List<String> mustHaveFeatures,
        List<String> niceToHaveFeatures,
        List<String> recommendedPages,
        Map<String, String> techStack,
        List<String> seoKeywords,
        String designDirection,
        String colorScheme,
        String tone,
        String competitorInsights,
        String industryInsights,
        String architecturalNotes,
        String requestedChanges,
        String projectHistory,  // null on first run; docs/PROJECT_HISTORY.md content on retries/changes
        String address,         // full street address from scraped business data
        String phone,           // business phone number
        String latitude,        // for Google Maps embed
        String longitude,       // for Google Maps embed
        String openHours        // formatted opening hours string
) {
}
