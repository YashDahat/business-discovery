package com.business.discovery.services.cline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Thin HTTP client for the Cline Node SDK sidecar (see cline-sidecar/).
 *
 * The sidecar embeds the {@code @cline} SDK and runs one Plan-mode (read-only) turn per call,
 * with its LLM provider pointed back at this app's OpenAI-compatible proxy
 * ({@link com.business.discovery.api.ClineLlmProxyController}) — so Cline never holds a provider key.
 */
@Slf4j
@Component
public class ClineClient {

    private final RestClient restClient;

    public ClineClient(@Value("${cline.sidecar.base-url}") String baseUrl) {
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofMinutes(5).toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public record ChatRequest(String sessionId, String message, String context, String grant) {}

    public record ChatResponse(String reply, Usage usage) {
        public record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {}
    }

    /**
     * Runs a single Cline turn for the given session. {@code context} is the user + project
     * preamble built by {@link ProjectContextBuilder}; {@code message} is the latest user turn;
     * {@code grant} is the scoped MCP grant the sidecar forwards on any MCP tool call (may be null).
     */
    public ChatResponse chat(String sessionId, String message, String context, String grant) {
        ChatResponse response = restClient.post()
                .uri("/chat")
                .body(new ChatRequest(sessionId, message, context, grant))
                .retrieve()
                .body(ChatResponse.class);

        if (response == null || response.reply() == null) {
            throw new IllegalStateException("Cline sidecar returned an empty reply");
        }
        return response;
    }
}
