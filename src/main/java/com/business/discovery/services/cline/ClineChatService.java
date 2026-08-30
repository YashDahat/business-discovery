package com.business.discovery.services.cline;

import com.business.discovery.model.ArchitectBrief;
import com.business.discovery.model.ContainerTask;
import com.business.discovery.model.PlatformUser;
import com.business.discovery.repository.ArchitectBriefRepository;
import com.business.discovery.repository.ContainerTaskRepository;
import com.business.discovery.services.chatMemory.PostgresChatMemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Routes a project chat turn through the Cline sidecar, seeded with user + project context.
 *
 * Persists the turn into the same Postgres-backed {@code chat_memory} used by the rest of the chat
 * system, so {@code ChatService.getHistory()} renders Cline turns unchanged. The context handed to
 * Cline is capped at the latest N messages ({@code cline.context.max-messages}) — Cline itself has no
 * vector store, so long-term recall relies on its per-task persistence, not on us replaying history.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClineChatService {

    private final ClineClient clineClient;
    private final ProjectContextBuilder contextBuilder;
    private final PostgresChatMemoryStore memoryStore;
    private final ArchitectBriefRepository briefRepository;
    private final ContainerTaskRepository containerTaskRepository;
    // Empty when cline.memory.semantic.enabled=false (bean absent).
    private final Optional<SemanticMemoryService> semanticMemory;

    @Value("${cline.context.max-messages:5}")
    private int maxContextMessages;

    @Value("${cline.memory.semantic.top-k:3}")
    private int semanticTopK;

    @Value("${cline.memory.semantic.min-score:0.6}")
    private double semanticMinScore;

    public record ClineChatResult(Long sessionId, String reply) {}

    public ClineChatResult chat(Long sessionId, UUID briefId, PlatformUser user, String message, String grant) {
        ArchitectBrief brief = briefRepository.findById(briefId)
                .orElseThrow(() -> new IllegalArgumentException("ArchitectBrief not found: " + briefId));

        ContainerTask task = containerTaskRepository
                .findTopByBriefIdOrderByCreatedAtDesc(briefId)
                .orElse(null);

        String projectContext = contextBuilder.build(user, brief, task);

        // Recent window (continuity) — keep the raw texts to exclude them from semantic recall.
        List<ChatMessage> recentMsgs = recentMessages(sessionId, maxContextMessages);
        String recentHistory = renderMessages(recentMsgs);
        Set<String> recentTexts = rawTexts(recentMsgs);

        // Semantic recall (relevance) — older messages surfaced by meaning. Hybrid = both.
        String semanticBlock = "";
        if (semanticMemory.isPresent()) {
            List<String> recalled = semanticMemory.get()
                    .recall(sessionId, message, semanticTopK, recentTexts, semanticMinScore);
            if (!recalled.isEmpty()) {
                semanticBlock = "\n== RELEVANT EARLIER CONTEXT ==\n" + String.join("\n", recalled) + "\n";
            }
        }

        String context = projectContext + semanticBlock
                + (recentHistory.isBlank() ? ""
                    : "\n== RECENT CONVERSATION (last " + maxContextMessages + ") ==\n" + recentHistory);

        log.info("[ClineChat] session={} brief={} contextChars={} semantic={} recentMsgs<= {}",
                sessionId, briefId, context.length(), !semanticBlock.isEmpty(), maxContextMessages);

        ClineClient.ChatResponse response = clineClient.chat(sessionId.toString(), message, context, grant);

        persistTurn(sessionId, message, response.reply());
        return new ClineChatResult(sessionId, response.reply());
    }

    // Appends the user turn and Cline's reply to the shared chat_memory, and indexes both for semantic recall.
    private void persistTurn(Long sessionId, String userMessage, String reply) {
        List<ChatMessage> updated = new ArrayList<>(memoryStore.getMessages(sessionId));
        updated.add(UserMessage.from(userMessage));
        updated.add(AiMessage.from(reply));
        memoryStore.updateMessages(sessionId, updated);

        semanticMemory.ifPresent(sm -> {
            sm.index(sessionId, "user", userMessage);
            sm.index(sessionId, "assistant", reply);
        });
    }

    // Last `max` messages of the session (the recent window).
    private List<ChatMessage> recentMessages(Long sessionId, int max) {
        List<ChatMessage> all = memoryStore.getMessages(sessionId);
        if (all.isEmpty()) {
            return List.of();
        }
        return all.size() > max ? all.subList(all.size() - max, all.size()) : all;
    }

    private String renderMessages(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) {
            if (m.type() == ChatMessageType.USER) {
                sb.append("User: ").append(((UserMessage) m).singleText()).append("\n");
            } else if (m.type() == ChatMessageType.AI) {
                String text = ((AiMessage) m).text();
                sb.append("Assistant: ").append(text != null ? text : "").append("\n");
            }
        }
        return sb.toString();
    }

    // Raw message texts (unprefixed) — used to exclude window messages from semantic recall.
    private Set<String> rawTexts(List<ChatMessage> messages) {
        Set<String> texts = new LinkedHashSet<>();
        for (ChatMessage m : messages) {
            if (m.type() == ChatMessageType.USER) {
                texts.add(((UserMessage) m).singleText());
            } else if (m.type() == ChatMessageType.AI) {
                String text = ((AiMessage) m).text();
                if (text != null) texts.add(text);
            }
        }
        return texts;
    }
}
