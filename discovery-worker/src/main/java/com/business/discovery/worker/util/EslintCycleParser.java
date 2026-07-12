package com.business.discovery.worker.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts import cycle violations from eslint's {@code --format json} output.
 *
 * <p>Only messages whose {@code ruleId} is the import-x (or legacy import) no-cycle rule are
 * returned — every other lint message (unused vars, etc.) is ignored. A cycle is a RUNTIME defect
 * that tsc and vite build clean, so this is the only gate that sees it.
 *
 * <p>Returns an empty list when the input is not the expected eslint JSON array. That is
 * deliberate: the caller must NOT fail a build because eslint itself crashed or emitted an
 * unparseable diagnostic — only an actual, parsed cycle should fail the stage. "Could not
 * determine" and "no cycles" are intentionally indistinguishable to the fail path, and the caller
 * separately warns on a non-zero eslint exit with empty output.
 */
@Slf4j
public final class EslintCycleParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Accept both the import-x fork and the legacy eslint-plugin-import rule id.
    private static final String RULE_IMPORT_X = "import-x/no-cycle";
    private static final String RULE_IMPORT = "import/no-cycle";

    private EslintCycleParser() {}

    /** One import-x/no-cycle violation: the file it was reported on, its line, and the message. */
    public record Cycle(String filePath, int line, String message) {}

    public static List<Cycle> parse(String eslintJson) {
        List<Cycle> cycles = new ArrayList<>();
        if (eslintJson == null || eslintJson.isBlank()) return cycles;

        JsonNode root;
        try {
            root = MAPPER.readTree(eslintJson);
        } catch (Exception e) {
            // Not valid JSON — eslint likely crashed (missing plugin, config error). Caller warns.
            log.debug("[EslintCycleParser] Output was not valid eslint JSON: {}", e.getMessage());
            return cycles;
        }
        if (!root.isArray()) return cycles;

        for (JsonNode fileResult : root) {
            String filePath = fileResult.path("filePath").asText("");
            JsonNode messages = fileResult.path("messages");
            if (!messages.isArray()) continue;
            for (JsonNode msg : messages) {
                String ruleId = msg.path("ruleId").asText("");
                if (RULE_IMPORT_X.equals(ruleId) || RULE_IMPORT.equals(ruleId)) {
                    cycles.add(new Cycle(
                            filePath,
                            msg.path("line").asInt(0),
                            msg.path("message").asText("Dependency cycle detected")));
                }
            }
        }
        return cycles;
    }
}
