package com.business.discovery.worker.config;

import dev.langchain4j.web.search.WebSearchEngine;
import dev.langchain4j.web.search.tavily.TavilyWebSearchEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class TavilyConfig {

    @Value("${worker.tavily.api-key:}")
    private String apiKey;

    @Bean
    public WebSearchEngine tavilyWebSearchEngine() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[TavilyConfig] TAVILY_API_KEY not set — ErrorFixNode searches will return empty results");
            // Bean is created so the context starts; search() throws at call time,
            // which ErrorFixNode.search() catches and returns "Search unavailable."
            return request -> { throw new IllegalStateException("Tavily API key not configured"); };
        }
        return TavilyWebSearchEngine.builder()
                .apiKey(apiKey)
                .includeAnswer(true)
                .includeRawContent(false)
                .build();
    }
}
