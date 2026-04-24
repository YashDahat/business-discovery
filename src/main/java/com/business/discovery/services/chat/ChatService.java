package com.business.discovery.services.chat;

import com.business.discovery.model.ChatMemoryEntity;
import com.business.discovery.repository.ChatMemoryRepository;
import com.business.discovery.services.chatMemory.PostgresChatMemoryStore;
import com.business.discovery.tools.GoogleMapsScraperTool;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class ChatService {

    private final ChatModel chatModel;
    private final PostgresChatMemoryStore memoryStore;
    private final ChatMemoryRepository repository;
    private final GoogleMapsScraperTool googleMapsScraperTool;

    // AiServices-based assistant — handles tool calls automatically
    interface ArchitectAssistant {
        String chat(@MemoryId Long memoryId, @UserMessage List<ChatMessage> messages);
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

    public ChatResult chat(Long sessionId, List<ChatMessage> newMessages) {
        // If new conversation, pre-save to get DB-generated ID
        if (sessionId == -1L) {
            var entity = repository.save(
                    new com.business.discovery.model.ChatMemoryEntity("[]")
            );
            sessionId = entity.getMemoryId();
            log.info("New session created — ID: {}", sessionId);
        }

        Long resolvedId = sessionId;

        // Build memory and add all incoming messages
        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
                .id(resolvedId)
                .maxMessages(20)
                .chatMemoryStore(memoryStore)
                .build();

        newMessages.forEach(memory::add);

        log.info("Chat request — sessionId: {}, message: {}", resolvedId, memory.messages());

        String reply = architectAssistant.chat(resolvedId, memory.messages());

        return new ChatResult(resolvedId, reply);
    }
}