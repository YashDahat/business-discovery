package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.model.GeneratedFile;
import com.business.discovery.worker.repository.GeneratedFileRepository;
import com.business.discovery.worker.service.BuildToolService;
import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.BriefContext;
import com.business.discovery.worker.service.llm.ComplianceResult;
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
    @Mock private LlmGeneratorService proLlm;
    @Mock private LlmGeneratorService fixLlm;
    @Mock private BuildToolService buildToolService;
    @Mock private GeneratedFileRepository fileRepo;
    @Mock private WorkerContext ctx;

    private BackendGeneratorNode node;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        node = new BackendGeneratorNode(flashLlm, proLlm, fixLlm, buildToolService, fileRepo);
        lenient().when(buildToolService.runMvnCompile(any(Path.class)))
                .thenReturn(new BuildToolService.BuildResult(0, ""));
        lenient().when(proLlm.checkSpecCompliance(any(), any(), any()))
                .thenReturn(ComplianceResult.ok());
    }

    // ── Full 3-stage pipeline ─────────────────────────────────────────────

    @Test
    void twoBackendFiles_allThreeStages_finalStatusSpecCompliant() throws Exception {
        UUID taskId = UUID.randomUUID();

        String entityPath = "backend/src/main/java/com/test/entity/Product.java";
        String repoPath   = "backend/src/main/java/com/test/repository/ProductRepository.java";

        writeSpec(tempDir,
                specEntry(entityPath, "BACKEND", "PLANNED", "// instruction entity"),
                specEntry(repoPath,   "BACKEND", "PLANNED", "// instruction repo"));

        List<FileEntry> manifest = List.of(
                new FileEntry(entityPath, FileType.BACKEND, "Product entity"),
                new FileEntry(repoPath,   FileType.BACKEND, "Product repo"),
                new FileEntry("frontend/src/App.tsx", FileType.FRONTEND, "Root component")
        );

        when(ctx.getFileManifest()).thenReturn(manifest);
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(taskId);
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(flashLlm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), any()))
                .thenReturn("// generated content");

        node.execute(ctx);

        assertThat(tempDir.resolve(entityPath)).exists();
        assertThat(tempDir.resolve(repoPath)).exists();
        assertThat(tempDir.resolve("frontend/src/App.tsx")).doesNotExist();

        ArchitectureSpec updated = ArchitectureJsonUtil.read(tempDir);
        assertThat(updated.getFiles())
                .allMatch(f -> "SPEC_COMPLIANT".equals(f.getStatus()));
    }

    @Test
    void noBackendFiles_savesNothingAndCompletes() {
        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry("frontend/src/App.tsx", FileType.FRONTEND, "Root component")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(fileRepo, flashLlm, proLlm, fixLlm, buildToolService);
    }

    @Test
    void fileWithNoFeatureInstruction_isSkippedWithWarning() throws Exception {
        String filePath = "backend/src/main/java/com/test/entity/Order.java";
        // specEntry with null instruction → featureName is null → feature lookup returns null → skip
        writeSpec(tempDir, specEntry(filePath, "BACKEND", "PLANNED", null));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.BACKEND, "Order entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(flashLlm, fileRepo, buildToolService, proLlm);
    }

    @Test
    void flashLlmThrowsInfraException_propagates() throws Exception {
        String filePath = "backend/src/main/java/com/test/entity/Order.java";
        writeSpec(tempDir, specEntry(filePath, "BACKEND", "PLANNED", "// instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.BACKEND, "Order entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(flashLlm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), any()))
                .thenThrow(new WorkerException(FailureType.INFRA, "LLM timeout"));

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> assertThat(((WorkerException) ex).getFailureType()).isEqualTo(FailureType.INFRA));

        verifyNoInteractions(fileRepo);
    }

    // ── Layer ordering ────────────────────────────────────────────────────

    @Test
    void serviceGeneratedAfterEntity_whenManifestIsReversed() throws Exception {
        String servicePath = "backend/src/main/java/com/test/service/OrderService.java";
        String entityPath  = "backend/src/main/java/com/test/entity/Order.java";

        writeSpec(tempDir,
                specEntry(servicePath, "BACKEND", "PLANNED", "INSTRUCTION_SERVICE"),
                specEntry(entityPath,  "BACKEND", "PLANNED", "INSTRUCTION_ENTITY"));

        List<FileEntry> manifest = List.of(
                new FileEntry(servicePath, FileType.BACKEND, "Order service"),
                new FileEntry(entityPath,  FileType.BACKEND, "Order entity")
        );

        when(ctx.getFileManifest()).thenReturn(manifest);
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(flashLlm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), any())).thenReturn("// content");

        node.execute(ctx);

        InOrder inOrder = inOrder(flashLlm);
        inOrder.verify(flashLlm).generateFileContent(anyString(), eq("INSTRUCTION_ENTITY"), anyString(), anyMap(), any());
        inOrder.verify(flashLlm).generateFileContent(anyString(), eq("INSTRUCTION_SERVICE"), anyString(), anyMap(), any());
    }

    // ── Skip behaviour ────────────────────────────────────────────────────

    @Test
    void specCompliantFile_isSkipped() throws Exception {
        String filePath = "backend/src/main/java/com/test/entity/Product.java";
        Path existingFile = tempDir.resolve(filePath);
        Files.createDirectories(existingFile.getParent());
        Files.writeString(existingFile, "// existing content");

        writeSpec(tempDir, specEntry(filePath, "BACKEND", "SPEC_COMPLIANT", "// instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.BACKEND, "Product entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(flashLlm, proLlm, fixLlm, fileRepo, buildToolService);
        assertThat(Files.readString(existingFile)).isEqualTo("// existing content");
    }

    @Test
    void generationFailedFile_isSkipped() throws Exception {
        String filePath = "backend/src/main/java/com/test/entity/Product.java";
        writeSpec(tempDir, specEntry(filePath, "BACKEND", "GENERATION_FAILED", "// instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.BACKEND, "Product entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(flashLlm, proLlm, fixLlm);
    }

    @Test
    void generatedFile_skipsStage3_runsStage1And2() throws Exception {
        String filePath = "backend/src/main/java/com/test/entity/Product.java";
        Path existingFile = tempDir.resolve(filePath);
        Files.createDirectories(existingFile.getParent());
        Files.writeString(existingFile, "// previously generated content");

        writeSpec(tempDir, specEntry(filePath, "BACKEND", "GENERATED", "// instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.BACKEND, "Product entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(2);

        node.execute(ctx);

        assertThat(Files.readString(existingFile)).isEqualTo("// previously generated content");
        verifyNoInteractions(flashLlm);
        verify(buildToolService).runMvnCompile(any(Path.class));
        verify(proLlm).checkSpecCompliance(eq(filePath), any(), contains("// instruction"));

        ArchitectureSpec updated = ArchitectureJsonUtil.read(tempDir);
        assertThat(updated.getFiles().get(0).getStatus()).isEqualTo("SPEC_COMPLIANT");
    }

    @Test
    void validatedFile_skipsStage3And1_runsStage2Only() throws Exception {
        String filePath = "backend/src/main/java/com/test/entity/Product.java";
        Path existingFile = tempDir.resolve(filePath);
        Files.createDirectories(existingFile.getParent());
        Files.writeString(existingFile, "// validated content");

        writeSpec(tempDir, specEntry(filePath, "BACKEND", "VALIDATED", "// instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.BACKEND, "Product entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(2);

        node.execute(ctx);

        verifyNoInteractions(flashLlm, buildToolService, fixLlm);
        verify(proLlm).checkSpecCompliance(eq(filePath), eq("// validated content"), contains("// instruction"));

        ArchitectureSpec updated = ArchitectureJsonUtil.read(tempDir);
        assertThat(updated.getFiles().get(0).getStatus()).isEqualTo("SPEC_COMPLIANT");
    }

    // ── Stage 1: compile + fix ────────────────────────────────────────────

    @Test
    void compileFails_fixAttemptSucceeds_becomesValidatedThenSpecCompliant() throws Exception {
        String filePath = "backend/src/main/java/com/test/entity/Product.java";
        Path existingFile = tempDir.resolve(filePath);
        Files.createDirectories(existingFile.getParent());
        Files.writeString(existingFile, "// broken");

        writeSpec(tempDir, specEntry(filePath, "BACKEND", "PLANNED", "// instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.BACKEND, "Product entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(flashLlm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), any())).thenReturn("// broken");

        when(buildToolService.runMvnCompile(any()))
                .thenReturn(new BuildToolService.BuildResult(1, "error: ';' expected"))
                .thenReturn(new BuildToolService.BuildResult(0, ""));
        when(fixLlm.fixFileContent(any(), any(), any(), anyMap())).thenReturn("// fixed");

        node.execute(ctx);

        verify(fixLlm).fixFileContent(eq(filePath), eq("// broken"), eq("error: ';' expected"), anyMap());
        assertThat(Files.readString(existingFile)).isEqualTo("// fixed");

        ArchitectureSpec updated = ArchitectureJsonUtil.read(tempDir);
        assertThat(updated.getFiles().get(0).getStatus()).isEqualTo("SPEC_COMPLIANT");
    }

    @Test
    void compileFails_maxRetriesExhausted_becomesGenerationFailed() throws Exception {
        String filePath = "backend/src/main/java/com/test/entity/Product.java";
        Path existingFile = tempDir.resolve(filePath);
        Files.createDirectories(existingFile.getParent());
        Files.writeString(existingFile, "// broken");

        writeSpec(tempDir, specEntry(filePath, "BACKEND", "PLANNED", "// instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.BACKEND, "Product entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(flashLlm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), any())).thenReturn("// broken");
        when(buildToolService.runMvnCompile(any()))
                .thenReturn(new BuildToolService.BuildResult(1, "compile error"));
        when(fixLlm.fixFileContent(any(), any(), any(), anyMap())).thenReturn("// still broken");

        node.execute(ctx);

        verify(buildToolService, times(4)).runMvnCompile(any());
        verify(fixLlm, times(3)).fixFileContent(any(), any(), any(), anyMap());
        verifyNoInteractions(proLlm);

        ArchitectureSpec updated = ArchitectureJsonUtil.read(tempDir);
        assertThat(updated.getFiles().get(0).getStatus()).isEqualTo("GENERATION_FAILED");
    }

    // ── Stage 2: spec drift + correction ─────────────────────────────────

    @Test
    void specDrift_regeneratesWithCorrections_secondCheckCompliant() throws Exception {
        String filePath = "backend/src/main/java/com/test/entity/Product.java";
        Path existingFile = tempDir.resolve(filePath);
        Files.createDirectories(existingFile.getParent());
        Files.writeString(existingFile, "// planned content");

        writeSpec(tempDir, specEntry(filePath, "BACKEND", "PLANNED", "// instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.BACKEND, "Product entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(flashLlm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), any())).thenReturn("// generated");

        when(proLlm.checkSpecCompliance(any(), any(), any()))
                .thenReturn(ComplianceResult.drift(List.of("Missing method processPayment()")))
                .thenReturn(ComplianceResult.ok());

        node.execute(ctx);

        // Flash called twice: initial generation + correction round
        verify(flashLlm, times(2)).generateFileContent(anyString(), anyString(), anyString(), anyMap(), any());

        // Second Flash call had corrections appended to fileRole (2nd arg)
        verify(flashLlm).generateFileContent(
                anyString(), anyString(), contains("Fix these spec deviations"), anyMap(), isNull());

        // Stage 1 ran twice (initial + after correction)
        verify(buildToolService, times(2)).runMvnCompile(any());

        ArchitectureSpec updated = ArchitectureJsonUtil.read(tempDir);
        assertThat(updated.getFiles().get(0).getStatus()).isEqualTo("SPEC_COMPLIANT");
    }

    @Test
    void specDrift_maxRoundsExhausted_leavesAsValidated() throws Exception {
        String filePath = "backend/src/main/java/com/test/entity/Product.java";
        Path existingFile = tempDir.resolve(filePath);
        Files.createDirectories(existingFile.getParent());

        writeSpec(tempDir, specEntry(filePath, "BACKEND", "VALIDATED", "// instruction"));
        Files.writeString(existingFile, "// validated content");

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.BACKEND, "Product entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(flashLlm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), any())).thenReturn("// corrected");

        when(proLlm.checkSpecCompliance(any(), any(), any()))
                .thenReturn(ComplianceResult.drift(List.of("Missing endpoint")));

        node.execute(ctx);

        verify(flashLlm, times(1)).generateFileContent(anyString(), anyString(), anyString(), anyMap(), any());

        ArchitectureSpec updated = ArchitectureJsonUtil.read(tempDir);
        assertThat(updated.getFiles().get(0).getStatus()).isEqualTo("VALIDATED");
    }

    // ── requestedChanges mode ─────────────────────────────────────────────

    @Test
    void requestedChangesMode_changeRequiredTrue_passesExistingContentToFlash() throws Exception {
        String filePath = "backend/src/main/java/com/test/entity/Product.java";
        Path existingFile = tempDir.resolve(filePath);
        Files.createDirectories(existingFile.getParent());
        Files.writeString(existingFile, "// old content");

        FileSpec spec = specEntry(filePath, "BACKEND", "VALIDATED", "// update instruction");
        writeSpecWithFeature(tempDir, spec, "// update instruction", true);

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.BACKEND, "Product entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(briefContextWithChanges("Add inventory field"));
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(2);
        when(flashLlm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), eq("// old content")))
                .thenReturn("// updated content");

        node.execute(ctx);

        verify(flashLlm).generateFileContent(
                anyString(), eq("// update instruction"), anyString(), anyMap(), eq("// old content"));
        assertThat(Files.readString(existingFile)).isEqualTo("// updated content");
    }

    @Test
    void requestedChangesMode_changeRequiredFalse_fileIsSkipped() throws Exception {
        String filePath = "backend/src/main/java/com/test/entity/Product.java";

        FileSpec spec = specEntry(filePath, "BACKEND", "VALIDATED", "// instruction");
        writeSpecWithFeature(tempDir, spec, "// instruction", false);

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.BACKEND, "Product entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(briefContextWithChanges("Change color scheme"));
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(flashLlm, proLlm, fixLlm, fileRepo, buildToolService);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private BriefContext mockBriefContext() {
        return new BriefContext("Test Business", "Restaurant", "Pune",
                "INFORMATIONAL", List.of(), List.of(), List.of(), java.util.Map.of(), List.of(),
                "modern", "blue", "professional", "", "", "", null, null,
                "", "", "", "", "");
    }

    private BriefContext briefContextWithChanges(String changes) {
        return new BriefContext("Test Business", "Restaurant", "Pune",
                "INFORMATIONAL", List.of(), List.of(), List.of(), java.util.Map.of(), List.of(),
                "modern", "blue", "professional", "", "", "", changes, null,
                "", "", "", "", "");
    }

    /** Creates a FileSpec where the instruction becomes both featureInstruction (via writeSpec) and fileRole. */
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

    /**
     * Writes spec with one FeatureSpec per unique featureName.
     * featureInstruction = fileRole of the first file in that feature.
     * changeRequired = true (default).
     */
    private void writeSpec(Path workspace, FileSpec... entries) throws Exception {
        Map<String, List<FileSpec>> byFeature = new LinkedHashMap<>();
        for (FileSpec e : entries) {
            if (e.getFeatureName() != null) {
                byFeature.computeIfAbsent(e.getFeatureName(), k -> new ArrayList<>()).add(e);
            }
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

    /** Writes spec with explicit changeRequired on the FeatureSpec (for requestedChanges tests). */
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
