package com.business.discovery.worker.config;

import com.business.discovery.worker.service.LlmInteractionLogger;
import com.business.discovery.worker.service.LlmTokenAccumulator;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.business.discovery.worker.service.llm.generator.claude.ClaudeLlmGeneratorService;
import com.business.discovery.worker.service.llm.generator.gemini.GeminiLlmGeneratorService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LlmConfigTest {

    private final LlmTokenAccumulator accumulator = mock(LlmTokenAccumulator.class);
    private final LlmInteractionLogger interactionLogger = mock(LlmInteractionLogger.class);

    private LlmConfig buildConfig() {
        LlmConfig config = new LlmConfig();
        ReflectionTestUtils.setField(config, "anthropicApiKey", "test-anthropic-key");
        ReflectionTestUtils.setField(config, "anthropicModel", "claude-sonnet-4-6");
        ReflectionTestUtils.setField(config, "geminiApiKey", "test-gemini-key");
        ReflectionTestUtils.setField(config, "geminiProModel", "gemini-2.5-pro");
        ReflectionTestUtils.setField(config, "geminiFlashModel", "gemini-2.5-flash");
        ReflectionTestUtils.setField(config, "geminiProMaxTokens", 65536);
        ReflectionTestUtils.setField(config, "geminiFlashMaxTokens", 16384);
        ReflectionTestUtils.setField(config, "geminiProTimeoutSeconds", 600);
        ReflectionTestUtils.setField(config, "geminiFlashTimeoutSeconds", 90);
        ReflectionTestUtils.setField(config, "geminiProThinkingBudget", 0);
        ReflectionTestUtils.setField(config, "geminiFlashThinkingBudget", 0);
        return config;
    }

    @Test
    void geminiPro_returnsGeminiImpl() {
        LlmGeneratorService service = buildConfig().geminiPro(accumulator, interactionLogger);
        assertThat(service).isInstanceOf(GeminiLlmGeneratorService.class);
    }

    @Test
    void geminiFlash_returnsGeminiImpl() {
        LlmGeneratorService service = buildConfig().geminiFlash(accumulator, interactionLogger);
        assertThat(service).isInstanceOf(GeminiLlmGeneratorService.class);
    }

    @Test
    void claudeSonnet_returnsClaudeImpl() {
        LlmGeneratorService service = buildConfig().claudeSonnet(accumulator, interactionLogger);
        assertThat(service).isInstanceOf(ClaudeLlmGeneratorService.class);
    }

    @Test
    void geminiProAndFlash_areDistinctInstances() {
        LlmConfig config = buildConfig();
        assertThat(config.geminiPro(accumulator, interactionLogger))
                .isNotSameAs(config.geminiFlash(accumulator, interactionLogger));
    }
}
