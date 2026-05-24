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

    @Test
    void threeBackendFiles_generatesThreeRowsAndWrites() {
        UUID taskId = UUID.randomUUID();
        BriefContext briefCtx = mockBriefContext();

        List<FileEntry> manifest = List.of(
                new FileEntry("backend/src/main/java/com/test/model/Product.java", FileType.BACKEND, "Product entity"),
                new FileEntry("backend/src/main/java/com/test/repository/ProductRepository.java", FileType.BACKEND, "Product repo"),
                new FileEntry("frontend/src/App.tsx", FileType.FRONTEND, "Root component")
        );

        when(ctx.getFileManifest()).thenReturn(manifest);
        when(ctx.getBriefCtx()).thenReturn(briefCtx);
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(taskId);
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(llm.generateFileContent(anyString(), anyString(), eq(briefCtx), anyList()))
                .thenReturn("// generated content");

        node.execute(ctx);

        ArgumentCaptor<GeneratedFile> captor = ArgumentCaptor.forClass(GeneratedFile.class);
        verify(fileRepo, times(2)).save(captor.capture());

        List<GeneratedFile> saved = captor.getAllValues();
        assertThat(saved).allMatch(f -> f.getFileType() == GeneratedFile.FileType.BACKEND);
        assertThat(saved).allMatch(f -> f.getStatus() == GeneratedFile.FileStatus.GENERATED);
        assertThat(saved).allMatch(f -> f.getAttemptNumber() == 1);
        assertThat(saved).allMatch(f -> f.getTaskId().equals(taskId));

        // frontend file was skipped — only 2 backend files written to disk
        assertThat(tempDir.resolve("backend/src/main/java/com/test/model/Product.java")).exists();
        assertThat(tempDir.resolve("backend/src/main/java/com/test/repository/ProductRepository.java")).exists();
        assertThat(tempDir.resolve("frontend/src/App.tsx")).doesNotExist();
    }

    @Test
    void llmThrowsInfraException_propagates() {
        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry("backend/src/main/java/com/test/model/Order.java", FileType.BACKEND, "Order entity")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(llm.generateFileContent(anyString(), anyString(), any(), anyList()))
                .thenThrow(new WorkerException(FailureType.INFRA, "LLM timeout"));

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> assertThat(((WorkerException) ex).getFailureType())
                        .isEqualTo(FailureType.INFRA));

        verifyNoInteractions(fileRepo);
    }

    @Test
    void noBackendFiles_savesNothingAndCompletes() {
        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry("frontend/src/App.tsx", FileType.FRONTEND, "Root component")
        ));

        node.execute(ctx);

        verifyNoInteractions(fileRepo);
        verifyNoInteractions(llm);
    }

    @Test
    void fileContent_writtenToDisk() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(ctx.getFileManifest()).thenReturn(List.of(
                new FileEntry("backend/src/main/java/com/test/service/MenuService.java", FileType.BACKEND, "Menu service")
        ));
        when(ctx.getBriefCtx()).thenReturn(mockBriefContext());
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(taskId);
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(llm.generateFileContent(anyString(), anyString(), any(), anyList()))
                .thenReturn("public class MenuService {}");

        node.execute(ctx);

        String written = Files.readString(
                tempDir.resolve("backend/src/main/java/com/test/service/MenuService.java"));
        assertThat(written).isEqualTo("public class MenuService {}");
    }

    private BriefContext mockBriefContext() {
        return new BriefContext("Test Business", "Restaurant", "Pune",
                "INFORMATIONAL", List.of(), List.of(), java.util.Map.of(), List.of(),
                "modern", "blue", "professional", "", "", "");
    }
}
