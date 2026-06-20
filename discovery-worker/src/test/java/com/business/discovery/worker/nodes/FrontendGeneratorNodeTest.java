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
class FrontendGeneratorNodeTest {

    @Mock private LlmGeneratorService flashLlm;
    @Mock private LlmGeneratorService proLlm;
    @Mock private LlmGeneratorService fixLlm;
    @Mock private BuildToolService buildToolService;
    @Mock private GeneratedFileRepository fileRepo;
    @Mock private WorkerContext ctx;

    private FrontendGeneratorNode node;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tempDir.resolve("frontend"));
        node = new FrontendGeneratorNode(flashLlm, proLlm, fixLlm, buildToolService, fileRepo);
        lenient().when(buildToolService.runNpmInstall(any(Path.class)))
                .thenReturn(new BuildToolService.BuildResult(0, ""));
        lenient().when(buildToolService.runTscCheck(any(Path.class)))
                .thenReturn(new BuildToolService.BuildResult(0, ""));
        lenient().when(buildToolService.runEslintFix(any(Path.class)))
                .thenReturn(new BuildToolService.BuildResult(0, ""));
        lenient().when(buildToolService.runNpmInstallPackage(any(Path.class), anyString()))
                .thenReturn(new BuildToolService.BuildResult(0, ""));
        lenient().when(proLlm.checkSpecCompliance(any(), any(), any()))
                .thenReturn(ComplianceResult.ok());
    }

    // ── Full 3-stage pipeline ─────────────────────────────────────────────

    @Test
    void twoFrontendFiles_allThreeStages_finalStatusSpecCompliant() throws Exception {
        UUID taskId = UUID.randomUUID();

        String appPath  = "frontend/src/App.tsx";
        String homePath = "frontend/src/pages/Home.tsx";

        writeSpec(tempDir,
                specEntry(appPath,  "FRONTEND", "PLANNED", "// instruction App"),
                specEntry(homePath, "FRONTEND", "PLANNED", "// instruction Home"));

        List<FileEntry> manifest = List.of(
                new FileEntry("backend/src/main/java/com/test/model/Menu.java", FileType.BACKEND, "Menu entity"),
                new FileEntry(appPath,  FileType.FRONTEND, "Root component"),
                new FileEntry(homePath, FileType.FRONTEND, "Home page")
        );

        when(ctx.getFileManifest()).thenReturn(manifest);
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(taskId);
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(flashLlm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), any()))
                .thenReturn("export default function Component() { return null; }");

        node.execute(ctx);

        assertThat(tempDir.resolve(appPath)).exists();
        assertThat(tempDir.resolve(homePath)).exists();
        assertThat(tempDir.resolve("backend/src/main/java/com/test/model/Menu.java")).doesNotExist();

        ArchitectureSpec updated = ArchitectureJsonUtil.read(tempDir);
        assertThat(updated.getFiles()).allMatch(f -> "SPEC_COMPLIANT".equals(f.getStatus()));
    }

    @Test
    void noFrontendFiles_savesNothingAndCompletes() {
        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry("backend/src/main/java/com/test/model/Order.java", FileType.BACKEND, "Order entity")
        ));
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(fileRepo, flashLlm, proLlm, fixLlm, buildToolService);
    }

    @Test
    void fileWithNoFeatureInstruction_isSkipped() throws Exception {
        String filePath = "frontend/src/App.tsx";
        // null instruction → featureName is null → feature lookup returns null → skip
        writeSpec(tempDir, specEntry(filePath, "FRONTEND", "PLANNED", null));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.FRONTEND, "Root component")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(flashLlm, proLlm, fixLlm, fileRepo);
    }

    @Test
    void specCompliantFile_isSkipped() throws Exception {
        String filePath = "frontend/src/App.tsx";
        Path existingFile = tempDir.resolve(filePath);
        Files.createDirectories(existingFile.getParent());
        Files.writeString(existingFile, "// existing");

        writeSpec(tempDir, specEntry(filePath, "FRONTEND", "SPEC_COMPLIANT", "// instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.FRONTEND, "Root component")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(flashLlm, proLlm, fixLlm, fileRepo);
        assertThat(Files.readString(existingFile)).isEqualTo("// existing");
    }

    @Test
    void generatedFile_skipsStage3_runsTscAndSpecCheck() throws Exception {
        String filePath = "frontend/src/App.tsx";
        Path existingFile = tempDir.resolve(filePath);
        Files.createDirectories(existingFile.getParent());
        Files.writeString(existingFile, "// previously generated");

        writeSpec(tempDir, specEntry(filePath, "FRONTEND", "GENERATED", "// instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.FRONTEND, "Root component")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(2);

        node.execute(ctx);

        assertThat(Files.readString(existingFile)).isEqualTo("// previously generated");
        verifyNoInteractions(flashLlm);
        verify(buildToolService).runTscCheck(any(Path.class));
        verify(proLlm).checkSpecCompliance(eq(filePath), any(), contains("// instruction"));
    }

    @Test
    void validatedFile_skipsStage3AndTsc_runsSpecCheckOnly() throws Exception {
        String filePath = "frontend/src/App.tsx";
        Path existingFile = tempDir.resolve(filePath);
        Files.createDirectories(existingFile.getParent());
        Files.writeString(existingFile, "// validated");

        writeSpec(tempDir, specEntry(filePath, "FRONTEND", "VALIDATED", "// instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.FRONTEND, "Root component")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(2);

        node.execute(ctx);

        verifyNoInteractions(flashLlm, fixLlm);
        verify(proLlm).checkSpecCompliance(eq(filePath), eq("// validated"), contains("// instruction"));

        ArchitectureSpec updated = ArchitectureJsonUtil.read(tempDir);
        assertThat(updated.getFiles().get(0).getStatus()).isEqualTo("SPEC_COMPLIANT");
    }

    // ── Stage 1: tsc + fix ────────────────────────────────────────────────

    @Test
    void tscFails_fixAttemptSucceeds_becomesSpecCompliant() throws Exception {
        String filePath = "frontend/src/App.tsx";
        Path existingFile = tempDir.resolve(filePath);
        Files.createDirectories(existingFile.getParent());
        Files.writeString(existingFile, "// broken tsx");

        writeSpec(tempDir, specEntry(filePath, "FRONTEND", "PLANNED", "// instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.FRONTEND, "Root component")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(flashLlm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), any())).thenReturn("// broken tsx");
        when(buildToolService.runTscCheck(any()))
                .thenReturn(new BuildToolService.BuildResult(1, "TS2304: Cannot find name"))
                .thenReturn(new BuildToolService.BuildResult(0, ""));
        when(fixLlm.fixFileContent(any(), any(), any(), anyMap())).thenReturn("// fixed tsx");

        node.execute(ctx);

        verify(fixLlm).fixFileContent(eq(filePath), any(), eq("TS2304: Cannot find name"), anyMap());
        assertThat(Files.readString(existingFile)).isEqualTo("// fixed tsx");

        ArchitectureSpec updated = ArchitectureJsonUtil.read(tempDir);
        assertThat(updated.getFiles().get(0).getStatus()).isEqualTo("SPEC_COMPLIANT");
    }

    @Test
    void tscFails_maxRetriesExhausted_becomesGenerationFailed() throws Exception {
        String filePath = "frontend/src/App.tsx";
        Path existingFile = tempDir.resolve(filePath);
        Files.createDirectories(existingFile.getParent());
        Files.writeString(existingFile, "// broken");

        writeSpec(tempDir, specEntry(filePath, "FRONTEND", "PLANNED", "// instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.FRONTEND, "Root component")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(flashLlm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), any())).thenReturn("// broken");
        when(buildToolService.runTscCheck(any()))
                .thenReturn(new BuildToolService.BuildResult(1, "type error"));
        when(fixLlm.fixFileContent(any(), any(), any(), anyMap())).thenReturn("// still broken");

        node.execute(ctx);

        verify(buildToolService, times(4)).runTscCheck(any());
        verify(fixLlm, times(3)).fixFileContent(any(), any(), any(), anyMap());
        verifyNoInteractions(proLlm);

        ArchitectureSpec updated = ArchitectureJsonUtil.read(tempDir);
        assertThat(updated.getFiles().get(0).getStatus()).isEqualTo("GENERATION_FAILED");
    }

    // ── requestedChanges mode ─────────────────────────────────────────────

    @Test
    void requestedChangesMode_changeRequiredTrue_passesExistingContentToFlash() throws Exception {
        String filePath = "frontend/src/App.tsx";
        Path existingFile = tempDir.resolve(filePath);
        Files.createDirectories(existingFile.getParent());
        Files.writeString(existingFile, "// old");

        FileSpec spec = specEntry(filePath, "FRONTEND", "VALIDATED", "// update instruction");
        writeSpecWithFeature(tempDir, spec, "// update instruction", true);

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.FRONTEND, "Root component")
        ));
        when(ctx.getBriefCtx()).thenReturn(briefContextWithChanges("Change color scheme to red"));
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(2);
        when(flashLlm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), eq("// old")))
                .thenReturn("// updated");

        node.execute(ctx);

        verify(flashLlm).generateFileContent(
                anyString(), eq("// update instruction"), anyString(), anyMap(), eq("// old"));
        assertThat(Files.readString(existingFile)).isEqualTo("// updated");
    }

    @Test
    void requestedChangesMode_changeRequiredFalse_fileIsSkipped() throws Exception {
        String filePath = "frontend/src/App.tsx";

        FileSpec spec = specEntry(filePath, "FRONTEND", "VALIDATED", "// instruction");
        writeSpecWithFeature(tempDir, spec, "// instruction", false);

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.FRONTEND, "Root component")
        ));
        when(ctx.getBriefCtx()).thenReturn(briefContextWithChanges("Change color scheme"));
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(flashLlm, proLlm, fixLlm, fileRepo);
    }

    @Test
    void llmThrowsInfra_propagates() throws Exception {
        String filePath = "frontend/src/App.tsx";
        writeSpec(tempDir, specEntry(filePath, "FRONTEND", "PLANNED", "// instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.FRONTEND, "Root component")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(flashLlm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), any()))
                .thenThrow(new WorkerException(FailureType.INFRA, "LLM timeout"));

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> assertThat(((WorkerException) ex).getFailureType())
                        .isEqualTo(FailureType.INFRA));

        verifyNoInteractions(fileRepo);
    }

    @Test
    void fileContent_writtenCorrectly() throws Exception {
        String filePath = "frontend/src/components/Header.tsx";
        writeSpec(tempDir, specEntry(filePath, "FRONTEND", "PLANNED", "// instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.FRONTEND, "Header component")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(flashLlm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), any()))
                .thenReturn("export const Header = () => <header>Hello</header>;");

        node.execute(ctx);

        String written = Files.readString(tempDir.resolve(filePath));
        assertThat(written).isEqualTo("export const Header = () => <header>Hello</header>;");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private BriefContext mockBriefContext() {
        return new BriefContext("Test Business", "Restaurant", "Pune",
                "INFORMATIONAL", List.of(), List.of(), List.of(), Map.of(), List.of(),
                "modern", "blue", "professional", "", "", "", null, null);
    }

    private BriefContext briefContextWithChanges(String changes) {
        return new BriefContext("Test Business", "Restaurant", "Pune",
                "INFORMATIONAL", List.of(), List.of(), List.of(), Map.of(), List.of(),
                "modern", "blue", "professional", "", "", "", changes, null);
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
                        .featureType("FRONTEND")
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
                .featureType("FRONTEND")
                .featureInstruction(featureInstruction)
                .changeRequired(changeRequired)
                .filePaths(List.of(spec.getFilePath()))
                .build());
        ArchitectureJsonUtil.write(workspace,
                ArchitectureSpec.builder().files(List.of(spec)).features(features).build());
    }
}
