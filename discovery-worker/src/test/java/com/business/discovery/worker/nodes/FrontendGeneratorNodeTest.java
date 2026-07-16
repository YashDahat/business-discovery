package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.repository.GeneratedFileRepository;
import com.business.discovery.worker.service.BuildToolService;
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
    @Mock private BuildToolService buildToolService;
    @Mock private GeneratedFileRepository fileRepo;
    @Mock private GitService gitService;
    @Mock private WorkerContext ctx;

    private FrontendGeneratorNode node;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tempDir.resolve("frontend"));
        node = new FrontendGeneratorNode(flashLlm, buildToolService, fileRepo, gitService);
        lenient().doNothing().when(gitService).commitAndPushCheckpoint(any(), any(), any());
        lenient().when(fileRepo.findByTaskIdAndFilePath(any(), any())).thenReturn(java.util.Optional.empty());
        lenient().when(buildToolService.runEslintFix(any())).thenReturn(new BuildToolService.BuildResult(0, ""));
        lenient().when(buildToolService.runNpmInstall(any())).thenReturn(new BuildToolService.BuildResult(0, ""));
    }

    // ── Core generation ───────────────────────────────────────────────────

    @Test
    void twoFrontendFiles_generated_writtenToDisk() throws Exception {
        String appPath  = "frontend/src/App.tsx";
        String homePath = "frontend/src/pages/Home.tsx";

        writeSpec(tempDir,
                specEntry(appPath,  "FRONTEND", "PLANNED", "instruction App"),
                specEntry(homePath, "FRONTEND", "PLANNED", "instruction Home"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry("backend/src/main/java/com/test/model/Menu.java", FileType.BACKEND, "Menu entity"),
                new FileEntry(appPath,  FileType.FRONTEND, "Root component"),
                new FileEntry(homePath, FileType.FRONTEND, "Home page")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(ctx.getGithubBranch()).thenReturn("feature/test");
        when(flashLlm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), any(), any()))
                .thenReturn("export default function Component() { return null; }");

        node.execute(ctx);

        assertThat(tempDir.resolve(appPath)).exists();
        assertThat(tempDir.resolve(homePath)).exists();
        // backend file must not be touched
        assertThat(tempDir.resolve("backend/src/main/java/com/test/model/Menu.java")).doesNotExist();
    }

    @Test
    void noFrontendFiles_doesNothing() {
        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry("backend/src/main/java/com/test/model/Order.java", FileType.BACKEND, "Order entity")
        ));
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(flashLlm, fileRepo, gitService);
    }

    @Test
    void fileWithNoFeatureInstruction_isSkipped() throws Exception {
        String filePath = "frontend/src/App.tsx";
        writeSpec(tempDir, specEntry(filePath, "FRONTEND", "PLANNED", null));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.FRONTEND, "Root component")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(flashLlm, fileRepo);
    }

    @Test
    void llmThrowsInfraException_propagates() throws Exception {
        String filePath = "frontend/src/App.tsx";
        writeSpec(tempDir, specEntry(filePath, "FRONTEND", "PLANNED", "instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.FRONTEND, "Root component")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getGithubBranch()).thenReturn("feature/test");
        when(flashLlm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), any(), any()))
                .thenThrow(new WorkerException(FailureType.INFRA, "LLM timeout"));

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> assertThat(((WorkerException) ex).getFailureType()).isEqualTo(FailureType.INFRA));
    }

    // ── Skip behaviour ────────────────────────────────────────────────────

    @Test
    void specCompliantFile_isSkipped() throws Exception {
        String filePath = "frontend/src/App.tsx";
        Path existing = tempDir.resolve(filePath);
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "// existing");

        writeSpec(tempDir, specEntry(filePath, "FRONTEND", "SPEC_COMPLIANT", "instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.FRONTEND, "Root component")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(flashLlm, fileRepo);
        assertThat(Files.readString(existing)).isEqualTo("// existing");
    }

    @Test
    void validatedFile_isSkipped() throws Exception {
        String filePath = "frontend/src/App.tsx";
        Path existing = tempDir.resolve(filePath);
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "// validated");

        writeSpec(tempDir, specEntry(filePath, "FRONTEND", "VALIDATED", "instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.FRONTEND, "Root component")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(flashLlm, fileRepo);
        assertThat(Files.readString(existing)).isEqualTo("// validated");
    }

    // ── Layer ordering ────────────────────────────────────────────────────

    @Test
    void checkpointCommittedAfterEachLayer() throws Exception {
        String typePath  = "frontend/src/types/Menu.ts";
        String pagePath  = "frontend/src/pages/Menu.tsx";

        writeSpec(tempDir,
                specEntry(typePath, "FRONTEND", "PLANNED", "instruction"),
                specEntry(pagePath, "FRONTEND", "PLANNED", "instruction"));

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(typePath, FileType.FRONTEND, "Menu type"),
                new FileEntry(pagePath, FileType.FRONTEND, "Menu page")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(ctx.getGithubBranch()).thenReturn("feature/test");
        when(flashLlm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), any(), any()))
                .thenReturn("export type Placeholder = {};");

        node.execute(ctx);

        // One checkpoint per distinct layer (type=10, page=70 → 2 calls)
        verify(gitService, times(2)).commitAndPushCheckpoint(eq(tempDir), anyString(), eq("feature/test"));
    }

    // ── requestedChanges mode ─────────────────────────────────────────────

    @Test
    void requestedChangesMode_changeRequiredTrue_regeneratesFile() throws Exception {
        String filePath = "frontend/src/App.tsx";
        Path existing = tempDir.resolve(filePath);
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "// old");

        FileSpec spec = specEntry(filePath, "FRONTEND", "VALIDATED", "update instruction");
        writeSpecWithFeature(tempDir, spec, "update instruction", true);

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.FRONTEND, "Root component")
        ));
        when(ctx.getBriefCtx()).thenReturn(briefContextWithChanges("Change color scheme to red"));
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(2);
        when(ctx.getGithubBranch()).thenReturn("feature/test");
        when(flashLlm.generateFileContent(anyString(), anyString(), anyString(), anyMap(), eq("// old"), any()))
                .thenReturn("// updated");

        node.execute(ctx);

        verify(flashLlm).generateFileContent(anyString(), eq("update instruction"), anyString(), anyMap(), eq("// old"), any());
        assertThat(Files.readString(existing)).isEqualTo("// updated");
    }

    @Test
    void requestedChangesMode_changeRequiredFalse_fileIsSkipped() throws Exception {
        String filePath = "frontend/src/App.tsx";

        FileSpec spec = specEntry(filePath, "FRONTEND", "VALIDATED", "instruction");
        writeSpecWithFeature(tempDir, spec, "instruction", false);

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry(filePath, FileType.FRONTEND, "Root component")
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
                        .featureType("FRONTEND")
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
                .featureType("FRONTEND")
                .featureInstruction(featureInstruction)
                .changeRequired(changeRequired)
                .filePaths(List.of(spec.getFilePath()))
                .build());
        ArchitectureJsonUtil.write(workspace,
                ArchitectureSpec.builder().files(List.of(spec)).features(features).build());
    }
}
