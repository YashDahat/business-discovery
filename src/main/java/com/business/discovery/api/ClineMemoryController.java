package com.business.discovery.api;

import com.business.discovery.services.cline.SemanticMemoryBackfill;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * Admin ops for the Cline semantic chat memory. Operator-only (POST under /api/** per SecurityConfig).
 */
@Slf4j
@RestController
@RequestMapping("/api/v4/cline/memory")
@RequiredArgsConstructor
public class ClineMemoryController {

    // Empty when cline.memory.semantic.enabled=false.
    private final Optional<SemanticMemoryBackfill> backfill;

    /** Index all existing chat_memory sessions into the vector store (idempotent, re-runnable). */
    @PostMapping("/backfill")
    public ResponseEntity<Map<String, Object>> backfill() {
        if (backfill.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "semantic memory is disabled (cline.memory.semantic.enabled=false)"));
        }
        SemanticMemoryBackfill.BackfillResult result = backfill.get().run();
        return ResponseEntity.ok(Map.of(
                "sessions", result.sessions(),
                "messagesIndexed", result.messagesIndexed()));
    }
}
