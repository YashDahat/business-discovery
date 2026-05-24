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
        Map<String, String> techStack,
        List<String> seoKeywords,
        String designDirection,
        String colorScheme,
        String tone,
        String competitorInsights,
        String industryInsights,
        String architecturalNotes,
        String requestedChanges
) {
}
