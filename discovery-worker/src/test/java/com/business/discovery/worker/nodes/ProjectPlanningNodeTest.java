package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.model.ArchitectBrief;
import com.business.discovery.worker.model.BusinessEntity;
import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.BriefContext;
import com.business.discovery.worker.service.llm.FileEntry;
import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.ProjectDependencies;
import com.business.discovery.worker.service.GitService;
import com.business.discovery.worker.service.SpringInitializrClient;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.business.discovery.worker.util.SlugUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectPlanningNodeTest {

    @Mock private LlmGeneratorService llm;
    @Mock private LlmGeneratorService enrichLlm;
    @Mock private SpringInitializrClient initializrClient;
    @Mock private GitService gitService;
    @Mock private WorkerContext ctx;

    @TempDir Path tempDir;

    @Test
    void execute_setsManifestFromArchSpec() {
        org.mockito.Mockito.lenient().when(initializrClient.filterValidDependencies(any()))
                .thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.lenient().when(initializrClient.getDefaultBootVersion())
                .thenReturn("3.4.5");

        ProjectPlanningNode node = new ProjectPlanningNode(llm, enrichLlm, initializrClient, gitService, List.of());

        ArchitectBrief brief = ArchitectBrief.builder()
                .id(UUID.randomUUID())
                .businessCategory("Restaurant")
                .location("Pune")
                .mustHaveFeatures(List.of("Menu", "Online ordering"))
                .recommendedTechStack(Map.of("frontend", "React 19", "backend", "Spring Boot 3"))
                .websiteType(ArchitectBrief.WebsiteType.INFORMATIONAL)
                .build();
        BusinessEntity business = BusinessEntity.builder()
                .id(UUID.randomUUID()).title("Raj Restaurant").category("Restaurant").build();

        ArchitectureSpec spec = ArchitectureSpec.builder()
                .generatedAt("2026-05-30T10:00:00")
                .businessName("Raj Restaurant")
                .basePackage("com.rajrestaurant")
                .projectDependencies(ProjectDependencies.builder()
                        .springBootStarters(List.of("web", "data-jpa", "postgresql", "lombok", "validation", "actuator"))
                        .npmPackages(List.of("@tanstack/react-query", "axios", "react-router-dom"))
                        .build())
                .files(List.of(
                        FileSpec.builder()
                                .fileName("MenuItem.java")
                                .filePath("backend/src/main/java/com/rajrestaurant/model/MenuItem.java")
                                .fileType("BACKEND")
                                .layer("MODEL")
                                .status("PLANNED")
                                .description("JPA entity for menu items")
                                .build(),
                        FileSpec.builder()
                                .fileName("MenuPage.tsx")
                                .filePath("frontend/src/pages/MenuPage.tsx")
                                .fileType("FRONTEND")
                                .layer("PAGE")
                                .status("PLANNED")
                                .description("Menu browsing page")
                                .build()
                ))
                .build();

        when(ctx.getBrief()).thenReturn(brief);
        when(ctx.getBusiness()).thenReturn(business);
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getProjectHistory()).thenReturn(null);
        when(llm.generateArchitectureSpec(any(BriefContext.class), eq("rajrestaurant"))).thenReturn(spec);

        AtomicReference<List<FileEntry>> capturedManifest = new AtomicReference<>();
        org.mockito.stubbing.Answer<Void> captureManifest = inv -> {
            @SuppressWarnings("unchecked")
            List<FileEntry> m = (List<FileEntry>) inv.getArgument(0);
            capturedManifest.set(m);
            return null;
        };
        org.mockito.Mockito.doAnswer(captureManifest).when(ctx).setFileManifest(any());

        // Node fails on scaffold (no real filesystem/network) — verify LLM + context interactions
        try {
            node.execute(ctx);
        } catch (Exception ignored) {}

        verify(ctx).setBriefCtx(any(BriefContext.class));
        verify(ctx).setFileManifest(any());

        List<FileEntry> manifest = capturedManifest.get();
        assertThat(manifest).isNotNull().hasSize(2);
        assertThat(manifest.get(0).path()).isEqualTo("backend/src/main/java/com/rajrestaurant/model/MenuItem.java");
        assertThat(manifest.get(0).type()).isEqualTo(FileType.BACKEND);
        assertThat(manifest.get(1).type()).isEqualTo(FileType.FRONTEND);
    }

    @Test
    void enrichment_runsBackendFeaturesBeforeFrontend() {
        org.mockito.Mockito.lenient().when(initializrClient.filterValidDependencies(any()))
                .thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.lenient().when(initializrClient.getDefaultBootVersion())
                .thenReturn("3.4.5");

        ProjectPlanningNode node = new ProjectPlanningNode(llm, enrichLlm, initializrClient, gitService, List.of());

        ArchitectBrief brief = ArchitectBrief.builder()
                .id(UUID.randomUUID()).businessCategory("Gym").location("Pune")
                .mustHaveFeatures(List.of("Booking"))
                .recommendedTechStack(Map.of("frontend", "React 19"))
                .websiteType(ArchitectBrief.WebsiteType.INFORMATIONAL)
                .build();
        BusinessEntity business = BusinessEntity.builder()
                .id(UUID.randomUUID()).title("MultiFit").category("Gym").build();

        String bePath = "backend/src/main/java/com/multifit/model/Trainer.java";
        String fePath = "frontend/src/pages/TrainerPage.tsx";
        // Deliberately FRONTEND-first in the spec — the node must reorder
        ArchitectureSpec spec = ArchitectureSpec.builder()
                .generatedAt("2026-07-06").businessName("MultiFit").basePackage("com.multifit")
                .files(List.of(
                        FileSpec.builder().fileName("TrainerPage.tsx").filePath(fePath)
                                .fileType("FRONTEND").layer("PAGE").status("PLANNED")
                                .description("trainer page").build(),
                        FileSpec.builder().fileName("Trainer.java").filePath(bePath)
                                .fileType("BACKEND").layer("MODEL").status("PLANNED")
                                .description("trainer entity").build()))
                .features(new java.util.ArrayList<>(List.of(
                        com.business.discovery.worker.service.llm.FeatureSpec.builder()
                                .featureName("trainer-ui").featureDisplayName("Trainer UI")
                                .featureType("FRONTEND").filePaths(List.of(fePath)).build(),
                        com.business.discovery.worker.service.llm.FeatureSpec.builder()
                                .featureName("trainer-backend").featureDisplayName("Trainer Backend")
                                .featureType("BACKEND").filePaths(List.of(bePath)).build())))
                .build();

        when(ctx.getBrief()).thenReturn(brief);
        when(ctx.getBusiness()).thenReturn(business);
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getProjectHistory()).thenReturn(null);
        when(llm.generateArchitectureSpec(any(BriefContext.class), any())).thenReturn(spec);

        List<String> enrichOrder = new java.util.ArrayList<>();
        org.mockito.Mockito.lenient().when(enrichLlm.enrichFeature(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    com.business.discovery.worker.service.llm.FeatureSpec f = inv.getArgument(0);
                    enrichOrder.add(f.getFeatureName());
                    f.setFeatureInstruction("A sufficiently long holistic instruction covering all files "
                            + "in this feature including exact signatures and wiring for " + f.getFeatureName());
                    return f;
                });

        try {
            node.execute(ctx);
        } catch (Exception ignored) {}

        assertThat(enrichOrder).containsExactly("trainer-backend", "trainer-ui");
    }

    // ── Cycle self-heal (Option B) ────────────────────────────────────────────

    /**
     * A cycle whose back-edge owner re-enriches WITHOUT re-wiring back is healed in-run: the loop
     * re-enriches only that owner, feeds it the exact cycle path, and returns without throwing.
     */
    @Test
    void enforceAcyclic_healsCycle_whenReEnrichDropsBackEdge() throws Exception {
        ProjectPlanningNode node =
                new ProjectPlanningNode(llm, enrichLlm, initializrClient, gitService, List.of());

        CyclicFixture fx = cyclicTwoFeatureSpec();   // featureA ⇄ featureB, back-edge owner = featureB

        java.util.concurrent.atomic.AtomicReference<String> capturedViolation = new AtomicReference<>();
        when(enrichLlm.enrichFeature(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    com.business.discovery.worker.service.llm.FeatureSpec owner = inv.getArgument(0);
                    capturedViolation.set(inv.getArgument(6));
                    // Re-enrich as a good citizen: forward-only, no back-edge into featureA.
                    owner.setFeatureInstruction("Re-enriched holistic instruction that returns its "
                            + "outcome to the shared controller instead of calling back.");
                    owner.setDependsOnFeatures(List.of());
                    return owner;
                });

        invokeEnforceAcyclic(node, fx);   // must NOT throw

        // The retry prompt was handed the concrete cycle it had formed.
        assertThat(capturedViolation.get()).isEqualTo("featureA → featureB → featureA");
        // Re-enriched exactly once — one pass was enough to converge.
        verify(enrichLlm).enrichFeature(any(), any(), any(), any(), any(), any(), any());
        assertThat(com.business.discovery.worker.util.FeatureDependencyGraph.findCycle(
                fx.spec.getFeatures())).isEmpty();
    }

    /**
     * A stubborn cycle — the owner re-wires the back-edge on every re-enrich — exhausts the heal
     * budget and fails hard, leaving the owner cleared for a container retry (the prior contract).
     */
    @Test
    void enforceAcyclic_failsHardAfterBudget_whenReEnrichKeepsWiringBack() throws Exception {
        ProjectPlanningNode node =
                new ProjectPlanningNode(llm, enrichLlm, initializrClient, gitService, List.of());

        CyclicFixture fx = cyclicTwoFeatureSpec();

        when(enrichLlm.enrichFeature(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    com.business.discovery.worker.service.llm.FeatureSpec owner = inv.getArgument(0);
                    owner.setFeatureInstruction("Ignores the constraint and wires back anyway.");
                    owner.setDependsOnFeatures(List.of("featureA"));   // re-forms the cycle every time
                    return owner;
                });

        Throwable thrown = catchInvocationCause(() -> invokeEnforceAcyclic(node, fx));

        assertThat(thrown).isInstanceOf(
                com.business.discovery.worker.errorhandler.WorkerException.class);
        assertThat(thrown.getMessage()).contains("Self-heal re-enriched it 2x");
        // Budget is exactly MAX_CYCLE_HEAL_ATTEMPTS re-enrich calls.
        verify(enrichLlm, org.mockito.Mockito.times(2))
                .enrichFeature(any(), any(), any(), any(), any(), any(), any());
        // Back-edge owner (featureB) left cleared so a container retry re-enriches just it.
        com.business.discovery.worker.service.llm.FeatureSpec featureB = fx.spec.getFeatures().stream()
                .filter(f -> "featureB".equals(f.getFeatureName())).findFirst().orElseThrow();
        assertThat(featureB.getFeatureInstruction()).isNull();
    }

    /** featureA depends on featureB and featureB depends back on featureA — a two-node cycle. */
    private CyclicFixture cyclicTwoFeatureSpec() {
        FileSpec aFile = FileSpec.builder()
                .fileName("A.java").filePath("backend/src/main/java/com/x/A.java")
                .fileType("BACKEND").featureName("featureA").build();
        FileSpec bFile = FileSpec.builder()
                .fileName("B.java").filePath("backend/src/main/java/com/x/B.java")
                .fileType("BACKEND").featureName("featureB").build();

        com.business.discovery.worker.service.llm.FeatureSpec featureA =
                com.business.discovery.worker.service.llm.FeatureSpec.builder()
                        .featureName("featureA").featureDisplayName("Feature A").featureType("BACKEND")
                        .filePaths(List.of(aFile.getFilePath())).featureInstruction("A enriched")
                        .dependsOnFeatures(new java.util.ArrayList<>(List.of("featureB"))).build();
        com.business.discovery.worker.service.llm.FeatureSpec featureB =
                com.business.discovery.worker.service.llm.FeatureSpec.builder()
                        .featureName("featureB").featureDisplayName("Feature B").featureType("BACKEND")
                        .filePaths(List.of(bFile.getFilePath())).featureInstruction("B enriched")
                        .dependsOnFeatures(new java.util.ArrayList<>(List.of("featureA"))).build();

        ArchitectureSpec spec = new ArchitectureSpec();
        spec.setFiles(new java.util.ArrayList<>(List.of(aFile, bFile)));
        spec.setFeatures(new java.util.ArrayList<>(List.of(featureA, featureB)));

        // Enrichment order: featureA first, featureB last → featureB owns the back edge.
        List<com.business.discovery.worker.service.llm.FeatureSpec> ordered = List.of(featureA, featureB);
        Map<String, List<FileSpec>> filesByFeature =
                Map.of("featureA", List.of(aFile), "featureB", List.of(bFile));
        return new CyclicFixture(spec, ordered, filesByFeature);
    }

    private void invokeEnforceAcyclic(ProjectPlanningNode node, CyclicFixture fx) throws Exception {
        java.lang.reflect.Method m = ProjectPlanningNode.class.getDeclaredMethod(
                "enforceAcyclicFeatureDependencies",
                ArchitectureSpec.class, List.class, Path.class, BriefContext.class,
                Map.class, com.business.discovery.worker.util.WorkspaceReader.class, String.class);
        m.setAccessible(true);
        m.invoke(node, fx.spec, fx.ordered, tempDir, null, fx.filesByFeature,
                new com.business.discovery.worker.util.WorkspaceReader(tempDir), null);
    }

    /** Runs a reflective invocation and returns the underlying cause of the thrown exception. */
    private Throwable catchInvocationCause(ThrowingBlock block) {
        try {
            block.run();
        } catch (java.lang.reflect.InvocationTargetException e) {
            return e.getCause();
        } catch (Exception e) {
            return e;
        }
        return null;
    }

    private interface ThrowingBlock { void run() throws Exception; }

    private record CyclicFixture(
            ArchitectureSpec spec,
            List<com.business.discovery.worker.service.llm.FeatureSpec> ordered,
            Map<String, List<FileSpec>> filesByFeature) {}

    @Test
    void slugUtil_handlesSpecialCharsAndLeadingDigits() {
        assertThat(SlugUtil.toSlug("Shree Restaurant")).isEqualTo("shreerestaurant");
        assertThat(SlugUtil.toSlug("Pizza & Co.")).isEqualTo("pizzaco");
        assertThat(SlugUtil.toSlug("123 ABC Shop")).isEqualTo("abcshop");
        assertThat(SlugUtil.toSlug(null)).isEqualTo("business");
        assertThat(SlugUtil.toSlug("   ")).isEqualTo("business");
    }

}
