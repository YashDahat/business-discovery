package com.business.discovery.worker.service.llm.generator.gemini;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import lombok.extern.slf4j.Slf4j;

/**
 * Calls Gemini via LangChain4j — same library the master already uses.
 * API in langchain4j 1.13.0: ChatModel.chat(ChatMessage...) → ChatResponse.
 */
@Slf4j
public class GeminiLlmGeneratorService extends LlmGeneratorService {

    private final ChatModel model;

    public GeminiLlmGeneratorService(String apiKey, String modelName) {
        this.model = GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .maxOutputTokens(MAX_TOKENS)
                .temperature(0.3)
                .build();
    }

    @Override
    protected String callLlm(String systemPrompt, String userPrompt) {
        try {
            ChatResponse response = model.chat(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userPrompt));
            return response.aiMessage().text();
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("401") || msg.contains("403") || msg.contains("API key")) {
                throw new WorkerException(FailureType.CONFIG_AUTH,
                        "Gemini auth failed — check GEMINI_API_KEY: " + msg, e);
            }
            throw new WorkerException(FailureType.INFRA,
                    "Gemini call failed: " + msg, e);
        }
    }
}
