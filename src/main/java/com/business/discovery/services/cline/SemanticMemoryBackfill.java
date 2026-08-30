package com.business.discovery.services.cline;

import com.business.discovery.model.ChatMemoryEntity;
import com.business.discovery.repository.ChatMemoryRepository;
import com.business.discovery.services.chatMemory.PostgresChatMemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * One-time (re-runnable) backfill: indexes every existing {@code chat_memory} session into the vector store
 * so historical conversations get semantic recall, not just new turns. Idempotent — each session's vectors
 * are cleared before re-indexing, so it can be run again safely. Present only when semantic memory is on.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cline.memory.semantic.enabled", havingValue = "true", matchIfMissing = true)
public class SemanticMemoryBackfill {

    private final ChatMemoryRepository chatMemoryRepository;
    private final PostgresChatMemoryStore memoryStore;
    private final SemanticMemoryService semanticMemory;

    public record BackfillResult(int sessions, int messagesIndexed) {}

    public BackfillResult run() {
        List<ChatMemoryEntity> all = chatMemoryRepository.findAll();
        int sessions = 0;
        int indexed = 0;

        for (ChatMemoryEntity entity : all) {
            Long sessionId = entity.getMemoryId();
            semanticMemory.clearSession(sessionId); // idempotent re-run

            List<ChatMessage> messages = memoryStore.getMessages(sessionId);
            int perSession = 0;
            for (ChatMessage m : messages) {
                if (m.type() == ChatMessageType.USER) {
                    semanticMemory.index(sessionId, "user", ((UserMessage) m).singleText());
                    perSession++;
                } else if (m.type() == ChatMessageType.AI) {
                    String text = ((AiMessage) m).text();
                    if (text != null && !text.isBlank()) {
                        semanticMemory.index(sessionId, "assistant", text);
                        perSession++;
                    }
                }
            }
            sessions++;
            indexed += perSession;
            log.info("[SemanticBackfill] session={} indexed {} message(s)", sessionId, perSession);
        }

        log.info("[SemanticBackfill] done — {} session(s), {} message(s) indexed", sessions, indexed);
        return new BackfillResult(sessions, indexed);
    }
}
