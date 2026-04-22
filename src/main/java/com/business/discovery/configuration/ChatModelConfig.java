package com.business.discovery.configuration;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class ChatModelConfig {

    @Bean
    public ChatModel chatModel(@Value("${langchain4j.google-ai-gemini.chat-model.api-key}") String apiKey) {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gemini-2.5-pro") // Ensure this supports reasoning
                .temperature(0.2) // Low temperature for architectural consistency
                .timeout(Duration.ofSeconds(60))
                .build();
    }
}