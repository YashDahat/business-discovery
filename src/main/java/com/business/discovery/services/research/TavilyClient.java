package com.business.discovery.services.research;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin client for Tavily's REST API ({@code https://api.tavily.com}), covering the capabilities the
 * langchain4j {@code WebSearchEngine} does not expose: {@code /extract}, {@code /crawl} and
 * {@code /map}, plus a full-control {@code /search} that returns Tavily's structured JSON verbatim.
 *
 * Kept separate from {@link TavilyResearchService} (which wraps langchain4j for the Architect nodes):
 * this client hands back the raw JSON payload so callers — notably the Cline MCP web tools — receive
 * the complete structured result (urls, scores, answer, raw content) rather than a flattened string.
 *
 * Auth is the API key as a Bearer token. Read timeout is generous because {@code /crawl} can walk many
 * pages in one call.
 */
@Slf4j
@Component
public class TavilyClient {

    private final RestClient restClient;
    private final int defaultMaxResults;

    public TavilyClient(
            @Value("${tavily.base-url:https://api.tavily.com}") String baseUrl,
            @Value("${tavily.api-key}") String apiKey,
            @Value("${tavily.max-results:5}") int defaultMaxResults) {
        this.defaultMaxResults = defaultMaxResults;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofMinutes(3).toMillis());

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * Web search. Returns Tavily's raw JSON: {@code answer}, {@code results[]} (title, url, content,
     * score) and optionally {@code raw_content} per result.
     */
    public String search(String query, String searchDepth, String topic, Integer maxResults,
                         Boolean includeAnswer, Boolean includeRawContent,
                         List<String> includeDomains, List<String> excludeDomains) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        putIfPresent(body, "search_depth", searchDepth);
        putIfPresent(body, "topic", topic);
        body.put("max_results", maxResults != null ? maxResults : defaultMaxResults);
        putIfPresent(body, "include_answer", includeAnswer);
        putIfPresent(body, "include_raw_content", includeRawContent);
        putIfNotEmpty(body, "include_domains", includeDomains);
        putIfNotEmpty(body, "exclude_domains", excludeDomains);
        return post("/search", body, "search q=" + query);
    }

    /** Extract clean content (and optionally images) from one or more URLs. */
    public String extract(List<String> urls, String extractDepth, Boolean includeImages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("urls", urls);
        putIfPresent(body, "extract_depth", extractDepth);
        putIfPresent(body, "include_images", includeImages);
        return post("/extract", body, "extract urls=" + urls);
    }

    /** Crawl a site starting from {@code url}, following links to gather page content. */
    public String crawl(String url, Integer maxDepth, Integer limit, String instructions,
                        String extractDepth) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", url);
        putIfPresent(body, "max_depth", maxDepth);
        putIfPresent(body, "limit", limit);
        putIfPresent(body, "instructions", instructions);
        putIfPresent(body, "extract_depth", extractDepth);
        return post("/crawl", body, "crawl url=" + url);
    }

    /** Map a site's structure (discover URLs) starting from {@code url}, without extracting content. */
    public String map(String url, Integer maxDepth, Integer limit, String instructions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", url);
        putIfPresent(body, "max_depth", maxDepth);
        putIfPresent(body, "limit", limit);
        putIfPresent(body, "instructions", instructions);
        return post("/map", body, "map url=" + url);
    }

    private String post(String path, Map<String, Object> body, String logSummary) {
        log.info("[TavilyClient] POST {} — {}", path, logSummary);
        String json = restClient.post()
                .uri(path)
                .body(body)
                .retrieve()
                .body(String.class);
        return json != null ? json : "{}";
    }

    private static void putIfPresent(Map<String, Object> body, String key, Object value) {
        if (value != null) {
            body.put(key, value);
        }
    }

    private static void putIfNotEmpty(Map<String, Object> body, String key, List<String> value) {
        if (value != null && !value.isEmpty()) {
            body.put(key, value);
        }
    }
}
