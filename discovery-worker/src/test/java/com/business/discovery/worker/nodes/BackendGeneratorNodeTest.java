package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.repository.GeneratedFileRepository;
import com.business.discovery.worker.service.GitService;
import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.BriefContext;
import com.business.discovery.worker.service.llm.FeatureSpec;
import com.business.discovery.worker.service.llm.FileEntry;
import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.business.discovery.worker.util.ArchitectureJsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BackendGeneratorNodeTest {

    @Mock private LlmGeneratorService flashLlm;
    @Mock private GeneratedFileRepository fileRepo;
    @Mock private GitService gitService;
    @Mock private WorkerContext ctx;

    private BackendGeneratorNode node;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        node = new BackendGeneratorNode(flashLlm, fileRepo, gitService);
        lenient().doNothing().when(gitService).commitAndPushCheckpoint(any(), any(), any());
        lenient().when(fileRepo.findByTaskIdAndFilePath(any(), any())).thenReturn(java.util.Optional.empty());
    }

    // ── Core generation ───────────────────────────────────────────────────

    @Test
    void twoBackendFiles_generated_writtenToDisk() throws Exception {
        String entityPath = "backend/src/main/java/com/test/entity/Product.java";
        String repoPath   = "backend/src/main/java/com/test/repository/ProductRepository.java";

        writeSpec(tempDir,
                specEntry(entityPath, "BACKEND", "PLANNED", "instruction entity"),
                specEntry(repoPath,   "BACKEND", "PLANNED", "instruction repo"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(entityPath, FileType.BACKEND, "Product entity"),
                new FileEntry(repoPath,   FileType.BACKEND, "Product repo"),
                new FileEntry("frontend/src/App.tsx", FileType.FRONTEND, "Root component")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(ctx.getGithubBranch()).thenReturn("feature/test");
        when(flashLlm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), any()))
                .thenReturn("public class Placeholder {}");

        node.execute(ctx);

        assertThat(tempDir.resolve(entityPath)).exists();
        assertThat(tempDir.resolve(repoPath)).exists();
        // frontend file must not be touched
        assertThat(tempDir.resolve("frontend/src/App.tsx")).doesNotExist();
    }

    @Test
    void noBackendFiles_doesNothing() {
        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry("frontend/src/App.tsx", FileType.FRONTEND, "Root component")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(flashLlm, fileRepo, gitService);
    }

    @Test
    void fileWithNoFeatureInstruction_isSkipped() throws Exception {
        String filePath = "backend/src/main/java/com/test/entity/Order.java";
        writeSpec(tempDir, specEntry(filePath, "BACKEND", "PLANNED", null));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.BACKEND, "Order entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(flashLlm, fileRepo);
    }

    @Test
    void llmThrowsInfraException_propagates() throws Exception {
        String filePath = "backend/src/main/java/com/test/entity/Order.java";
        writeSpec(tempDir, specEntry(filePath, "BACKEND", "PLANNED", "instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.BACKEND, "Order entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getGithubBranch()).thenReturn("feature/test");
        when(flashLlm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), any()))
                .thenThrow(new WorkerException(FailureType.INFRA, "LLM timeout"));

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> assertThat(((WorkerException) ex).getFailureType()).isEqualTo(FailureType.INFRA));
    }

    // ── Layer ordering ────────────────────────────────────────────────────

    @Test
    void serviceGeneratedAfterEntity_whenManifestIsReversed() throws Exception {
        String servicePath = "backend/src/main/java/com/test/service/OrderService.java";
        String entityPath  = "backend/src/main/java/com/test/entity/Order.java";

        writeSpec(tempDir,
                specEntry(servicePath, "BACKEND", "PLANNED", "INSTRUCTION_SERVICE"),
                specEntry(entityPath,  "BACKEND", "PLANNED", "INSTRUCTION_ENTITY"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(servicePath, FileType.BACKEND, "Order service"),
                new FileEntry(entityPath,  FileType.BACKEND, "Order entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(ctx.getGithubBranch()).thenReturn("feature/test");
        when(flashLlm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), any()))
                .thenReturn("public class Placeholder {}");

        node.execute(ctx);

        InOrder inOrder = inOrder(flashLlm);
        inOrder.verify(flashLlm).generateFileContent(anyString(), eq("INSTRUCTION_ENTITY"), anyString(), anyMap(), any());
        inOrder.verify(flashLlm).generateFileContent(anyString(), eq("INSTRUCTION_SERVICE"), anyString(), anyMap(), any());
    }

    @Test
    void checkpointCommittedAfterEachLayer() throws Exception {
        String entityPath  = "backend/src/main/java/com/test/entity/Order.java";
        String servicePath = "backend/src/main/java/com/test/service/OrderService.java";

        writeSpec(tempDir,
                specEntry(entityPath,  "BACKEND", "PLANNED", "instruction"),
                specEntry(servicePath, "BACKEND", "PLANNED", "instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(entityPath,  FileType.BACKEND, "Order entity"),
                new FileEntry(servicePath, FileType.BACKEND, "Order service")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(ctx.getGithubBranch()).thenReturn("feature/test");
        when(flashLlm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), any()))
                .thenReturn("public class Placeholder {}");

        node.execute(ctx);

        // One checkpoint per distinct layer (entity=10, service=50 → 2 calls)
        verify(gitService, times(2)).commitAndPushCheckpoint(eq(tempDir), anyString(), eq("feature/test"));
    }

    // ── Skip behaviour ────────────────────────────────────────────────────

    @Test
    void specCompliantFile_isSkipped() throws Exception {
        String filePath = "backend/src/main/java/com/test/entity/Product.java";
        Path existing = tempDir.resolve(filePath);
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "// existing");

        writeSpec(tempDir, specEntry(filePath, "BACKEND", "SPEC_COMPLIANT", "instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.BACKEND, "Product entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(flashLlm, fileRepo);
        assertThat(Files.readString(existing)).isEqualTo("// existing");
    }

    @Test
    void validatedFile_isSkipped() throws Exception {
        String filePath = "backend/src/main/java/com/test/entity/Product.java";
        Path existing = tempDir.resolve(filePath);
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "// validated");

        writeSpec(tempDir, specEntry(filePath, "BACKEND", "VALIDATED", "instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.BACKEND, "Product entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(flashLlm, fileRepo);
        assertThat(Files.readString(existing)).isEqualTo("// validated");
    }

    @Test
    void generationFailedFile_isSkipped() throws Exception {
        String filePath = "backend/src/main/java/com/test/entity/Product.java";
        writeSpec(tempDir, specEntry(filePath, "BACKEND", "GENERATION_FAILED", "instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.BACKEND, "Product entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(flashLlm, fileRepo);
    }

    // ── requestedChanges mode ─────────────────────────────────────────────

    @Test
    void requestedChangesMode_changeRequiredTrue_regeneratesFile() throws Exception {
        String filePath = "backend/src/main/java/com/test/entity/Product.java";
        Path existing = tempDir.resolve(filePath);
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "// old content");

        FileSpec spec = specEntry(filePath, "BACKEND", "VALIDATED", "update instruction");
        writeSpecWithFeature(tempDir, spec, "update instruction", true);

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.BACKEND, "Product entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(briefContextWithChanges("Add inventory field"));
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(2);
        when(ctx.getGithubBranch()).thenReturn("feature/test");
        when(flashLlm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), eq("// old content")))
                .thenReturn("// updated content");

        node.execute(ctx);

        verify(flashLlm).generateFileContent(anyString(), eq("update instruction"), anyString(), anyMap(), eq("// old content"));
        assertThat(Files.readString(existing)).isEqualTo("// updated content");
    }

    @Test
    void requestedChangesMode_changeRequiredFalse_fileIsSkipped() throws Exception {
        String filePath = "backend/src/main/java/com/test/entity/Product.java";

        FileSpec spec = specEntry(filePath, "BACKEND", "VALIDATED", "instruction");
        writeSpecWithFeature(tempDir, spec, "instruction", false);

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.BACKEND, "Product entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(briefContextWithChanges("Change color scheme"));
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(flashLlm, fileRepo);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private BriefContext mockBriefContext() {
        return new BriefContext("Test Business", "Restaurant", "Pune",
                "INFORMATIONAL", List.of(), List.of(), List.of(), Map.of(), List.of(),
                "modern", "blue", "professional", "", "", "", null, null,
                "", "", "", "", "");
    }

    private BriefContext briefContextWithChanges(String changes) {
        return new BriefContext("Test Business", "Restaurant", "Pune",
                "INFORMATIONAL", List.of(), List.of(), List.of(), Map.of(), List.of(),
                "modern", "blue", "professional", "", "", "", changes, null,
                "", "", "", "", "");
    }

    private FileSpec specEntry(String filePath, String fileType, String status, String instruction) {
        String featureName = instruction != null ? featureNameFor(filePath) : null;
        return FileSpec.builder()
                .filePath(filePath).fileType(fileType).status(status)
                .featureName(featureName)
                .fileRole(instruction != null ? instruction : "")
                .build();
    }

    private static String featureNameFor(String filePath) {
        String name = filePath.substring(filePath.lastIndexOf('/') + 1).replace('.', '-');
        return "feat-" + name.toLowerCase();
    }

    private void writeSpec(Path workspace, FileSpec... entries) throws Exception {
        Map<String, List<FileSpec>> byFeature = new LinkedHashMap<>();
        for (FileSpec e : entries) {
            if (e.getFeatureName() != null)
                byFeature.computeIfAbsent(e.getFeatureName(), k -> new ArrayList<>()).add(e);
        }
        List<FeatureSpec> features = byFeature.entrySet().stream()
                .map(entry -> FeatureSpec.builder()
                        .featureName(entry.getKey())
                        .featureType("BACKEND")
                        .featureInstruction(entry.getValue().get(0).getFileRole())
                        .changeRequired(true)
                        .filePaths(entry.getValue().stream().map(FileSpec::getFilePath).toList())
                        .build())
                .toList();
        ArchitectureJsonUtil.write(workspace,
                ArchitectureSpec.builder().files(List.of(entries)).features(features).build());
    }

    private void writeSpecWithFeature(Path workspace, FileSpec spec,
                                      String featureInstruction, boolean changeRequired) throws Exception {
        String featureName = spec.getFeatureName() != null ? spec.getFeatureName() : "test-feature";
        List<FeatureSpec> features = List.of(FeatureSpec.builder()
                .featureName(featureName)
                .featureType("BACKEND")
                .featureInstruction(featureInstruction)
                .changeRequired(changeRequired)
                .filePaths(List.of(spec.getFilePath()))
                .build());
        ArchitectureJsonUtil.write(workspace,
                ArchitectureSpec.builder().files(List.of(spec)).features(features).build());
    }
}
