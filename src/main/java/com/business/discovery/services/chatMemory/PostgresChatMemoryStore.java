// com/business/discovery/services/chat/PostgresChatMemoryStore.java
package com.business.discovery.services.chatMemory;

import com.business.discovery.model.ChatMemoryEntity;
import com.business.discovery.repository.ChatMemoryRepository;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class PostgresChatMemoryStore implements ChatMemoryStore {

    private final ChatMemoryRepository repository;

    public PostgresChatMemoryStore(ChatMemoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return repository.findById(Long.parseLong(memoryId.toString()))
                .map(entity -> ChatMessageDeserializer.messagesFromJson(entity.getMessagesJson()))
                .orElse(Collections.emptyList());
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String json = ChatMessageSerializer.messagesToJson(messages);
        ChatMemoryEntity entity = repository.findById(Long.parseLong(memoryId.toString()))
                .orElse(new ChatMemoryEntity(json));
        entity.setMessagesJson(json);
        repository.save(entity);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        repository.deleteById(Long.parseLong(memoryId.toString()));
    }
}