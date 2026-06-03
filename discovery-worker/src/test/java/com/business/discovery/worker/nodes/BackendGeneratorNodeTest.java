package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.model.GeneratedFile;
import com.business.discovery.worker.repository.GeneratedFileRepository;
import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.BriefContext;
import com.business.discovery.worker.service.llm.FileEntry;
import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.business.discovery.worker.util.ArchitectureJsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BackendGeneratorNodeTest {

    @Mock private LlmGeneratorService llm;
    @Mock private GeneratedFileRepository fileRepo;
    @Mock private WorkerContext ctx;

    @InjectMocks
    private BackendGeneratorNode node;

    @TempDir
    Path tempDir;

    // ── Basic generation ──────────────────────────────────────────────────

    @Test
    void twoBackendFiles_generatesRowsAndWritesToDisk() throws Exception {
        UUID taskId = UUID.randomUUID();
        BriefContext briefCtx = mockBriefContext();

        List<FileEntry> manifest = List.of(
                new FileEntry("backend/src/main/java/com/test/entity/Product.java", FileType.BACKEND, "Product entity"),
                new FileEntry("backend/src/main/java/com/test/repository/ProductRepository.java", FileType.BACKEND, "Product repo"),
                new FileEntry("frontend/src/App.tsx", FileType.FRONTEND, "Root component")
        );

        when(ctx.getFileManifest()).thenReturn(manifest);
        when(ctx.getBriefCtx()).thenReturn(briefCtx);
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(taskId);
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(llm.generateFileContent(anyString(), anyString(), anyString(), eq(briefCtx), anyList(), any(), anyString(), any()))
                .thenReturn("// generated content");

        node.execute(ctx);

        ArgumentCaptor<GeneratedFile> captor = ArgumentCaptor.forClass(GeneratedFile.class);
        verify(fileRepo, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allMatch(f -> f.getFileType() == GeneratedFile.FileType.BACKEND);
        assertThat(captor.getAllValues()).allMatch(f -> f.getStatus() == GeneratedFile.FileStatus.GENERATED);
        assertThat(tempDir.resolve("backend/src/main/java/com/test/entity/Product.java")).exists();
        assertThat(tempDir.resolve("backend/src/main/java/com/test/repository/ProductRepository.java")).exists();
        assertThat(tempDir.resolve("frontend/src/App.tsx")).doesNotExist();
    }

    @Test
    void noBackendFiles_savesNothingAndCompletes() {
        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry("frontend/src/App.tsx", FileType.FRONTEND, "Root component")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(fileRepo, llm);
    }

    @Test
    void llmThrowsInfraException_propagates() {
        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry("backend/src/main/java/com/test/entity/Order.java", FileType.BACKEND, "Order entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(llm.generateFileContent(anyString(), anyString(), anyString(), any(), anyList(), any(), anyString(), any()))
                .thenThrow(new WorkerException(FailureType.INFRA, "LLM timeout"));

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> assertThat(((WorkerException) ex).getFailureType()).isEqualTo(FailureType.INFRA));

        verifyNoInteractions(fileRepo);
    }

    // ── Layer ordering ────────────────────────────────────────────────────

    @Test
    void serviceGeneratedAfterEntity_whenManifestIsReversed() throws Exception {
        // Manifest lists service BEFORE entity — node must sort and generate entity first
        List<FileEntry> manifest = List.of(
                new FileEntry("backend/src/main/java/com/test/service/OrderService.java", FileType.BACKEND, "Order service"),
                new FileEntry("backend/src/main/java/com/test/entity/Order.java", FileType.BACKEND, "Order entity")
        );

        when(ctx.getFileManifest()).thenReturn(manifest);
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(llm.generateFileContent(anyString(), anyString(), anyString(), any(), anyList(), any(), anyString(), any()))
                .thenReturn("// content");

        node.execute(ctx);

        InOrder inOrder = inOrder(llm);
        // entity (priority 10) must be called before service (priority 50)
        inOrder.verify(llm).generateFileContent(
                contains("entity/Order.java"), anyString(), eq("ENTITY"), any(), anyList(), any(), anyString(), any());
        inOrder.verify(llm).generateFileContent(
                contains("service/OrderService.java"), anyString(), eq("SERVICE"), any(), anyList(), any(), anyString(), any());
    }

    @Test
    void layerNamePassedToLlm() throws Exception {
        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry("backend/src/main/java/com/test/controller/MenuController.java", FileType.BACKEND, "Menu controller")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(llm.generateFileContent(anyString(), anyString(), anyString(), any(), anyList(), any(), anyString(), any()))
                .thenReturn("// content");

        node.execute(ctx);

        verify(llm).generateFileContent(anyString(), anyString(), eq("CONTROLLER"), any(), anyList(), any(), anyString(), any());
    }

    // ── ARCHITECTURE.json status update ───────────────────────────────────

    @Test
    void architectureJsonUpdatedToGeneratedAfterEachWrite() throws Exception {
        String filePath = "backend/src/main/java/com/test/entity/Order.java";
        FileSpec plannedSpec = FileSpec.builder()
                .filePath(filePath).fileType("BACKEND").status("PLANNED").build();
        ArchitectureJsonUtil.write(tempDir, ArchitectureSpec.builder().files(List.of(plannedSpec)).build());

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.BACKEND, "Order entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(llm.generateFileContent(anyString(), anyString(), anyString(), any(), anyList(), any(), anyString(), any()))
                .thenReturn("// entity content");

        node.execute(ctx);

        ArchitectureSpec updated = ArchitectureJsonUtil.read(tempDir);
        assertThat(updated.getFiles().get(0).getStatus()).isEqualTo("GENERATED");
    }

    // ── Skip / retry behaviour ────────────────────────────────────────────

    @Test
    void retryMode_validatedFile_isSkipped() throws Exception {
        String filePath = "backend/src/main/java/com/test/entity/Product.java";
        Path existingFile = tempDir.resolve(filePath);
        Files.createDirectories(existingFile.getParent());
        Files.writeString(existingFile, "// existing content");
        writeArchitectureJson(tempDir, filePath, "BACKEND", "VALIDATED");

        when(ctx.getFileManifest()).thenReturn(List.of(new FileEntry(filePath, FileType.BACKEND, "Product entity")));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(llm, fileRepo);
        assertThat(Files.readString(existingFile)).isEqualTo("// existing content");
    }

    @Test
    void retryMode_existingFileNotValidated_passedToLlmForReview() throws Exception {
        String filePath = "backend/src/main/java/com/test/entity/Product.java";
        Path existingFile = tempDir.resolve(filePath);
        Files.createDirectories(existingFile.getParent());
        Files.writeString(existingFile, "// broken content from last attempt");

        when(ctx.getFileManifest()).thenReturn(List.of(new FileEntry(filePath, FileType.BACKEND, "Product entity")));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(2);
        when(llm.generateFileContent(anyString(), anyString(), anyString(), any(), anyList(),
                eq("// broken content from last attempt"), anyString(), any()))
                .thenReturn("// fixed content");

        node.execute(ctx);

        verify(llm).generateFileContent(anyString(), anyString(), anyString(), any(), anyList(),
                eq("// broken content from last attempt"), anyString(), any());
        assertThat(Files.readString(existingFile)).isEqualTo("// fixed content");
    }

    @Test
    void requestedChangesMode_existingFile_isRegenerated() throws Exception {
        Path existingFile = tempDir.resolve("backend/src/main/java/com/test/entity/Product.java");
        Files.createDirectories(existingFile.getParent());
        Files.writeString(existingFile, "// old content");

        BriefContext briefCtxWithChanges = new BriefContext(
                "Test Business", "Restaurant", "Pune", "INFORMATIONAL",
                List.of(), List.of(), java.util.Map.of(), List.of(),
                "modern", "blue", "professional", "", "", "",
                "Add inventory field", null);

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry("backend/src/main/java/com/test/entity/Product.java", FileType.BACKEND, "Product entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(briefCtxWithChanges);
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(2);
        when(llm.generateFileContent(anyString(), anyString(), anyString(), any(), anyList(), any(), anyString(), any()))
                .thenReturn("// updated content");

        node.execute(ctx);

        verify(llm).generateFileContent(anyString(), anyString(), anyString(), any(), anyList(), any(), anyString(), any());
        assertThat(Files.readString(existingFile)).isEqualTo("// updated content");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private BriefContext mockBriefContext() {
        return new BriefContext("Test Business", "Restaurant", "Pune",
                "INFORMATIONAL", List.of(), List.of(), java.util.Map.of(), List.of(),
                "modern", "blue", "professional", "", "", "", null, null);
    }

    private void writeArchitectureJson(Path workspace, String filePath, String fileType, String status) throws Exception {
        FileSpec fileSpec = FileSpec.builder().filePath(filePath).fileType(fileType).status(status).build();
        ArchitectureJsonUtil.write(workspace, ArchitectureSpec.builder().files(List.of(fileSpec)).build());
    }
}
