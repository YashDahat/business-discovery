package com.business.discovery.worker.service.llm.generator.gemini;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.service.LlmTokenAccumulator;
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
import dev.langchain4j.model.googleai.GeminiThinkingConfig;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.output.FinishReason;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@Slf4j
public class GeminiLlmGeneratorService extends LlmGeneratorService {

    private static final int MAX_TOOL_ROUNDS = 30;

    private final ChatModel model;
    private final String modelKey;
    private final LlmTokenAccumulator accumulator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GeminiLlmGeneratorService(String apiKey, String modelName, int maxOutputTokens,
                                      Duration timeout, int thinkingBudget, LlmTokenAccumulator accumulator) {
        var builder = GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .maxOutputTokens(maxOutputTokens)
                .timeout(timeout)
                .temperature(0.3);
        log.info("[Gemini] model={} thinkingBudget={} maxOutputTokens={}", modelName, thinkingBudget, maxOutputTokens);
        // Apply thinkingConfig whenever the budget is non-negative. Budget 0 EXPLICITLY DISABLES
        // thinking: Gemini 2.5 Flash defaults to dynamic thinking, and when the config was skipped
        // (the old `> 0` guard) those thinking tokens silently ate the output-token budget and cut
        // generated files mid-token (yeti AdminMenuPage truncated at ~31K chars, finishReason
        // unrecorded, 2026-07-07). A negative budget leaves the model default in place.
        if (thinkingBudget >= 0) {
            builder.thinkingConfig(
                    GeminiThinkingConfig.builder().thinkingBudget(thinkingBudget).build());
        }
        this.model = builder.build();
        this.modelKey = modelName;
        this.accumulator = accumulator;
    }

    // ── Standard single-turn call (used by all generation except enrichment) ─

    @Override
    protected String modelName() {
        return modelKey;
    }

    @Override
    protected String doCallLlm(String systemPrompt, String userPrompt) {
        try {
            ChatResponse response = model.chat(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userPrompt));
            recordUsage(response);
            warnIfTruncated("single-turn generate", response);
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

            recordUsage(response);
            warnIfTruncated("enrichment-tools round " + round, response);
            AiMessage aiMessage = response.aiMessage();
            messages.add(aiMessage);

            if (!aiMessage.hasToolExecutionRequests()) {
                if (interactionLogger() != null) {
                    interactionLogger().log(modelKey, "enrichment-tools (" + round + " rounds)",
                            systemPrompt, userPrompt, aiMessage.text());
                }
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
                                   Function<List<String>, String> postRoundHook,
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

            recordUsage(response);
            warnIfTruncated("fix-agent round " + round, response);
            AiMessage aiMessage = response.aiMessage();
            messages.add(aiMessage);

            if (!aiMessage.hasToolExecutionRequests()) {
                log.info("[GeminiLlmGeneratorService] Fix agent completed after {} tool rounds", round);
                return true;
            }

            List<String> executedTools = new ArrayList<>();
            for (var req : aiMessage.toolExecutionRequests()) {
                String preview = req.arguments() != null && req.arguments().length() > 120
                        ? req.arguments().substring(0, 120) + "..." : req.arguments();
                log.info("[GeminiLlmGeneratorService] Fix agent tool: {}({})", req.name(), preview);
                String result = toolHandler.apply(req);
                executedTools.add(req.name());
                messages.add(ToolExecutionResultMessage.from(req, result));
            }

            String hookOutput = postRoundHook != null ? postRoundHook.apply(executedTools) : null;
            if (hookOutput != null && !hookOutput.isBlank()) {
                messages.add(UserMessage.from(hookOutput));
            }
        }

        log.warn("[GeminiLlmGeneratorService] Fix agent exhausted {} tool rounds without completing", maxRounds);
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void recordUsage(ChatResponse response) {
        if (response.tokenUsage() != null) {
            accumulator.record(modelKey,
                    response.tokenUsage().inputTokenCount(),
                    response.tokenUsage().outputTokenCount());
        }
    }

    /**
     * Logs finish reason and token usage, and warns loudly on {@link FinishReason#LENGTH} — the
     * signal that the output hit the token ceiling and was cut mid-token. Before this, truncation
     * was invisible: the yeti run truncated AdminMenuPage.tsx twice (~31K and ~21.7K chars) with no
     * recorded finish reason, so the cause could not be diagnosed. Recovery is owned by the caller's
     * completeness check (e.g. FrontendGeneratorNode); this only makes the cause observable.
     */
    private void warnIfTruncated(String context, ChatResponse response) {
        FinishReason reason = response.finishReason();
        Integer out = response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : null;
        Integer in  = response.tokenUsage() != null ? response.tokenUsage().inputTokenCount()  : null;
        if (reason == FinishReason.LENGTH) {
            log.warn("[Gemini] {} hit the output-token ceiling (finishReason=LENGTH) — response truncated. "
                    + "model={} inputTokens={} outputTokens={}. If this recurs, raise "
                    + "worker.llm.gemini.flash-max-tokens or lower the thinking budget.",
                    context, modelKey, in, out);
        } else {
            log.debug("[Gemini] {} finishReason={} model={} inputTokens={} outputTokens={}",
                    context, reason, modelKey, in, out);
        }
    }

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
