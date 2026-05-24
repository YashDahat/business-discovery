package com.business.discovery.worker.config;

import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.business.discovery.worker.service.llm.generator.claude.ClaudeLlmGeneratorService;
import com.business.discovery.worker.service.llm.generator.gemini.GeminiLlmGeneratorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {

    @Value("${worker.llm.provider:claude}")
    private String provider;

    @Value("${worker.llm.anthropic.api-key:}")
    private String anthropicApiKey;

    @Value("${worker.llm.anthropic.model:claude-sonnet-4-6}")
    private String anthropicModel;

    @Value("${worker.llm.gemini.api-key:}")
    private String geminiApiKey;

    @Value("${worker.llm.gemini.model:gemini-2.5-pro-preview-03-25}")
    private String geminiModel;

    @Bean
    public LlmGeneratorService llmGeneratorService() {
        return switch (provider.trim().toLowerCase()) {
            case "claude"  -> new ClaudeLlmGeneratorService(anthropicApiKey, anthropicModel);
            case "gemini"  -> new GeminiLlmGeneratorService(geminiApiKey, geminiModel);
            default        -> throw new IllegalStateException(
                    "Unknown LLM_PROVIDER: '" + provider + "'. Use 'claude' or 'gemini'.");
        };
    }
}
