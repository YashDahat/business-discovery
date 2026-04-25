package com.business.discovery.configuration;

import dev.langchain4j.web.search.WebSearchEngine;
import dev.langchain4j.web.search.tavily.TavilyWebSearchEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TavilyConfig {

    @Value("${tavily.api-key}")
    private String apiKey;

    @Value("${tavily.include-answer:true}")
    private boolean includeAnswer;

    @Value("${tavily.include-raw-content:false}")
    private boolean includeRawContent;

    @Bean
    public WebSearchEngine tavilyWebSearchEngine() {
        return TavilyWebSearchEngine.builder()
                .apiKey(apiKey)
                .includeAnswer(includeAnswer)
                .includeRawContent(includeRawContent)
                .build();
    }
}