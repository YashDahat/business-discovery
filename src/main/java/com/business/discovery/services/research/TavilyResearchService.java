package com.business.discovery.services.research;

import dev.langchain4j.web.search.WebSearchEngine;
import dev.langchain4j.web.search.WebSearchRequest;
import dev.langchain4j.web.search.WebSearchResults;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TavilyResearchService {

    private final WebSearchEngine tavilyWebSearchEngine;

    public String search(String query) {
        log.info("[TAVILY] Searching: {}", query);

        WebSearchResults results = tavilyWebSearchEngine.search(
                WebSearchRequest.builder()
                        .searchTerms(query)
                        .build()
        );

        String combined = results.results().stream()
                .map(r -> "Source: %s\n%s".formatted(r.url(), r.snippet()))
                .collect(Collectors.joining("\n\n---\n\n"));

        log.info("[TAVILY] {} results returned for: {}", results.results().size(), query);
        return combined;
    }

    public String researchBusinessCategory(String category, String location) {
        return search("what features do %s websites need in India %s 2025"
                .formatted(category, location));
    }

    public String researchCompetitors(String category, String location) {
        return search("popular %s apps and websites %s India customer expectations"
                .formatted(category, location));
    }

    public String researchTechStack(String category) {
        return search("best tech stack for %s booking management website India 2025"
                .formatted(category));
    }

    public String researchSeoKeywords(String category, String location) {
        return search("top SEO keywords for %s business in %s India"
                .formatted(category, location));
    }
}