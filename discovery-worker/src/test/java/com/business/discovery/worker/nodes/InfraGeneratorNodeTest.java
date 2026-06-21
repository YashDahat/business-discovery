package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.model.GeneratedFile;
import com.business.discovery.worker.repository.GeneratedFileRepository;
import com.business.discovery.worker.service.llm.BriefContext;
import com.business.discovery.worker.service.llm.FileEntry;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InfraGeneratorNodeTest {

    @Mock private LlmGeneratorService llm;
    @Mock private GeneratedFileRepository fileRepo;
    @Mock private WorkerContext ctx;

    @InjectMocks
    private InfraGeneratorNode node;

    @TempDir
    Path tempDir;

    @Test
    void infraFiles_generatedAndSaved() {
        UUID taskId = UUID.randomUUID();
        BriefContext briefCtx = mockBriefContext();

        List<FileEntry> manifest = List.of(
                new FileEntry("Dockerfile", FileType.INFRA, "Multi-stage Docker build"),
                new FileEntry("docker-compose.yml", FileType.INFRA, "Local dev compose"),
                new FileEntry("frontend/src/App.tsx", FileType.FRONTEND, "Root component")
        );

        when(ctx.getFileManifest()).thenReturn(manifest);
        when(ctx.getBriefCtx()).thenReturn(briefCtx);
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(taskId);
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(llm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), any()))
                .thenReturn("# generated infra content");

        node.execute(ctx);

        // Only INFRA files saved — frontend skipped
        verify(fileRepo, times(2)).save(argThat(f ->
                f.getFileType() == GeneratedFile.FileType.INFRA &&
                f.getStatus() == GeneratedFile.FileStatus.GENERATED));

        assertThat(tempDir.resolve("Dockerfile")).exists();
        assertThat(tempDir.resolve("docker-compose.yml")).exists();
    }

    @Test
    void ciWorkflow_alwaysWrittenIfAbsent() throws Exception {
        when(ctx.getFileManifest()).thenReturn(List.of());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());

        node.execute(ctx);

        Path ciPath = tempDir.resolve(".github/workflows/ci.yml");
        assertThat(ciPath).exists();
        String content = Files.readString(ciPath);
        assertThat(content).contains("actions/checkout");
        assertThat(content).contains("setup-java");
        assertThat(content).contains("setup-node");
    }

    @Test
    void ciWorkflow_notOverwrittenIfAlreadyExists() throws Exception {
        Path ciPath = tempDir.resolve(".github/workflows/ci.yml");
        Files.createDirectories(ciPath.getParent());
        Files.writeString(ciPath, "# custom workflow");

        when(ctx.getFileManifest()).thenReturn(List.of());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());

        node.execute(ctx);

        assertThat(Files.readString(ciPath)).isEqualTo("# custom workflow");
    }

    @Test
    void noInfraFiles_onlyCiWorkflowWritten() {
        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry("backend/src/main/java/com/test/model/Product.java", FileType.BACKEND, "Product entity")
        ));
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());

        node.execute(ctx);

        verifyNoInteractions(fileRepo);
        verifyNoInteractions(llm);
        assertThat(tempDir.resolve(".github/workflows/ci.yml")).exists();
    }

    @Test
    void retryMode_existingInfraFile_isSkipped() throws Exception {
        Files.writeString(tempDir.resolve("Dockerfile"), "# existing");

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry("Dockerfile", FileType.INFRA, "Multi-stage build")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext()); // requestedChanges = null
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(llm);
        verifyNoInteractions(fileRepo);
        assertThat(Files.readString(tempDir.resolve("Dockerfile"))).isEqualTo("# existing");
    }

    @Test
    void requestedChangesMode_existingInfraFile_isRegenerated() throws Exception {
        Files.writeString(tempDir.resolve("Dockerfile"), "# old");

        BriefContext briefCtxWithChanges = new BriefContext(
                "Test Business", "Restaurant", "Pune", "INFORMATIONAL",
                List.of(), List.of(), List.of(), Map.of(), List.of(),
                "modern", "blue", "professional", "", "", "",
                "Add Redis service", null,
                "", "", "", "", "");

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry("Dockerfile", FileType.INFRA, "Multi-stage build")
        ));
        when(ctx.getBriefCtx()).thenReturn(briefCtxWithChanges);
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(2);
        when(llm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), any()))
                .thenReturn("# updated Dockerfile");

        node.execute(ctx);

        verify(llm).generateFileContent(anyString(), anyString(), anyString(), anyMap(), any());
        assertThat(Files.readString(tempDir.resolve("Dockerfile"))).isEqualTo("# updated Dockerfile");
    }

    private BriefContext mockBriefContext() {
        return new BriefContext("Test Business", "Restaurant", "Pune",
                "INFORMATIONAL", List.of(), List.of(), List.of(), Map.of(), List.of(),
                "modern", "blue", "professional", "", "", "", null, null,
                "", "", "", "", "");
    }
}
