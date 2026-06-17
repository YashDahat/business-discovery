package com.business.discovery.worker.util;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import lombok.extern.slf4j.Slf4j;

/**
 * Parses raw LLM text responses into usable values.
 *
 * Handles all known Gemini output quirks in one place:
 *   - Markdown fences (```json ... ```)
 *   - Prose prefix before the JSON block
 *   - Literal control characters inside strings (newline, tab — codes 10, 9)
 *   - Invalid backslash escapes (\` and similar)
 *   - camelCase keys when snake_case is expected
 *   - Wrapped objects ({"result": {...}}) — unwraps one level
 *   - Suspiciously short responses (< MIN_INSTRUCTION_CHARS treated as blank)
 */
@Slf4j
public final class LlmResponseParser {

    private static final int MIN_INSTRUCTION_CHARS = 80;

    private static final ObjectMapper LENIENT = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature())
            .enable(JsonReadFeature.ALLOW_LEADING_ZEROS_FOR_NUMBERS.mappedFeature())
            .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS.mappedFeature());

    private LlmResponseParser() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Strips markdown and parses the first JSON object found in the raw LLM response.
     * Throws {@link LlmParseException} if no valid JSON object can be extracted.
     */
    public static JsonNode parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new LlmParseException("LLM returned empty response");
        }

        String stripped = stripMarkdown(raw);
        String jsonBlock = extractJsonBlock(stripped);

        try {
            JsonNode node = LENIENT.readTree(jsonBlock);
            // Unwrap one level if the model wrapped the result in an outer object
            // e.g. {"result": {...}} or {"response": {...}}
            if (node.isObject() && node.size() == 1) {
                JsonNode inner = node.fields().next().getValue();
                if (inner.isObject() && inner.has("feature_instruction")) {
                    log.debug("[LlmResponseParser] Unwrapped single-key envelope");
                    return inner;
                }
            }
            return node;
        } catch (Exception e) {
            throw new LlmParseException("JSON parse failed: " + e.getMessage(), e);
        }
    }

    /**
     * Reads a string field from a JsonNode trying each key variant in order.
     * Returns null if none are found or all are blank.
     * Rejects values shorter than MIN_INSTRUCTION_CHARS as suspiciously truncated.
     */
    public static String getString(JsonNode node, String... keyVariants) {
        for (String key : keyVariants) {
            JsonNode field = node.path(key);
            if (!field.isMissingNode() && !field.isNull()) {
                String value = field.asText(null);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * Same as getString but enforces a minimum length — rejects suspiciously short
     * values that indicate a truncated or degraded LLM response.
     */
    public static String getInstruction(JsonNode node, String... keyVariants) {
        String value = getString(node, keyVariants);
        if (value != null && value.length() < MIN_INSTRUCTION_CHARS) {
            log.warn("[LlmResponseParser] Instruction too short ({} chars) — treating as blank: {}",
                    value.length(), value);
            return null;
        }
        return value;
    }

    /**
     * Strips markdown fences from LLM text responses.
     * Handles: ```json ... ```, ``` ... ```, and prose with an embedded fence.
     */
    public static String stripMarkdown(String raw) {
        if (raw == null) return "";
        String trimmed = raw.strip();

        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```[a-zA-Z]*\\n?", "");
            int end = trimmed.lastIndexOf("```");
            if (end >= 0) trimmed = trimmed.substring(0, end).strip();
            return trimmed;
        }

        int fenceStart = trimmed.indexOf("```");
        if (fenceStart >= 0) {
            String afterFence = trimmed.substring(fenceStart);
            afterFence = afterFence.replaceFirst("^```[a-zA-Z]*\\n?", "");
            int end = afterFence.lastIndexOf("```");
            if (end >= 0) afterFence = afterFence.substring(0, end).strip();
            return afterFence;
        }

        return trimmed;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Finds the first '{' and its matching '}' to extract a JSON object block.
     * Handles prose prefix like "Sure! Here is the JSON: {...}".
     */
    private static String extractJsonBlock(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            throw new LlmParseException("No JSON object found in LLM response. Raw (first 300): "
                    + truncate(text, 300));
        }

        int depth = 0;
        boolean inString = false;
        boolean escape = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            if (escape) { escape = false; continue; }
            if (c == '\\' && inString) { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;

            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }

        throw new LlmParseException("Unclosed JSON object in LLM response — possible truncation. Raw (first 300): "
                + truncate(text, 300));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ── Exception type ────────────────────────────────────────────────────────

    public static class LlmParseException extends RuntimeException {
        public LlmParseException(String message) { super(message); }
        public LlmParseException(String message, Throwable cause) { super(message, cause); }
    }
}
