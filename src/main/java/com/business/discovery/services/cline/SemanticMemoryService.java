package com.business.discovery.services.cline;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Semantic memory for the Cline project chat: embeds each message into pgvector and recalls the most
 * relevant past messages by meaning, scoped per chat session. Complements (does not replace) the recent
 * window in {@link ClineChatService}. Present only when {@code cline.memory.semantic.enabled} is on.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "cline.memory.semantic.enabled", havingValue = "true", matchIfMissing = true)
public class SemanticMemoryService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> store;

    public SemanticMemoryService(EmbeddingModel chatMemoryEmbeddingModel,
                                 EmbeddingStore<TextSegment> chatMemoryEmbeddingStore) {
        this.embeddingModel = chatMemoryEmbeddingModel;
        this.store = chatMemoryEmbeddingStore;
    }

    /** Remove all embeddings for a session — used to make re-indexing/backfill idempotent. */
    public void clearSession(Long sessionId) {
        try {
            store.removeAll(new IsEqualTo("sessionId", String.valueOf(sessionId)));
        } catch (Exception e) {
            log.warn("[SemanticMemory] clearSession failed (session={}): {}", sessionId, e.getMessage());
        }
    }

    /** Embed and store one message, tagged with its session + role. */
    public void index(Long sessionId, String role, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            Metadata md = Metadata.from(Map.<String, Object>of(
                    "sessionId", String.valueOf(sessionId),
                    "role", role,
                    "ts", String.valueOf(Instant.now().toEpochMilli())));
            TextSegment segment = TextSegment.from(text, md);
            Embedding embedding = embeddingModel.embed(text).content();
            store.add(embedding, segment);
        } catch (Exception e) {
            // Memory indexing is best-effort — never fail a chat turn over it.
            log.warn("[SemanticMemory] index failed (session={}): {}", sessionId, e.getMessage());
        }
    }

    /**
     * Return up to {@code topK} past messages for this session most semantically similar to {@code query},
     * excluding any already shown in the recent window and anything below {@code minScore}. Rendered as
     * "Role: text" lines.
     */
    public List<String> recall(Long sessionId, String query, int topK, Set<String> excludeTexts, double minScore) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        try {
            Embedding q = embeddingModel.embed(query).content();
            int fetch = topK + (excludeTexts != null ? excludeTexts.size() : 0);
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(q)
                    .maxResults(fetch)
                    .minScore(minScore)
                    .filter(new IsEqualTo("sessionId", String.valueOf(sessionId)))
                    .build();

            List<EmbeddingMatch<TextSegment>> matches = store.search(request).matches();
            List<String> out = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> m : matches) {
                String text = m.embedded().text();
                if (excludeTexts != null && excludeTexts.contains(text)) {
                    continue;
                }
                String role = m.embedded().metadata().getString("role");
                out.add((role != null ? capitalize(role) : "Message") + ": " + text);
                if (out.size() >= topK) {
                    break;
                }
            }
            if (!out.isEmpty()) {
                log.info("[SemanticMemory] session={} recalled {} segment(s) (topScore={})",
                        sessionId, out.size(), matches.isEmpty() ? "n/a" : matches.get(0).score());
            }
            return out;
        } catch (Exception e) {
            log.warn("[SemanticMemory] recall failed (session={}): {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
