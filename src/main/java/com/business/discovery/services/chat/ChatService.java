package com.business.discovery.services.chat;

import com.business.discovery.model.ChatMemoryEntity;
import com.business.discovery.repository.ChatMemoryRepository;
import com.business.discovery.services.chatMemory.PostgresChatMemoryStore;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ChatService {

    private final ChatModel chatModel;
    private final PostgresChatMemoryStore memoryStore;
    private final ChatMemoryRepository repository;

    public ChatService(ChatModel chatModel, PostgresChatMemoryStore memoryStore, ChatMemoryRepository repository) {
        this.chatModel = chatModel;
        this.memoryStore = memoryStore;
        this.repository = repository;
    }

    public record ChatResult(Long sessionId, String reply) {}

    public ChatResult chat(Long sessionId, List<ChatMessage> newMessages) {
        // If new conversation, pre-save to get DB-generated ID
        if (sessionId == -1L) {
            ChatMemoryEntity entity = repository.save(new ChatMemoryEntity("[]"));
            sessionId = entity.getMemoryId();
        }

        Long resolvedId = sessionId;

        ChatMemory memory = MessageWindowChatMemory.builder()
                .id(resolvedId)
                .maxMessages(20)
                .chatMemoryStore(memoryStore)
                .build();

        newMessages.forEach(memory::add);

        ChatResponse response = chatModel.chat(memory.messages());
        memory.add(response.aiMessage());

        return new ChatResult(resolvedId, response.aiMessage().text());
    }
}