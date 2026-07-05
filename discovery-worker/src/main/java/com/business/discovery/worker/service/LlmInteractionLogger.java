package com.business.discovery.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

/**
 * Persists every LLM interaction as JSONL in the workspace so failed generations
 * carry their own evidence: when a run fails, docs/llm/interactions.jsonl is
 * force-added to the failure push and reviewers can see exactly what each model
 * was asked and what it answered — not just the code it produced.
 *
 * The directory is gitignored so successful runs don't ship prompts into client
 * repos; WorkerOrchestrator force-adds it only on the failure path.
 *
 * Fields are truncated at worker.llm.log.max-chars (default 20K) to keep the file
 * useful: full arch-spec and enrichment exchanges fit, while the 200K-char
 * dependency contexts of file generation are cut to their head.
 */
@Component
@Slf4j
public class LlmInteractionLogger {

    public static final String LOG_DIR = "docs/llm";
    public static final String LOG_FILE = "interactions.jsonl";

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${worker.llm.log.max-chars:20000}")
    private int maxChars;

    @Value("${worker.llm.log.enabled:true}")
    private boolean enabled;

    private volatile Path workspace;

    /** Called once by WorkerOrchestrator before the pipeline runs. */
    public void init(Path workspace) {
        this.workspace = workspace;
    }

    /**
     * Appends one interaction. Never throws — logging must not break generation.
     * label examples: "single-turn", "enrichment-tools", "fix-agent-round-3".
     */
    public synchronized void log(String model, String label,
                                 String systemPrompt, String userPrompt, String response) {
        if (!enabled || workspace == null) return;
        try {
            Path dir = workspace.resolve(LOG_DIR);
            Files.createDirectories(dir);

            ObjectNode entry = mapper.createObjectNode();
            entry.put("ts", LocalDateTime.now().toString());
            entry.put("model", model);
            entry.put("label", label);
            entry.put("system_chars", systemPrompt == null ? 0 : systemPrompt.length());
            entry.put("user_chars", userPrompt == null ? 0 : userPrompt.length());
            entry.put("response_chars", response == null ? 0 : response.length());
            entry.put("system_prompt", truncate(systemPrompt));
            entry.put("user_prompt", truncate(userPrompt));
            entry.put("response", truncate(response));

            Files.writeString(dir.resolve(LOG_FILE), entry.toString() + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[LlmInteractionLogger] Could not append interaction: {}", e.getMessage());
        }
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() <= maxChars ? s
                : s.substring(0, maxChars) + "\n[...truncated " + (s.length() - maxChars) + " chars]";
    }
}
