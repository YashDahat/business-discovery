package com.business.discovery.worker.config;

import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.business.discovery.worker.service.llm.generator.claude.ClaudeLlmGeneratorService;
import com.business.discovery.worker.service.llm.generator.gemini.GeminiLlmGeneratorService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class LlmConfigTest {

    private LlmConfig buildConfig() {
        LlmConfig config = new LlmConfig();
        ReflectionTestUtils.setField(config, "anthropicApiKey", "test-anthropic-key");
        ReflectionTestUtils.setField(config, "anthropicModel", "claude-sonnet-4-6");
        ReflectionTestUtils.setField(config, "geminiApiKey", "test-gemini-key");
        ReflectionTestUtils.setField(config, "geminiProModel", "gemini-2.5-pro-preview-03-25");
        ReflectionTestUtils.setField(config, "geminiFlashModel", "gemini-2.5-flash-preview-04-17");
        ReflectionTestUtils.setField(config, "geminiProMaxTokens", 65536);
        ReflectionTestUtils.setField(config, "geminiFlashMaxTokens", 8192);
        ReflectionTestUtils.setField(config, "geminiProTimeoutSeconds", 300);
        ReflectionTestUtils.setField(config, "geminiFlashTimeoutSeconds", 90);
        return config;
    }

    @Test
    void geminiPro_returnsGeminiImpl() {
        LlmGeneratorService service = buildConfig().geminiPro();
        assertThat(service).isInstanceOf(GeminiLlmGeneratorService.class);
    }

    @Test
    void geminiFlash_returnsGeminiImpl() {
        LlmGeneratorService service = buildConfig().geminiFlash();
        assertThat(service).isInstanceOf(GeminiLlmGeneratorService.class);
    }

    @Test
    void claudeSonnet_returnsClaudeImpl() {
        LlmGeneratorService service = buildConfig().claudeSonnet();
        assertThat(service).isInstanceOf(ClaudeLlmGeneratorService.class);
    }

    @Test
    void geminiProAndFlash_areDistinctInstances() {
        LlmConfig config = buildConfig();
        assertThat(config.geminiPro()).isNotSameAs(config.geminiFlash());
    }
}
