package com.business.discovery.api;

import com.business.discovery.services.chat.ChatService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

// Controller
@RestController
@RequestMapping("/api/chat")
public class ChatController {
    // DTO for incoming requests
    public record ChatMessageDto(String role, String content) {}
    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ResponseEntity<ChatService.ChatResult> chat(
            @RequestParam(defaultValue = "-1") Long sessionId,
            @RequestBody List<ChatMessageDto> dtos) {

        List<ChatMessage> messages = dtos.stream()
                .map(this::toMessage)
                .toList();

        return ResponseEntity.ok(chatService.chat(sessionId, messages));
    }

    private ChatMessage toMessage(ChatMessageDto dto) {
        return switch (dto.role().toLowerCase()) {
            case "system" -> SystemMessage.from(dto.content());
            case "ai", "assistant" -> AiMessage.from(dto.content());
            case "user" -> UserMessage.from(dto.content());
            default -> throw new IllegalArgumentException("Unknown role: " + dto.role());
        };
    }
}