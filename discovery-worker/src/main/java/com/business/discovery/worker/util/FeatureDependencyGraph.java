package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.FeatureSpec;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Directed graph over FeatureSpec.dependsOnFeatures, used to keep cross-feature wiring acyclic.
 *
 * <p>Why this exists: enrichment runs one feature at a time (BACKEND/SHARED before FRONTEND) and
 * each iteration sees the FULLY enriched detail of every feature enriched before it. The enrichment
 * prompt tells the model to bind to real signatures published in those peer summaries — so whenever
 * feature B is enriched after feature A, B can see and wire to A's services. If A already depends on
 * B, that back edge closes a Spring bean cycle and the generated app dies at startup with
 * "The dependencies of some of the beans in the application context form a cycle" — a RUNTIME
 * failure the compiler cannot see, so it costs a full smoke-gate cycle to discover.
 *
 * <p>Observed on circuit-house: order-management enriched first and declared it injects
 * PaymentService; payment-processing enriched second, saw OrderService.updateOrderStatus published
 * in its peer summary, and wired back to it. Both features were locally correct; only the union was
 * cyclic, and nothing inspected the union.
 *
 * <p>{@link #dependentsOf} feeds the per-iteration prompt constraint (prevention); {@link #findCycle}
 * is the post-enrichment gate (detection) that fires before any Java is generated.
 */
@Slf4j
public final class FeatureDependencyGraph {

    private FeatureDependencyGraph() {}

    /**
     * Adjacency map featureName → declared dependencies, canonicalized against the known feature
     * set. Unknown names (the model emitting a display name or a feature that does not exist) and
     * self-edges (intra-feature calls, which can never form a bean cycle across features) are
     * dropped so malformed declarations degrade to "no edge" rather than corrupting the graph.
     */
    public static Map<String, Set<String>> build(List<FeatureSpec> features) {
        Map<String, String> canonical = new HashMap<>();
        for (FeatureSpec f : features) {
            if (f != null && f.getFeatureName() != null) {
                canonical.put(f.getFeatureName().trim().toLowerCase(Locale.ROOT), f.getFeatureName());
            }
        }

        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        for (FeatureSpec f : features) {
            if (f == null || f.getFeatureName() == null) continue;

            Set<String> deps = new LinkedHashSet<>();
            List<String> declared = f.getDependsOnFeatures();
            if (declared != null) {
                for (String raw : declared) {
                    if (raw == null || raw.isBlank()) continue;
                    String resolved = canonical.get(raw.trim().toLowerCase(Locale.ROOT));
                    if (resolved == null) {
                        log.debug("[FeatureDependencyGraph] {} declared unknown dependency '{}' — dropped",
                                f.getFeatureName(), raw);
                        continue;
                    }
                    if (resolved.equals(f.getFeatureName())) continue;
                    deps.add(resolved);
                }
            }
            adjacency.put(f.getFeatureName(), deps);
        }
        return adjacency;
    }

    /**
     * Every feature that transitively depends on {@code featureName} — i.e. the set this feature
     * must not wire back into. Transitive matters: for A → B → C, enriching C must forbid calling
     * both B and A, otherwise a multi-hop cycle slips through a pairwise-only check.
     *
     * <p>Safe on an already-cyclic graph: the visited set terminates the walk.
     */
    public static Set<String> dependentsOf(String featureName, List<FeatureSpec> features) {
        if (featureName == null) return Set.of();
        Map<String, Set<String>> adjacency = build(features);

        Set<String> dependents = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(featureName);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (Map.Entry<String, Set<String>> entry : adjacency.entrySet()) {
                if (entry.getValue().contains(current) && dependents.add(entry.getKey())) {
                    queue.add(entry.getKey());
                }
            }
        }
        dependents.remove(featureName);
        return dependents;
    }

    /**
     * First dependency cycle as a closed path, e.g.
     * {@code [payment-processing, order-management, payment-processing]}, or an empty list when the
     * graph is acyclic.
     */
    public static List<String> findCycle(List<FeatureSpec> features) {
        Map<String, Set<String>> adjacency = build(features);
        Set<String> visited = new HashSet<>();

        for (String node : adjacency.keySet()) {
            List<String> cycle = walk(node, adjacency, visited, new LinkedHashSet<>());
            if (!cycle.isEmpty()) return cycle;
        }
        return List.of();
    }

    /** Depth-first walk; {@code inProgress} is the current recursion path, so re-entering it is a cycle. */
    private static List<String> walk(String node,
                                     Map<String, Set<String>> adjacency,
                                     Set<String> visited,
                                     LinkedHashSet<String> inProgress) {
        if (inProgress.contains(node)) {
            List<String> path = new ArrayList<>();
            boolean started = false;
            for (String step : inProgress) {
                if (step.equals(node)) started = true;
                if (started) path.add(step);
            }
            path.add(node);
            return path;
        }
        if (!visited.add(node)) return List.of();

        inProgress.add(node);
        for (String dependency : adjacency.getOrDefault(node, Set.of())) {
            List<String> cycle = walk(dependency, adjacency, visited, inProgress);
            if (!cycle.isEmpty()) return cycle;
        }
        inProgress.remove(node);
        return List.of();
    }
}
