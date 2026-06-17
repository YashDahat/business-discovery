package com.business.discovery.worker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fetches Spring Initializr metadata once per container lifetime to:
 *   1. Get the current default Spring Boot version (never hardcode it)
 *   2. Validate LLM-provided dependency IDs against the real available list
 *
 * Metadata endpoint: GET https://start.spring.io
 * Accept: application/vnd.initializr.v2.2+json
 */
@Component
@Slf4j
public class SpringInitializrClient {

    private static final String METADATA_URL = "https://start.spring.io";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Cached after first fetch — metadata doesn't change during a container's lifetime
    private String cachedBootVersion = null;
    private Set<String> cachedDependencyIds = null;

    public String getDefaultBootVersion() {
        ensureMetadataLoaded();
        return cachedBootVersion;
    }

    /**
     * Filters the given list to only IDs that exist in Spring Initializr.
     * Unknown IDs are logged and dropped — prevents 400 from LLM hallucinations.
     */
    public List<String> filterValidDependencies(List<String> requested) {
        ensureMetadataLoaded();

        List<String> valid   = new ArrayList<>();
        List<String> unknown = new ArrayList<>();

        for (String id : requested) {
            if (cachedDependencyIds.contains(id)) {
                valid.add(id);
            } else {
                unknown.add(id);
            }
        }

        if (!unknown.isEmpty()) {
            log.warn("[SpringInitializrClient] Dropping unknown dependency IDs (not in Initializr): {}",
                    unknown);
        }
        log.info("[SpringInitializrClient] Using dependencies: {}", valid);
        return valid;
    }

    // ── Private ───────────────────────────────────────────────────────────

    private synchronized void ensureMetadataLoaded() {
        if (cachedBootVersion != null) return;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(METADATA_URL))
                    .header("Accept", "application/vnd.initializr.v2.2+json")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IOException("Spring Initializr metadata returned HTTP " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            cachedBootVersion = root.path("bootVersion").path("default").asText();
            cachedDependencyIds = extractDependencyIds(root);

            log.info("[SpringInitializrClient] Loaded metadata — Boot version: {}, {} dependencies available",
                    cachedBootVersion, cachedDependencyIds.size());

        } catch (Exception e) {
            log.warn("[SpringInitializrClient] Could not fetch metadata ({}), using fallback defaults",
                    e.getMessage());
            cachedBootVersion = "";           // omit from URL → Initializr picks current stable
            cachedDependencyIds = Set.of(     // well-known safe IDs as fallback
                    "web", "data-jpa", "postgresql", "lombok", "validation",
                    "actuator", "security", "mail", "data-redis", "cache",
                    "thymeleaf", "devtools", "test"
            );
        }
    }

    private Set<String> extractDependencyIds(JsonNode root) {
        Set<String> ids = new HashSet<>();
        JsonNode groups = root.path("dependencies").path("values");
        if (groups.isArray()) {
            for (JsonNode group : groups) {
                JsonNode items = group.path("values");
                if (items.isArray()) {
                    for (JsonNode item : items) {
                        String id = item.path("id").asText(null);
                        if (id != null && !id.isBlank()) ids.add(id);
                    }
                }
            }
        }
        return ids;
    }
}
