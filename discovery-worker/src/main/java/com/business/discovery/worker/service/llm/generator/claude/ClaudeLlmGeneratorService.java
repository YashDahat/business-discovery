package com.business.discovery.worker.service.llm.generator.claude;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/**
 * Calls Anthropic Messages API directly via Java HttpClient.
 * No additional SDK dependency — uses only Java 17 standard library + Jackson.
 */
@Slf4j
public class ClaudeLlmGeneratorService extends LlmGeneratorService {

    private static final String MESSAGES_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    // Claude Sonnet 4.6 pricing (per 1M tokens)
    private static final double INPUT_COST_PER_M  = 3.00;
    private static final double OUTPUT_COST_PER_M = 15.00;

    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ClaudeLlmGeneratorService(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    // ── Single-turn call ──────────────────────────────────────────────────────

    @Override
    protected String callLlm(String systemPrompt, String userPrompt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", MAX_TOKENS);
        body.put("system", systemPrompt);

        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);

        JsonNode response = send(body);
        return response.path("content").get(0).path("text").asText();
    }

    // ── Agentic fix loop with tool use ────────────────────────────────────────

    @Override
    public boolean runFixAgentLoop(String systemPrompt,
                                   String userTrigger,
                                   List<ToolSpecification> tools,
                                   Function<ToolExecutionRequest, String> toolHandler,
                                   int maxRounds) {
        ArrayNode toolsJson = toAnthropicTools(tools);

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode firstMsg = messages.addObject();
        firstMsg.put("role", "user");
        firstMsg.put("content", userTrigger);

        long totalInputTokens       = 0;
        long totalOutputTokens      = 0;
        long totalCacheWriteTokens  = 0;
        long totalCacheReadTokens   = 0;

        for (int round = 0; round < maxRounds; round++) {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", MAX_TOKENS);

            // System prompt as block array so Anthropic can cache it across rounds.
            // Cache key = exact byte prefix up to the marked block — identical every round.
            ArrayNode systemArray = body.putArray("system");
            ObjectNode systemBlock = systemArray.addObject();
            systemBlock.put("type", "text");
            systemBlock.put("text", systemPrompt);
            systemBlock.putObject("cache_control").put("type", "ephemeral");

            body.set("tools", toolsJson);
            body.set("messages", messages);

            JsonNode response = send(body);

            long inputTok       = response.path("usage").path("input_tokens").asLong();
            long outputTok      = response.path("usage").path("output_tokens").asLong();
            long cacheWriteTok  = response.path("usage").path("cache_creation_input_tokens").asLong();
            long cacheReadTok   = response.path("usage").path("cache_read_input_tokens").asLong();
            totalInputTokens      += inputTok;
            totalOutputTokens     += outputTok;
            totalCacheWriteTokens += cacheWriteTok;
            totalCacheReadTokens  += cacheReadTok;

            log.info("[ErrorFixAgent/Claude] round={} in={} out={} cache_write={} cache_read={} | cumul cost=${:.4f}",
                    round + 1, inputTok, outputTok, cacheWriteTok, cacheReadTok,
                    tokenCost(totalInputTokens, totalOutputTokens, totalCacheWriteTokens, totalCacheReadTokens));

            JsonNode content   = response.path("content");
            String  stopReason = response.path("stop_reason").asText();

            // Add assistant turn to conversation history
            ObjectNode assistantMsg = messages.addObject();
            assistantMsg.put("role", "assistant");
            assistantMsg.set("content", content);

            if (!"tool_use".equals(stopReason)) {
                log.info("[ErrorFixAgent/Claude] Agent done after {} rounds — in={} out={} cache_write={} cache_read={} | cost=${:.4f}",
                        round + 1, totalInputTokens, totalOutputTokens,
                        totalCacheWriteTokens, totalCacheReadTokens,
                        tokenCost(totalInputTokens, totalOutputTokens, totalCacheWriteTokens, totalCacheReadTokens));
                return true;
            }

            // Execute every tool call the model requested
            ArrayNode toolResults = objectMapper.createArrayNode();
            for (JsonNode block : content) {
                if (!"tool_use".equals(block.path("type").asText())) continue;

                String toolId    = block.path("id").asText();
                String toolName  = block.path("name").asText();
                String inputJson = block.path("input").toString();

                String preview = inputJson.length() > 120 ? inputJson.substring(0, 120) + "..." : inputJson;
                log.info("[ErrorFixAgent/Claude] Tool: {}({})", toolName, preview);

                ToolExecutionRequest req = ToolExecutionRequest.builder()
                        .id(toolId)
                        .name(toolName)
                        .arguments(inputJson)
                        .build();

                String result = toolHandler.apply(req);

                ObjectNode resultBlock = toolResults.addObject();
                resultBlock.put("type", "tool_result");
                resultBlock.put("tool_use_id", toolId);
                resultBlock.put("content", result);
            }

            // Append tool results as the next user turn
            ObjectNode toolResultMsg = messages.addObject();
            toolResultMsg.put("role", "user");
            toolResultMsg.set("content", toolResults);
        }

        log.warn("[ErrorFixAgent/Claude] Max rounds {} exhausted — in={} out={} cache_write={} cache_read={} | cost=${:.4f}",
                maxRounds, totalInputTokens, totalOutputTokens,
                totalCacheWriteTokens, totalCacheReadTokens,
                tokenCost(totalInputTokens, totalOutputTokens, totalCacheWriteTokens, totalCacheReadTokens));
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Sends a request to the Anthropic Messages API and returns the parsed response body. */
    private JsonNode send(ObjectNode body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(MESSAGES_URL))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("anthropic-beta", "prompt-caching-2024-07-31")
                    .header("content-type", "application/json")
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status == 401 || status == 403) {
                throw new WorkerException(FailureType.CONFIG_AUTH,
                        "Anthropic auth failed — check ANTHROPIC_API_KEY. HTTP " + status);
            }
            if (status == 429) {
                throw new WorkerException(FailureType.CODE,
                        "Anthropic rate limit — HTTP 429: " + response.body());
            }
            if (status >= 500) {
                throw new WorkerException(FailureType.INFRA,
                        "Anthropic server error HTTP " + status + ": " + response.body());
            }
            if (status != 200) {
                throw new WorkerException(FailureType.CODE,
                        "Anthropic unexpected HTTP " + status + ": " + response.body());
            }

