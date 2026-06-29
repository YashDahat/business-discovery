package com.business.discovery.worker.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-local accumulator for LLM token usage across all nodes in one worker run.
 * Each model (geminiPro, geminiFlash, claudeSonnet) records independently so cost
 * can be broken down by model tier. Call reset() at the start of each run.
 */
@Component
@Slf4j
public class LlmTokenAccumulator {

    // Pricing per million tokens (as of June 2026)
    private static final Map<String, double[]> MODEL_PRICING = Map.of(
        "gemini-2.5-pro",   new double[]{ 1.25, 10.00 },
        "gemini-2.5-flash", new double[]{ 0.15,  0.60 },
        "claude-sonnet",    new double[]{ 3.00, 15.00 }
    );

    public record ModelUsage(long inputTokens, long outputTokens) {}

    private final ThreadLocal<ConcurrentHashMap<String, long[]>> store =
        ThreadLocal.withInitial(ConcurrentHashMap::new);

    public void record(String modelKey, long inputTokens, long outputTokens) {
        store.get().merge(modelKey, new long[]{ inputTokens, outputTokens },
            (existing, incoming) -> new long[]{
                existing[0] + incoming[0],
                existing[1] + incoming[1]
            });
    }

    public void reset() {
        store.get().clear();
    }

    public Map<String, long[]> getUsageByModel() {
        return Map.copyOf(store.get());
    }

    public long totalInputTokens() {
        return store.get().values().stream().mapToLong(v -> v[0]).sum();
    }

    public long totalOutputTokens() {
        return store.get().values().stream().mapToLong(v -> v[1]).sum();
    }

    /** Computes total USD cost across all models using model-aware pricing. */
    public double totalCostUsd() {
        double total = 0.0;
        for (Map.Entry<String, long[]> entry : store.get().entrySet()) {
            double[] pricing = resolvePrice(entry.getKey());
            long[] tokens = entry.getValue();
            total += (tokens[0] / 1_000_000.0) * pricing[0]
                   + (tokens[1] / 1_000_000.0) * pricing[1];
        }
        return total;
    }

    public void logSummary() {
        double cost = totalCostUsd();
        log.info("[LlmCost] Total: {} | input={} output={} tokens",
                String.format("$%.4f", cost), totalInputTokens(), totalOutputTokens());
        store.get().forEach((model, tokens) -> {
            double[] pricing = resolvePrice(model);
            double modelCost = (tokens[0] / 1_000_000.0) * pricing[0]
                             + (tokens[1] / 1_000_000.0) * pricing[1];
            log.info("[LlmCost]   {} — input={} output={} cost={}",
                    model, tokens[0], tokens[1], String.format("$%.4f", modelCost));
        });
    }

    private double[] resolvePrice(String modelKey) {
        for (Map.Entry<String, double[]> e : MODEL_PRICING.entrySet()) {
            if (modelKey.contains(e.getKey())) return e.getValue();
        }
        log.warn("[LlmCost] No pricing found for model key '{}' — using Gemini Pro rate", modelKey);
        return MODEL_PRICING.get("gemini-2.5-pro");
    }
}
