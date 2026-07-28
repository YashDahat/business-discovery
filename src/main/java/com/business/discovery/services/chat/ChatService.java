package com.business.discovery.services.chat;

import com.business.discovery.model.ChatMemoryEntity;
import com.business.discovery.repository.ChatMemoryRepository;
import com.business.discovery.services.chatMemory.PostgresChatMemoryStore;
import com.business.discovery.tools.GoogleMapsScraperTool;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ChatService {

    // Marks a deterministic pipeline note (queued/applied/failed) so getHistory()
    // can render it as a system message instead of a genuine LLM reply.
    private static final String SYSTEM_NOTE_PREFIX = "[SYSTEM] ";

    private final ChatModel chatModel;
    private final PostgresChatMemoryStore memoryStore;
    private final ChatMemoryRepository repository;
    private final GoogleMapsScraperTool googleMapsScraperTool;

    // AiServices-based assistant — handles tool calls automatically
    interface ArchitectAssistant {
        String chat(@MemoryId Long memoryId, @UserMessage String message);
    }

    private ArchitectAssistant architectAssistant;

    public ChatService(
            ChatModel chatModel,
            PostgresChatMemoryStore memoryStore,
            ChatMemoryRepository repository,
            GoogleMapsScraperTool googleMapsScraperTool) {
        this.chatModel = chatModel;
        this.memoryStore = memoryStore;
        this.repository = repository;
        this.googleMapsScraperTool = googleMapsScraperTool;
    }

    @PostConstruct
    public void init() {
        // Build the ChatMemoryProvider — tells AiServices how to
        // create/retrieve memory for each session ID
        ChatMemoryProvider chatMemoryProvider = memoryId ->
                MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(20)
                        .chatMemoryStore(memoryStore)
                        .build();

        // Wire everything together — model + memory + tools
        architectAssistant = AiServices.builder(ArchitectAssistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(googleMapsScraperTool)
                .build();
    }

    public record ChatResult(Long sessionId, String reply) {}

    public record ChatMessageView(String role, String content) {}

    public Long createSession() {
        var entity = repository.save(new ChatMemoryEntity("[]"));
        Long sessionId = entity.getMemoryId();
        log.info("New session created — ID: {}", sessionId);
        return sessionId;
    }

    public ChatResult chat(Long sessionId, List<ChatMessage> newMessages) {
        Long resolvedId = sessionId == -1L ? createSession() : sessionId;

        String userText = newMessages.stream()
                .filter(m -> m.type() == ChatMessageType.USER)
                .map(m -> ((dev.langchain4j.data.message.UserMessage) m).singleText())
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IllegalArgumentException("No user message provided"));

        log.info("Chat request — sessionId: {}, message: {}", resolvedId, userText);

        String reply = architectAssistant.chat(resolvedId, userText);

        return new ChatResult(resolvedId, reply);
    }

    // Appends a message straight to the store without invoking the LLM — used
    // for deterministic pipeline status notes (queued / applied / failed).
    public void appendSystemNote(Long sessionId, String content) {
        List<ChatMessage> updated = new ArrayList<>(memoryStore.getMessages(sessionId));
        updated.add(AiMessage.from(SYSTEM_NOTE_PREFIX + content));
        memoryStore.updateMessages(sessionId, updated);
    }

    public List<ChatMessageView> getHistory(Long sessionId) {
        return memoryStore.getMessages(sessionId).stream()
                .map(ChatService::toView)
                .toList();
    }

    private static ChatMessageView toView(ChatMessage message) {
        if (message.type() == ChatMessageType.USER) {
            String text = ((dev.langchain4j.data.message.UserMessage) message).singleText();
            return new ChatMessageView("user", text);
        }
        if (message.type() == ChatMessageType.AI) {
            String text = ((AiMessage) message).text();
            if (text != null && text.startsWith(SYSTEM_NOTE_PREFIX)) {
                return new ChatMessageView("system", text.substring(SYSTEM_NOTE_PREFIX.length()));
            }
            return new ChatMessageView("ai", text != null ? text : "");
        }
        return new ChatMessageView("system", message.toString());
    }
}