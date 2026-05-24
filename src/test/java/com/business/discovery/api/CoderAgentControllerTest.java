package com.business.discovery.api;

import com.business.discovery.agents.coder.CoderAgentGraph;
import com.business.discovery.model.ArchitectBrief;
import com.business.discovery.model.ContainerTask;
import com.business.discovery.model.ContainerTask.ContainerTaskStatus;
import com.business.discovery.repository.ArchitectBriefRepository;
import com.business.discovery.repository.ContainerTaskRepository;
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

    // ── POST /api/v3/coder/brief/{briefId}/changes ────────────────────────

    @Test
    void submitChanges_briefAndTaskExist_returns202() {
        when(architectBriefRepository.findById(BRIEF_ID))
                .thenReturn(Optional.of(ArchitectBrief.builder()
                        .runId(UUID.randomUUID())
                        .businessCategory("Restaurant")
                        .build()));
        doNothing().when(architectBriefRepository).updateRequestedChanges(eq(BRIEF_ID), any());
        ContainerTask task = completedTask();
        when(containerTaskRepository.findTopByBriefIdOrderByCreatedAtDesc(BRIEF_ID))
                .thenReturn(Optional.of(task));
        when(containerTaskRepository.save(any())).thenReturn(task);

        var request = new CoderAgentController.ChangesRequest("Add WhatsApp contact form");
        ResponseEntity<Map<String, Object>> response = controller.submitChanges(BRIEF_ID, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("status", "CHANGES_SUBMITTED");
        assertThat(response.getBody().get("briefId")).isEqualTo(BRIEF_ID.toString());

        verify(architectBriefRepository).updateRequestedChanges(BRIEF_ID, "Add WhatsApp contact form");
        verify(containerTaskRepository).save(argThat(t ->
                t.getStatus() == ContainerTaskStatus.PENDING));
    }

    @Test
    void submitChanges_briefNotFound_throwsIllegalArgument() {
        when(architectBriefRepository.findById(BRIEF_ID)).thenReturn(Optional.empty());
        var request = new CoderAgentController.ChangesRequest("Add gallery");

        assertThatThrownBy(() -> controller.submitChanges(BRIEF_ID, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(BRIEF_ID.toString());

        verify(containerTaskRepository, never()).save(any());
    }

    @Test
    void submitChanges_taskNotFound_throwsIllegalArgument() {
        when(architectBriefRepository.findById(BRIEF_ID))
                .thenReturn(Optional.of(ArchitectBrief.builder()
                        .runId(UUID.randomUUID())
                        .businessCategory("Restaurant")
                        .build()));
        doNothing().when(architectBriefRepository).updateRequestedChanges(eq(BRIEF_ID), any());
        when(containerTaskRepository.findTopByBriefIdOrderByCreatedAtDesc(BRIEF_ID))
                .thenReturn(Optional.empty());

        var request = new CoderAgentController.ChangesRequest("Change color scheme");

        assertThatThrownBy(() -> controller.submitChanges(BRIEF_ID, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(BRIEF_ID.toString());

        verify(containerTaskRepository, never()).save(any());
    }
}
