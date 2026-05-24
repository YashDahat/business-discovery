package com.business.discovery.worker.config;

import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.business.discovery.worker.service.llm.generator.claude.ClaudeLlmGeneratorService;
import com.business.discovery.worker.service.llm.generator.gemini.GeminiLlmGeneratorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {

    @Value("${worker.llm.anthropic.api-key:}")
    private String anthropicApiKey;

    @Value("${worker.llm.anthropic.model:claude-sonnet-4-6}")
    private String anthropicModel;

    @Value("${worker.llm.gemini.api-key:}")
    private String geminiApiKey;

    @Value("${worker.llm.gemini.pro-model:gemini-2.5-pro-preview-03-25}")
    private String geminiProModel;

    @Value("${worker.llm.gemini.flash-model:gemini-2.5-flash-preview-04-17}")
    private String geminiFlashModel;

    // File manifest planning — needs Gemini Pro's reasoning depth
    @Bean("geminiPro")
    public LlmGeneratorService geminiPro() {
        return new GeminiLlmGeneratorService(geminiApiKey, geminiProModel);
    }

    // Backend / frontend / infra code generation — repetitive, Flash is sufficient and 10-15x cheaper
    @Bean("geminiFlash")
    public LlmGeneratorService geminiFlash() {
        return new GeminiLlmGeneratorService(geminiApiKey, geminiFlashModel);
    }

    // Validation error fixing — Claude Sonnet excels at spotting and correcting subtle code bugs
    @Bean("claudeSonnet")
    public LlmGeneratorService claudeSonnet() {
        return new ClaudeLlmGeneratorService(anthropicApiKey, anthropicModel);
    }
}
