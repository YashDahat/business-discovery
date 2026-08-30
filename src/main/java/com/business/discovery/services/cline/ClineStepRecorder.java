package com.business.discovery.services.cline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * In-memory, per-brief record of the MCP tool operations Cline performs during a chat turn, so the UI
 * can show a live stepper (git/code + web + brief steps) while the synchronous {@code /chat} call runs.
 *
 * How it stays "live" without streaming: each MCP tool Cline calls is a separate HTTP request to Spring's
 * {@code /internal/mcp/**} endpoints made *while* the outer {@code /chat} request is still blocked on the
 * sidecar — so the tool controllers append steps here in real time, and the frontend short-polls
 * {@code GET /api/v4/cline/brief/{id}/steps}. Only the current turn is retained per brief (reset by
 * {@link #startTurn}); {@code turnSeq} lets the client tell turns apart.
 */
@Slf4j
@Component
public class ClineStepRecorder {

    public enum Status { RUNNING, DONE, ERROR }

    /** Immutable view returned to the client. */
    public record StepView(String id, String tool, String label, String status, String detail, long ts) {}
    public record TurnView(long turnSeq, List<StepView> steps) {}

    private static final class Step {
        final String id;
        final String tool;
        final String label;
        final long ts;
        volatile Status status;
        volatile String detail;
        Step(String id, String tool, String label) {
            this.id = id;
            this.tool = tool;
            this.label = label;
            this.ts = System.currentTimeMillis();
            this.status = Status.RUNNING;
        }
        StepView view() {
            return new StepView(id, tool, label, status.name().toLowerCase(), detail, ts);
        }
    }

    private static final class Turn {
        final AtomicLong seq = new AtomicLong(0);
        final CopyOnWriteArrayList<Step> steps = new CopyOnWriteArrayList<>();
    }

    private final ConcurrentHashMap<UUID, Turn> turns = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(0);

    /** Begin a new turn for this brief — bumps the sequence and clears prior steps. */
    public void startTurn(UUID briefId) {
        if (briefId == null) return;
        Turn t = turns.computeIfAbsent(briefId, k -> new Turn());
        t.seq.incrementAndGet();
        t.steps.clear();
    }

    /**
     * Run {@code op} while recording it as a RUNNING step that flips to DONE/ERROR when it returns/throws.
     * Returns whatever {@code op} returns; rethrows unchanged so controller exception handling is intact.
     */
    public <T> T track(UUID briefId, String tool, String label, Supplier<T> op) {
        if (briefId == null) return op.get();
        Turn t = turns.computeIfAbsent(briefId, k -> new Turn());
        Step step = new Step(Long.toString(idGen.incrementAndGet()), tool, label);
        t.steps.add(step);
        try {
            T result = op.get();
            step.status = Status.DONE;
            return result;
        } catch (RuntimeException e) {
            step.status = Status.ERROR;
            step.detail = e.getMessage();
            throw e;
        }
    }

    /** Current turn snapshot for this brief (empty if none yet). */
    public TurnView snapshot(UUID briefId) {
        Turn t = turns.get(briefId);
        if (t == null) {
            return new TurnView(0, List.of());
        }
        return new TurnView(t.seq.get(), t.steps.stream().map(Step::view).toList());
    }
}
