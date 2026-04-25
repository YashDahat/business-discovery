package com.business.discovery.services.agent;

import com.business.discovery.model.AgentEvent;
import com.business.discovery.model.AgentEvent.EventType;
import com.business.discovery.repository.AgentEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEventService {

    private final AgentEventRepository agentEventRepository;

    public void log(UUID runId, String stepName, EventType eventType,
                    String message, Map<String, Object> payload, Long durationMs) {

        // Structured log with MDC runId for CloudWatch filtering
        log.info("[{}] [{}] {} — {}", runId, stepName, eventType, message);

        AgentEvent event = AgentEvent.builder()
                .runId(runId)
                .stepName(stepName)
                .eventType(eventType)
                .message(message)
                .payload(payload)
                .durationMs(durationMs)
                .build();

        agentEventRepository.save(event);
    }

    // Convenience methods for common event types
    public void stepStarted(UUID runId, String stepName) {
        log(runId, stepName, EventType.STEP_STARTED,
                "Step started", null, null);
    }

    public void stepCompleted(UUID runId, String stepName, long durationMs) {
        log(runId, stepName, EventType.STEP_COMPLETED,
                "Step completed in " + durationMs + "ms",
                Map.of("duration_ms", durationMs), durationMs);
    }

    public void stepFailed(UUID runId, String stepName, String error) {
        log(runId, stepName, EventType.STEP_FAILED,
                "Step failed: " + error,
                Map.of("error", error), null);
    }

    public void toolCall(UUID runId, String stepName, String tool, Map<String, Object> input) {
        log(runId, stepName, EventType.TOOL_CALL,
                "Tool called: " + tool, input, null);
    }

    public void llmCall(UUID runId, String stepName, int inputTokens) {
        log(runId, stepName, EventType.LLM_CALL,
                "LLM called",
                Map.of("input_tokens", inputTokens), null);
    }

    public List<AgentEvent> getRunEvents(UUID runId) {
        return agentEventRepository.findByRunIdOrderByCreatedAtAsc(runId);
    }
}