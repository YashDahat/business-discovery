package com.business.discovery.worker.service.llm.generator.gemini;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.business.discovery.worker.util.WorkspaceReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@Slf4j
public class GeminiLlmGeneratorService extends LlmGeneratorService {

    private static final int MAX_TOOL_ROUNDS = 30;

    private final ChatModel model;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GeminiLlmGeneratorService(String apiKey, String modelName, int maxOutputTokens,
                                      Duration timeout) {
        this.model = GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .maxOutputTokens(maxOutputTokens)
                .timeout(timeout)
                .temperature(0.3)
                .build();
    }

    // ── Standard single-turn call (used by all generation except enrichment) ─

    @Override
    protected String callLlm(String systemPrompt, String userPrompt) {
        try {
            ChatResponse response = model.chat(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userPrompt));
            return response.aiMessage().text();
        } catch (Exception e) {
            throw wrapException(e);
        }
    }

    // ── Tool-use loop (used only by enrichArchitectureSpec in Pro bean) ──────

    @Override
    protected String callLlmWithTools(String systemPrompt, String userPrompt,
                                      WorkspaceReader workspaceReader) {
        ToolSpecification readFileTool = ToolSpecification.builder()
                .name("read_file")
                .description("Read content of an existing workspace file. " +
                             "Returns FILE_NOT_FOUND: {path} if the file does not exist.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("file_path",
                                "Relative path from project root, e.g. frontend/src/App.tsx")
                        .required(List.of("file_path"))
                        .build())
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(userPrompt));

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            ChatResponse response;
            try {
                response = model.chat(ChatRequest.builder()
                        .messages(messages)
                        .toolSpecifications(List.of(readFileTool))
                        .build());
            } catch (Exception e) {
                throw wrapException(e);
            }

            AiMessage aiMessage = response.aiMessage();
            messages.add(aiMessage);

            if (!aiMessage.hasToolExecutionRequests()) {
                return aiMessage.text();
            }

            for (var req : aiMessage.toolExecutionRequests()) {
                String filePath = extractParam(req.arguments(), "file_path");
                log.info("[GeminiLlmGeneratorService] Pro reads file via tool: {}", filePath);
                String content = workspaceReader.readFile(filePath);
                messages.add(ToolExecutionResultMessage.from(req, content));
            }
        }

        throw new WorkerException(FailureType.CODE,
                "Enrichment tool loop exceeded " + MAX_TOOL_ROUNDS + " rounds without finishing");
    }

    // ── Agentic fix loop (used by ErrorFixAgent with Pro bean) ────────────────

    @Override
    public boolean runFixAgentLoop(String systemPrompt,
                                   String userTrigger,
                                   List<ToolSpecification> tools,
                                   Function<ToolExecutionRequest, String> toolHandler,
                                   int maxRounds) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(userTrigger));

        for (int round = 0; round < maxRounds; round++) {
            ChatResponse response;
            try {
                response = model.chat(ChatRequest.builder()
                        .messages(messages)
                        .toolSpecifications(tools)
                        .build());
            } catch (Exception e) {
                throw wrapException(e);
            }

            AiMessage aiMessage = response.aiMessage();
            messages.add(aiMessage);

            if (!aiMessage.hasToolExecutionRequests()) {
                log.info("[GeminiLlmGeneratorService] Fix agent completed after {} tool rounds", round);
                return true;
            }

            for (var req : aiMessage.toolExecutionRequests()) {
                String preview = req.arguments() != null && req.arguments().length() > 120
                        ? req.arguments().substring(0, 120) + "..." : req.arguments();
                log.info("[GeminiLlmGeneratorService] Fix agent tool: {}({})", req.name(), preview);
                String result = toolHandler.apply(req);
                messages.add(ToolExecutionResultMessage.from(req, result));
            }
        }

        log.warn("[GeminiLlmGeneratorService] Fix agent exhausted {} tool rounds without completing", maxRounds);
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String extractParam(String argumentsJson, String key) {
        try {
            JsonNode node = objectMapper.readTree(argumentsJson);
            return node.path(key).asText();
        } catch (Exception e) {
            throw new WorkerException(FailureType.CODE,
                    "Failed to parse tool arguments [" + argumentsJson + "]: " + e.getMessage());
        }
    }

    private WorkerException wrapException(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        if (msg.contains("401") || msg.contains("403") || msg.contains("API key")) {
            return new WorkerException(FailureType.CONFIG_AUTH,
                    "Gemini auth failed — check GEMINI_API_KEY: " + msg, e);
        }
        return new WorkerException(FailureType.INFRA, "Gemini call failed: " + msg, e);
    }
}
