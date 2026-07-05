package com.business.discovery.worker.config;

import com.business.discovery.worker.service.LlmTokenAccumulator;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.business.discovery.worker.service.llm.generator.claude.ClaudeLlmGeneratorService;
import com.business.discovery.worker.service.llm.generator.gemini.GeminiLlmGeneratorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
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

    // arch spec JSON can be 500+ lines for a full-platform project — needs room
    @Value("${worker.llm.gemini.pro-max-tokens:65536}")
    private int geminiProMaxTokens;

    // single-file generation from instruction — 16384 guards against truncation
    @Value("${worker.llm.gemini.flash-max-tokens:16384}")
    private int geminiFlashMaxTokens;

    // Pro generates large arch spec JSON with deep reasoning — can take 3-5 min
    @Value("${worker.llm.gemini.pro-timeout-seconds:300}")
    private int geminiProTimeoutSeconds;

    // Flash generates one file at a time — 90s is generous
    @Value("${worker.llm.gemini.flash-timeout-seconds:90}")
    private int geminiFlashTimeoutSeconds;

    // Thinking budget: GA thinking models need explicit budget; 0 = disabled (non-thinking models like Flash)
    @Value("${worker.llm.gemini.pro-thinking-budget:16384}")
    private int geminiProThinkingBudget;

    @Value("${worker.llm.gemini.flash-thinking-budget:0}")
    private int geminiFlashThinkingBudget;

    // Architecture spec planning — needs Gemini Pro's reasoning depth + high output limit
    @Bean("geminiPro")
    public LlmGeneratorService geminiPro(LlmTokenAccumulator accumulator,
                                         com.business.discovery.worker.service.LlmInteractionLogger interactionLogger) {
        var service = new GeminiLlmGeneratorService(geminiApiKey, geminiProModel,
                geminiProMaxTokens, java.time.Duration.ofSeconds(geminiProTimeoutSeconds),
                geminiProThinkingBudget, accumulator);
        service.setInteractionLogger(interactionLogger);
        return service;
    }

    // Backend / frontend / infra code generation — repetitive, Flash is sufficient and 10-15x cheaper
    @Bean("geminiFlash")
    public LlmGeneratorService geminiFlash(LlmTokenAccumulator accumulator,
                                           com.business.discovery.worker.service.LlmInteractionLogger interactionLogger) {
        var service = new GeminiLlmGeneratorService(geminiApiKey, geminiFlashModel,
                geminiFlashMaxTokens, java.time.Duration.ofSeconds(geminiFlashTimeoutSeconds),
                geminiFlashThinkingBudget, accumulator);
        service.setInteractionLogger(interactionLogger);
        return service;
    }

    // Validation error fixing — Claude Sonnet excels at spotting and correcting subtle code bugs
    @Bean("claudeSonnet")
    public LlmGeneratorService claudeSonnet(LlmTokenAccumulator accumulator,
                                            com.business.discovery.worker.service.LlmInteractionLogger interactionLogger) {
        var service = new ClaudeLlmGeneratorService(anthropicApiKey, anthropicModel, accumulator);
        service.setInteractionLogger(interactionLogger);
        return service;
    }
}