            return objectMapper.readTree(response.body());

        } catch (WorkerException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new WorkerException(FailureType.INFRA, "Anthropic request timed out", e);
        } catch (Exception e) {
            throw new WorkerException(FailureType.INFRA, "Anthropic call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Converts LangChain4j ToolSpecification list to Anthropic's native tool format.
     * ToolSpecification.parameters() returns JsonObjectSchema directly in LangChain4j 1.13.0.
     */
    private ArrayNode toAnthropicTools(List<ToolSpecification> tools) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (ToolSpecification tool : tools) {
            ObjectNode toolNode = arr.addObject();
            toolNode.put("name", tool.name());
            if (tool.description() != null) toolNode.put("description", tool.description());

            ObjectNode inputSchema = toolNode.putObject("input_schema");
            inputSchema.put("type", "object");

            JsonObjectSchema jos = tool.parameters();
            if (jos != null && jos.properties() != null) {
                ObjectNode props = inputSchema.putObject("properties");
                jos.properties().forEach((propName, propSchema) -> {
                    ObjectNode p = props.putObject(propName);
                    p.put("type", schemaType(propSchema));
                    String desc = schemaDescription(propSchema);
                    if (desc != null) p.put("description", desc);
                });
                if (jos.required() != null && !jos.required().isEmpty()) {
                    ArrayNode req = inputSchema.putArray("required");
                    jos.required().forEach(req::add);
                }
            }
        }
        // Mark the last tool for prompt caching — Anthropic caches the entire
        // system_prompt + tools prefix up to this point, saving ~800 tokens per round.
        if (!arr.isEmpty()) {
            ((ObjectNode) arr.get(arr.size() - 1))
                    .putObject("cache_control").put("type", "ephemeral");
        }
        return arr;
    }

    private static String schemaType(JsonSchemaElement schema) {
        if (schema instanceof JsonStringSchema)                                 return "string";
        if (schema instanceof dev.langchain4j.model.chat.request.json.JsonIntegerSchema) return "integer";
        if (schema instanceof dev.langchain4j.model.chat.request.json.JsonBooleanSchema) return "boolean";
        return "string";
    }

    private static String schemaDescription(JsonSchemaElement schema) {
        try {
            // All schema types in LangChain4j 1.13.0 expose description()
            return (String) schema.getClass().getMethod("description").invoke(schema);
        } catch (Exception e) {
            return null;
        }
    }

    private static double tokenCost(long inputTokens, long outputTokens,
                                    long cacheWriteTokens, long cacheReadTokens) {
        return (inputTokens       / 1_000_000.0 * INPUT_COST_PER_M)
             + (outputTokens      / 1_000_000.0 * OUTPUT_COST_PER_M)
             + (cacheWriteTokens  / 1_000_000.0 * INPUT_COST_PER_M * 1.25)
             + (cacheReadTokens   / 1_000_000.0 * INPUT_COST_PER_M * 0.10);
    }
}
