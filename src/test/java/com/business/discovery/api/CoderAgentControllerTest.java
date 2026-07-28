package com.business.discovery.api;

import com.business.discovery.agents.coder.CoderAgentGraph;
import com.business.discovery.model.ArchitectBrief;
import com.business.discovery.model.ContainerTask;
import com.business.discovery.model.ContainerTask.ContainerTaskStatus;
import com.business.discovery.repository.ArchitectBriefRepository;
import com.business.discovery.repository.ContainerTaskRepository;
import com.business.discovery.services.chat.ChatService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CoderAgentControllerTest {

    @Mock CoderAgentGraph coderAgentGraph;
    @Mock ContainerTaskRepository containerTaskRepository;
    @Mock ArchitectBriefRepository architectBriefRepository;
    @Mock ChatService chatService;

    @InjectMocks CoderAgentController controller;

    private static final UUID BRIEF_ID    = UUID.randomUUID();
    private static final UUID BUSINESS_ID = UUID.randomUUID();
    private static final UUID RUN_ID      = UUID.randomUUID();

    private static final UUID TASK_ID = UUID.randomUUID();

    private ContainerTask completedTask() {
        return ContainerTask.builder()
                .id(TASK_ID)
                .briefId(BRIEF_ID)
                .businessId(BUSINESS_ID)
                .runId(RUN_ID)
                .taskDescription(Map.of("test", "true"))
                .status(ContainerTaskStatus.COMPLETED)
                .build();
    }

    // ── POST /api/v3/coder/run ────────────────────────────────────────────

    @Test
    void triggerRun_validRequest_returns202() {
        doNothing().when(coderAgentGraph).execute(RUN_ID, BRIEF_ID, BUSINESS_ID);
        var request = new CoderAgentController.CoderRunRequest(RUN_ID, BRIEF_ID, BUSINESS_ID);

        ResponseEntity<Map<String, Object>> response = controller.triggerRun(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("status", "TRIGGERED");
        assertThat(response.getBody().get("briefId")).isEqualTo(BRIEF_ID.toString());
        verify(coderAgentGraph).execute(RUN_ID, BRIEF_ID, BUSINESS_ID);
    }

    // ── GET /api/v3/coder/brief/{briefId}/tasks ───────────────────────────

    @Test
    void getTasksForBrief_returnsList() {
        when(containerTaskRepository.findByBriefId(BRIEF_ID))
                .thenReturn(List.of(completedTask()));

        ResponseEntity<List<ContainerTask>> response = controller.getTasksForBrief(BRIEF_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).getStatus()).isEqualTo(ContainerTaskStatus.COMPLETED);
    }

    // ── POST /api/v3/coder/brief/{briefId}/chat ────────────────────────────

    @Test
    void sendChatMessage_newSession_createsSessionAndQueuesTask() {
        when(architectBriefRepository.findById(BRIEF_ID))
                .thenReturn(Optional.of(ArchitectBrief.builder()
                        .runId(UUID.randomUUID())
                        .businessCategory("Restaurant")
                        .build())); // chatSessionId is null
        ContainerTask task = completedTask();
        when(containerTaskRepository.findTopByBriefIdOrderByCreatedAtDesc(BRIEF_ID))
                .thenReturn(Optional.of(task));
        when(containerTaskRepository.save(any())).thenReturn(task);
        when(chatService.createSession()).thenReturn(42L);
        when(chatService.chat(eq(42L), any()))
                .thenReturn(new ChatService.ChatResult(42L, "Got it — queuing the update."));

        var request = new CoderAgentController.ChatSendRequest("Add WhatsApp contact form");
        ResponseEntity<Map<String, Object>> response = controller.sendChatMessage(BRIEF_ID, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("reply", "Got it — queuing the update.");
        assertThat(response.getBody()).containsEntry("sessionId", 42L);
        assertThat(response.getBody()).containsEntry("taskId", TASK_ID.toString());

        verify(architectBriefRepository).updateChatSessionId(BRIEF_ID, 42L);
        verify(architectBriefRepository).updateRequestedChanges(BRIEF_ID, "Add WhatsApp contact form");
        verify(containerTaskRepository).save(argThat(t -> t.getStatus() == ContainerTaskStatus.PENDING));
        verify(chatService).appendSystemNote(42L, "Queued — will regenerate on next cycle");
    }

    @Test
    void sendChatMessage_existingSession_reusesSessionId() {
        when(architectBriefRepository.findById(BRIEF_ID))
                .thenReturn(Optional.of(ArchitectBrief.builder()
                        .runId(UUID.randomUUID())
                        .businessCategory("Restaurant")
                        .chatSessionId(7L)
                        .build()));
        ContainerTask task = completedTask();
        when(containerTaskRepository.findTopByBriefIdOrderByCreatedAtDesc(BRIEF_ID))
                .thenReturn(Optional.of(task));
        when(containerTaskRepository.save(any())).thenReturn(task);
        when(chatService.chat(eq(7L), any()))
                .thenReturn(new ChatService.ChatResult(7L, "Sure thing."));

        var request = new CoderAgentController.ChatSendRequest("Now make the header blue");
        controller.sendChatMessage(BRIEF_ID, request);

        verify(chatService, never()).createSession();
        verify(architectBriefRepository, never()).updateChatSessionId(any(), any());
        verify(chatService).appendSystemNote(7L, "Queued — will regenerate on next cycle");
    }

    @Test
    void sendChatMessage_briefNotFound_throwsIllegalArgument() {
        when(architectBriefRepository.findById(BRIEF_ID)).thenReturn(Optional.empty());
        var request = new CoderAgentController.ChatSendRequest("Add gallery");

        assertThatThrownBy(() -> controller.sendChatMessage(BRIEF_ID, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(BRIEF_ID.toString());

        verify(containerTaskRepository, never()).save(any());
        verifyNoInteractions(chatService);
    }

    @Test
    void sendChatMessage_taskNotFound_throwsIllegalArgument() {
        when(architectBriefRepository.findById(BRIEF_ID))
                .thenReturn(Optional.of(ArchitectBrief.builder()
                        .runId(UUID.randomUUID())
                        .businessCategory("Restaurant")
                        .build()));
        when(containerTaskRepository.findTopByBriefIdOrderByCreatedAtDesc(BRIEF_ID))
                .thenReturn(Optional.empty());

        var request = new CoderAgentController.ChatSendRequest("Change color scheme");

        assertThatThrownBy(() -> controller.sendChatMessage(BRIEF_ID, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(BRIEF_ID.toString());

        verify(containerTaskRepository, never()).save(any());
        verify(architectBriefRepository, never()).updateRequestedChanges(any(), any());
        verifyNoInteractions(chatService);
    }

    // ── GET /api/v3/coder/brief/{briefId}/chat ─────────────────────────────

    @Test
    void getChat_noSession_returnsEmptyListWithoutTouchingChatService() {
        when(architectBriefRepository.findById(BRIEF_ID))
                .thenReturn(Optional.of(ArchitectBrief.builder()
                        .runId(UUID.randomUUID())
                        .businessCategory("Restaurant")
                        .build())); // chatSessionId is null

        ResponseEntity<List<ChatService.ChatMessageView>> response = controller.getChat(BRIEF_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
        verifyNoInteractions(chatService);
    }

    @Test
    void getChat_existingSession_returnsHistory() {
        when(architectBriefRepository.findById(BRIEF_ID))
                .thenReturn(Optional.of(ArchitectBrief.builder()
                        .runId(UUID.randomUUID())
                        .businessCategory("Restaurant")
                        .chatSessionId(7L)
                        .build()));
        List<ChatService.ChatMessageView> history = List.of(
                new ChatService.ChatMessageView("user", "Add WhatsApp contact form"),
                new ChatService.ChatMessageView("system", "Queued — will regenerate on next cycle"));
        when(chatService.getHistory(7L)).thenReturn(history);

        ResponseEntity<List<ChatService.ChatMessageView>> response = controller.getChat(BRIEF_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(history);
    }
}
