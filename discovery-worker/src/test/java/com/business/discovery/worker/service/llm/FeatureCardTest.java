package com.business.discovery.worker.service.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureCardTest {

    private FeatureCard mixedFeature() {
        return FeatureCard.builder()
                .featureName("orders")
                .featureDisplayName("Orders")
                .featureType("SHARED")
                .featureInstruction("Build the order flow across both tiers.")
                .files(List.of(
                        FeatureCard.FileRef.builder().path("backend/src/main/java/com/x/OrderService.java")
                                .role("SERVICE — order logic").build(),
                        FeatureCard.FileRef.builder().path("backend/src/main/java/com/x/OrderController.java")
                                .role("CONTROLLER — order endpoints").build(),
                        FeatureCard.FileRef.builder().path("frontend/src/pages/OrdersPage.tsx")
                                .role("PAGE — order list").build(),
                        FeatureCard.FileRef.builder().path("frontend/src/services/orderService.ts")
                                .role("SERVICE — order api").build(),
                        FeatureCard.FileRef.builder().path("backend/src/main/resources/application.properties")
                                .role("config").build()))
                .build();
    }

    @Test
    void backendFile_sibMap_showsOnlyBackendAndSideAgnostic() {
        String out = mixedFeature().toPromptSection("backend/src/main/java/com/x/OrderService.java");

        assertThat(out).contains("OrderController.java");            // backend sibling kept
        assertThat(out).contains("application.properties");          // side-agnostic kept
        assertThat(out).doesNotContain("OrdersPage.tsx");            // frontend sibling dropped
        assertThat(out).doesNotContain("orderService.ts");
    }

    @Test
    void frontendFile_sibMap_showsOnlyFrontendAndSideAgnostic() {
        String out = mixedFeature().toPromptSection("frontend/src/pages/OrdersPage.tsx");

        assertThat(out).contains("orderService.ts");                 // frontend sibling kept
        assertThat(out).doesNotContain("OrderService.java");         // backend sibling dropped
        assertThat(out).doesNotContain("OrderController.java");
    }

    @Test
    void currentFile_markedYouAreHere_withoutRepeatingItsRole() {
        String out = mixedFeature().toPromptSection("backend/src/main/java/com/x/OrderService.java");

        assertThat(out).contains("OrderService.java  ← YOU ARE HERE");
        // its own role text must NOT appear on the YOU-ARE-HERE line (lives in the dedicated section)
        assertThat(out).doesNotContain("OrderService.java — SERVICE — order logic");
        // a sibling's role IS shown
        assertThat(out).contains("OrderController.java — CONTROLLER — order endpoints");
    }

    @Test
    void buildFeatureContext_appendsInstruction_andHandlesNullCard() {
        String withCard = FeatureCard.buildFeatureContext(mixedFeature(),
                "backend/src/main/java/com/x/OrderService.java", "EFFECTIVE INSTRUCTION HERE");
        assertThat(withCard).contains("== FEATURE CONTEXT");
        assertThat(withCard).contains("== FEATURE INSTRUCTION");
        assertThat(withCard).contains("EFFECTIVE INSTRUCTION HERE");

        // Null card (ENRICHMENT.json absent) → instruction still flows, no feature-context header
        String noCard = FeatureCard.buildFeatureContext(null, "backend/src/main/java/com/x/OrderService.java",
                "ONLY THE INSTRUCTION");
        assertThat(noCard).doesNotContain("== FEATURE CONTEXT");
        assertThat(noCard).contains("== FEATURE INSTRUCTION");
        assertThat(noCard).contains("ONLY THE INSTRUCTION");
    }
}
