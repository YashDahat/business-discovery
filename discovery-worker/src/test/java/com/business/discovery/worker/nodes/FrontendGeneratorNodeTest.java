package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.model.GeneratedFile;
import com.business.discovery.worker.repository.GeneratedFileRepository;
import com.business.discovery.worker.service.llm.BriefContext;
import com.business.discovery.worker.service.llm.FileEntry;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FrontendGeneratorNodeTest {

    @Mock private LlmGeneratorService llm;
    @Mock private GeneratedFileRepository fileRepo;
    @Mock private WorkerContext ctx;

    @InjectMocks
    private FrontendGeneratorNode node;

    @TempDir
    Path tempDir;

    @Test
    void twoFrontendFiles_generatesRowsAndWritesToDisk() throws Exception {
        UUID taskId = UUID.randomUUID();
        BriefContext briefCtx = mockBriefContext();

        List<FileEntry> manifest = List.of(
                new FileEntry("backend/src/main/java/com/test/model/Menu.java", FileType.BACKEND, "Menu entity"),
                new FileEntry("frontend/src/App.tsx", FileType.FRONTEND, "Root component"),
                new FileEntry("frontend/src/pages/Home.tsx", FileType.FRONTEND, "Home page")
        );

        when(ctx.getFileManifest()).thenReturn(manifest);
        when(ctx.getBriefCtx()).thenReturn(briefCtx);
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(taskId);
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(llm.generateFileContent(anyString(), anyString(), eq(briefCtx), anyList(), any(), any()))
                .thenReturn("export default function Component() { return null; }");

        node.execute(ctx);

        ArgumentCaptor<GeneratedFile> captor = ArgumentCaptor.forClass(GeneratedFile.class);
        verify(fileRepo, times(2)).save(captor.capture());

        List<GeneratedFile> saved = captor.getAllValues();
        assertThat(saved).allMatch(f -> f.getFileType() == GeneratedFile.FileType.FRONTEND);
        assertThat(saved).allMatch(f -> f.getStatus() == GeneratedFile.FileStatus.GENERATED);
        assertThat(saved).allMatch(f -> taskId.equals(f.getTaskId()));

        assertThat(tempDir.resolve("frontend/src/App.tsx")).exists();
        assertThat(tempDir.resolve("frontend/src/pages/Home.tsx")).exists();
        assertThat(tempDir.resolve("backend/src/main/java/com/test/model/Menu.java")).doesNotExist();
    }

    @Test
    void noFrontendFiles_savesNothingAndCompletes() {
        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry("backend/src/main/java/com/test/model/Order.java", FileType.BACKEND, "Order entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(fileRepo);
        verifyNoInteractions(llm);
    }

    @Test
    void retryMode_existingFile_isSkipped() throws Exception {
        Path existingFile = tempDir.resolve("frontend/src/App.tsx");
        Files.createDirectories(existingFile.getParent());
        Files.writeString(existingFile, "// existing");

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry("frontend/src/App.tsx", FileType.FRONTEND, "Root component")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext()); // requestedChanges = null
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        node.execute(ctx);

        verifyNoInteractions(llm);
        verifyNoInteractions(fileRepo);
        assertThat(Files.readString(existingFile)).isEqualTo("// existing");
    }

    @Test
    void requestedChangesMode_existingFile_isRegenerated() throws Exception {
        Path existingFile = tempDir.resolve("frontend/src/App.tsx");
        Files.createDirectories(existingFile.getParent());
        Files.writeString(existingFile, "// old");

        BriefContext briefCtxWithChanges = new BriefContext(
                "Test Business", "Restaurant", "Pune", "INFORMATIONAL",
                List.of(), List.of(), Map.of(), List.of(),
                "modern", "blue", "professional", "", "", "",
                "Change color scheme to red", null);

        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry("frontend/src/App.tsx", FileType.FRONTEND, "Root component")
        ));
        when(ctx.getBriefCtx()).thenReturn(briefCtxWithChanges);
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(2);
        when(llm.generateFileContent(anyString(), anyString(), any(), anyList(), any(), any()))
                .thenReturn("// updated");

        node.execute(ctx);

        verify(llm).generateFileContent(anyString(), anyString(), any(), anyList(), any(), any());
        assertThat(Files.readString(existingFile)).isEqualTo("// updated");
    }

    @Test
    void llmThrowsInfra_propagates() {
        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry("frontend/src/App.tsx", FileType.FRONTEND, "Root component")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(llm.generateFileContent(anyString(), anyString(), any(), anyList(), any(), any()))
                .thenThrow(new WorkerException(FailureType.INFRA, "LLM timeout"));

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> assertThat(((WorkerException) ex).getFailureType())
                        .isEqualTo(FailureType.INFRA));

        verifyNoInteractions(fileRepo);
    }

    @Test
    void fileContent_writtenCorrectly() throws Exception {
        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry("frontend/src/components/Header.tsx", FileType.FRONTEND, "Header component")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(llm.generateFileContent(anyString(), anyString(), any(), anyList(), any(), any()))
                .thenReturn("export const Header = () => <header>Hello</header>;");

        node.execute(ctx);

        String written = Files.readString(tempDir.resolve("frontend/src/components/Header.tsx"));
        assertThat(written).isEqualTo("export const Header = () => <header>Hello</header>;");
    }

    private BriefContext mockBriefContext() {
        return new BriefContext("Test Business", "Restaurant", "Pune",
                "INFORMATIONAL", List.of(), List.of(), Map.of(), List.of(),
                "modern", "blue", "professional", "", "", "", null, null);
    }
}
