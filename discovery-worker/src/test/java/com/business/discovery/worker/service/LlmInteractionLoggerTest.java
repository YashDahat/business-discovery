package com.business.discovery.worker.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LlmInteractionLoggerTest {

    @TempDir
    Path workspace;

    private LlmInteractionLogger logger;

    @BeforeEach
    void setUp() {
        logger = new LlmInteractionLogger();
        ReflectionTestUtils.setField(logger, "maxChars", 50);
        ReflectionTestUtils.setField(logger, "enabled", true);
    }

    @Test
    void appendsJsonlEntriesWithTruncation() throws Exception {
        logger.init(workspace);
        logger.log("gemini-2.5-pro", "single-turn", "sys", "u".repeat(200), "resp");
        logger.log("claude-sonnet-4-6", "fix-agent-round-1", null, null, "journal");

        Path file = workspace.resolve("docs/llm/interactions.jsonl");
        assertThat(file).exists();

        var lines = Files.readAllLines(file);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("\"model\":\"gemini-2.5-pro\"");
        assertThat(lines.get(0)).contains("\"user_chars\":200");
        assertThat(lines.get(0)).contains("truncated 150 chars");
        assertThat(lines.get(1)).contains("\"label\":\"fix-agent-round-1\"");
        assertThat(lines.get(1)).contains("\"response\":\"journal\"");
    }

    @Test
    void noopsBeforeInitAndWhenDisabled() {
        logger.log("m", "l", "s", "u", "r"); // not initialised — must not throw
        assertThat(workspace.resolve("docs/llm")).doesNotExist();

        ReflectionTestUtils.setField(logger, "enabled", false);
        logger.init(workspace);
        logger.log("m", "l", "s", "u", "r");
        assertThat(workspace.resolve("docs/llm")).doesNotExist();
    }
}
