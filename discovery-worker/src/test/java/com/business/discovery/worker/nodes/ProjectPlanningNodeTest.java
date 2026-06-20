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
    @Mock private SpringInitializrClient initializrClient;
    @Mock private WorkerContext ctx;

    @TempDir Path tempDir;

    @Test
    void execute_setsManifestFromArchSpec() {
        org.mockito.Mockito.lenient().when(initializrClient.filterValidDependencies(any()))
                .thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.lenient().when(initializrClient.getDefaultBootVersion())
                .thenReturn("3.4.5");

        ProjectPlanningNode node = new ProjectPlanningNode(llm, initializrClient);

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
    void slugUtil_handlesSpecialCharsAndLeadingDigits() {
        assertThat(SlugUtil.toSlug("Shree Restaurant")).isEqualTo("shreerestaurant");
        assertThat(SlugUtil.toSlug("Pizza & Co.")).isEqualTo("pizzaco");
        assertThat(SlugUtil.toSlug("123 ABC Shop")).isEqualTo("abcshop");
        assertThat(SlugUtil.toSlug(null)).isEqualTo("business");
        assertThat(SlugUtil.toSlug("   ")).isEqualTo("business");
    }

}
