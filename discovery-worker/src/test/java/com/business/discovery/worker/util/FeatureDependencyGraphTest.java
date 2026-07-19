package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.FeatureSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureDependencyGraphTest {

    private static FeatureSpec feature(String name, String... dependsOn) {
        FeatureSpec f = new FeatureSpec();
        f.setFeatureName(name);
        f.setFeatureType("BACKEND");
        f.setDependsOnFeatures(dependsOn.length == 0 ? List.of() : Arrays.asList(dependsOn));
        return f;
    }

    // ── findCycle ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("detects the circuit-house pair cycle: order-management ↔ payment-processing")
    void findCycle_detectsPairCycle() {
        List<FeatureSpec> features = List.of(
                feature("order-management", "payment-processing"),
                feature("payment-processing", "order-management"));

        List<String> cycle = FeatureDependencyGraph.findCycle(features);

        assertThat(cycle).isNotEmpty();
        assertThat(cycle.get(0)).isEqualTo(cycle.get(cycle.size() - 1));
        assertThat(cycle).contains("order-management", "payment-processing");
    }

    @Test
    @DisplayName("detects a 3-hop cycle a pairwise-only check would miss")
    void findCycle_detectsTransitiveCycle() {
        List<FeatureSpec> features = List.of(
                feature("a", "b"),
                feature("b", "c"),
                feature("c", "a"));

        List<String> cycle = FeatureDependencyGraph.findCycle(features);

        assertThat(cycle).isNotEmpty();
        assertThat(cycle).contains("a", "b", "c");
    }

    @Test
    @DisplayName("acyclic graph with a shared dependency (diamond) passes")
    void findCycle_acyclicDiamondPasses() {
        List<FeatureSpec> features = List.of(
                feature("order-management", "payment-processing", "notification-services"),
                feature("payment-processing", "notification-services"),
                feature("notification-services"),
                feature("shared-backend"));

        assertThat(FeatureDependencyGraph.findCycle(features)).isEmpty();
    }

    @Test
    @DisplayName("self-dependency is not a cycle — intra-feature wiring cannot cross features")
    void findCycle_ignoresSelfEdge() {
        assertThat(FeatureDependencyGraph.findCycle(List.of(feature("auth", "auth")))).isEmpty();
    }

    @Test
    @DisplayName("unknown dependency names are dropped rather than corrupting the graph")
    void findCycle_dropsUnknownFeatureNames() {
        List<FeatureSpec> features = List.of(
                feature("order-management", "Payment Processing", "nonexistent-feature"),
                feature("payment-processing"));

        assertThat(FeatureDependencyGraph.findCycle(features)).isEmpty();
        assertThat(FeatureDependencyGraph.build(features).get("order-management")).isEmpty();
    }

    @Test
    @DisplayName("dependency names match case-insensitively against known features")
    void build_canonicalizesCasing() {
        List<FeatureSpec> features = List.of(
                feature("order-management", "  Payment-Processing  "),
                feature("payment-processing"));

        assertThat(FeatureDependencyGraph.build(features).get("order-management"))
                .containsExactly("payment-processing");
    }

    @Test
    @DisplayName("null dependsOnFeatures (spec written before the field existed) yields no edges")
    void build_handlesNullDeclarations() {
        FeatureSpec legacy = new FeatureSpec();
        legacy.setFeatureName("legacy");
        legacy.setDependsOnFeatures(null);

        assertThat(FeatureDependencyGraph.build(List.of(legacy)).get("legacy")).isEmpty();
        assertThat(FeatureDependencyGraph.findCycle(List.of(legacy))).isEmpty();
    }

    // ── dependentsOf ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("dependentsOf returns the direct dependent that must not be called back into")
    void dependentsOf_findsDirectDependent() {
        List<FeatureSpec> features = List.of(
                feature("order-management", "payment-processing"),
                feature("payment-processing"));

        assertThat(FeatureDependencyGraph.dependentsOf("payment-processing", features))
                .containsExactly("order-management");
        assertThat(FeatureDependencyGraph.dependentsOf("order-management", features)).isEmpty();
    }

    @Test
    @DisplayName("dependentsOf is transitive — a→b→c forbids c calling both b and a")
    void dependentsOf_isTransitive() {
        List<FeatureSpec> features = List.of(
                feature("a", "b"),
                feature("b", "c"),
                feature("c"));

        assertThat(FeatureDependencyGraph.dependentsOf("c", features))
                .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    @DisplayName("dependentsOf terminates on an already-cyclic graph and excludes the feature itself")
    void dependentsOf_terminatesOnCyclicGraph() {
        List<FeatureSpec> features = List.of(
                feature("a", "b"),
                feature("b", "a"));

        Set<String> dependents = FeatureDependencyGraph.dependentsOf("a", features);

        assertThat(dependents).containsExactly("b");
        assertThat(dependents).doesNotContain("a");
    }

    @Test
    @DisplayName("first feature enriched has no dependents, so no constraint is injected")
    void dependentsOf_emptyWhenNothingDependsOnIt() {
        List<FeatureSpec> features = List.of(feature("shared-backend"), feature("order-management"));

        assertThat(FeatureDependencyGraph.dependentsOf("shared-backend", features)).isEmpty();
    }
}
