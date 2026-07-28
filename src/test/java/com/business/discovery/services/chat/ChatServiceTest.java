package com.business.discovery.services.chat;

import com.business.discovery.model.ChatMemoryEntity;
import com.business.discovery.repository.ChatMemoryRepository;
import com.business.discovery.services.chatMemory.PostgresChatMemoryStore;
import com.business.discovery.tools.GoogleMapsScraperTool;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock ChatModel chatModel;
    @Mock PostgresChatMemoryStore memoryStore;
    @Mock ChatMemoryRepository repository;
    @Mock GoogleMapsScraperTool googleMapsScraperTool;

    @InjectMocks ChatService chatService;

    // ── createSession ───────────────────────────────────────────────────────

    @Test
    void createSession_savesEmptyEntityAndReturnsGeneratedId() {
        ChatMemoryEntity saved = new ChatMemoryEntity("[]");
        ReflectionTestUtils.setField(saved, "memoryId", 42L);
        when(repository.save(any(ChatMemoryEntity.class))).thenReturn(saved);

        Long sessionId = chatService.createSession();

        assertThat(sessionId).isEqualTo(42L);
    }

    // ── appendSystemNote ─────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void appendSystemNote_appendsMarkedMessageWithoutTouchingExistingHistory() {
        when(memoryStore.getMessages(7L)).thenReturn(List.of(UserMessage.from("Add WhatsApp button")));

        chatService.appendSystemNote(7L, "Queued — will regenerate on next cycle");

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(memoryStore).updateMessages(eq(7L), captor.capture());
        List<ChatMessage> updated = captor.getValue();

        assertThat(updated).hasSize(2);
        assertThat(((AiMessage) updated.get(1)).text())
                .isEqualTo("[SYSTEM] Queued — will regenerate on next cycle");
    }

    // ── getHistory ───────────────────────────────────────────────────────────

    @Test
    void getHistory_mapsRolesAndStripsSystemNotePrefix() {
        when(memoryStore.getMessages(7L)).thenReturn(List.of(
                UserMessage.from("Add WhatsApp button"),
                AiMessage.from("Sure — I'll add that."),
                AiMessage.from("[SYSTEM] Queued — will regenerate on next cycle")
        ));

        List<ChatService.ChatMessageView> history = chatService.getHistory(7L);

        assertThat(history).containsExactly(
                new ChatService.ChatMessageView("user", "Add WhatsApp button"),
                new ChatService.ChatMessageView("ai", "Sure — I'll add that."),
                new ChatService.ChatMessageView("system", "Queued — will regenerate on next cycle")
        );
    }

    @Test
    void getHistory_noMessages_returnsEmptyList() {
        when(memoryStore.getMessages(7L)).thenReturn(List.of());

        assertThat(chatService.getHistory(7L)).isEmpty();
    }
}
