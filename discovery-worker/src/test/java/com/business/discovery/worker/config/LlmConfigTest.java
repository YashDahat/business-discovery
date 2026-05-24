package com.business.discovery.worker.config;

import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.business.discovery.worker.service.llm.generator.claude.ClaudeLlmGeneratorService;
import com.business.discovery.worker.service.llm.generator.gemini.GeminiLlmGeneratorService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmConfigTest {

    private LlmConfig buildConfig(String provider) {
        LlmConfig config = new LlmConfig();
        ReflectionTestUtils.setField(config, "provider", provider);
        ReflectionTestUtils.setField(config, "anthropicApiKey", "test-anthropic-key");
        ReflectionTestUtils.setField(config, "anthropicModel", "claude-sonnet-4-6");
        ReflectionTestUtils.setField(config, "geminiApiKey", "test-gemini-key");
        ReflectionTestUtils.setField(config, "geminiModel", "gemini-2.5-pro-preview-03-25");
        return config;
    }

    @Test
    void whenProviderIsClaude_thenClaudeImplIsReturned() {
        LlmGeneratorService service = buildConfig("claude").llmGeneratorService();
        assertThat(service).isInstanceOf(ClaudeLlmGeneratorService.class);
    }

    @Test
    void whenProviderIsGemini_thenGeminiImplIsReturned() {
        LlmGeneratorService service = buildConfig("gemini").llmGeneratorService();
        assertThat(service).isInstanceOf(GeminiLlmGeneratorService.class);
    }

    @Test
    void whenProviderIsClaude_caseInsensitive() {
        LlmGeneratorService service = buildConfig("CLAUDE").llmGeneratorService();
        assertThat(service).isInstanceOf(ClaudeLlmGeneratorService.class);
    }

    @Test
    void whenProviderIsUnknown_thenIllegalStateException() {
        assertThatThrownBy(() -> buildConfig("openai").llmGeneratorService())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown LLM_PROVIDER");
    }
}
