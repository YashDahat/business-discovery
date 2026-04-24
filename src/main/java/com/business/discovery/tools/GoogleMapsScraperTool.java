package com.business.discovery.tools;

import com.business.discovery.model.BusinessEntity;
import com.business.discovery.services.scraper.GoogleMapsScraperService;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleMapsScraperTool {

    private final GoogleMapsScraperService scraperService;

    @Tool("""
        Searches Google Maps for businesses matching the given keyword and location.
        Scrapes detailed business data including name, address, phone, website,
        coordinates, ratings, reviews, and opening hours.
        Persists results to the database and returns a summary.
        Example keyword: 'stationary shops in Khadki Pune'
        """)
    public String scrapeBusinesses(String keyword) {
        log.info("[TOOL CALLED] scrapeBusinesses — keyword: {}", keyword);

        try {
            // runId is null for now — will be wired in properly in AgentRun phase
            List<BusinessEntity> businesses = scraperService.scrapeAndPersist(keyword, null);

            if (businesses.isEmpty()) {
                return "No businesses found for keyword: " + keyword;
            }

            // Return a concise summary the LLM can reason about
            String summary = businesses.stream()
                    .map(b -> "- %s | %s | Rating: %s | Phone: %s | Website: %s".formatted(
                            b.getTitle(),
                            b.getAddress(),
                            b.getRating() != null ? b.getRating() : "N/A",
                            b.getPhone() != null ? b.getPhone() : "N/A",
                            b.getWebsite() != null ? b.getWebsite() : "N/A"
                    ))
                    .collect(Collectors.joining("\n"));

            return ("Successfully scraped and saved %d businesses for '%s':\n\n%s")
                    .formatted(businesses.size(), keyword, summary);

        } catch (Exception e) {
            log.error("[TOOL ERROR] scrapeBusinesses failed for keyword: {}", keyword, e);
            return "Scraping failed for keyword: " + keyword + " — Error: " + e.getMessage();
        }
    }
}