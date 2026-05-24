package com.business.discovery.worker.service.llm.generator.claude;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Calls Anthropic Messages API directly via Java HttpClient.
 * No additional SDK dependency — uses only Java 17 standard library + Jackson.
 */
@Slf4j
public class ClaudeLlmGeneratorService extends LlmGeneratorService {

    private static final String MESSAGES_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

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

    @Override
    protected String callLlm(String systemPrompt, String userPrompt) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", MAX_TOKENS);
            body.put("system", systemPrompt);

            ArrayNode messages = body.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(MESSAGES_URL))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("content-type", "application/json")
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();

            if (status == 401 || status == 403) {
                throw new WorkerException(FailureType.CONFIG_AUTH,
                        "Anthropic auth failed — check ANTHROPIC_API_KEY. HTTP " + status);
            }
            if (status >= 500) {
                throw new WorkerException(FailureType.INFRA,
                        "Anthropic server error HTTP " + status + ": " + response.body());
            }
            if (status != 200) {
                throw new WorkerException(FailureType.CODE,
                        "Anthropic unexpected HTTP " + status + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            return root.path("content").get(0).path("text").asText();

        } catch (WorkerException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new WorkerException(FailureType.INFRA, "Anthropic request timed out", e);
        } catch (Exception e) {
            throw new WorkerException(FailureType.INFRA,
                    "Anthropic call failed: " + e.getMessage(), e);
        }
    }
}
