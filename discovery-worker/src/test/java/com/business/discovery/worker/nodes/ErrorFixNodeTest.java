package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.model.GeneratedFile;
import com.business.discovery.worker.repository.GeneratedFileRepository;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ErrorFixNodeTest {

    @Mock private LlmGeneratorService llm;
    @Mock private GeneratedFileRepository fileRepo;
    @Mock private WorkerContext ctx;

    @InjectMocks
    private ErrorFixNode node;

    @TempDir
    Path tempDir;

    @Test
    void llmReturnsFixedContent_fileOverwrittenAndReturnsTrue() throws Exception {
        UUID taskId = UUID.randomUUID();
        Path backendFile = tempDir.resolve("backend/src/main/java/com/test/service/MenuService.java");
        Files.createDirectories(backendFile.getParent());
        Files.writeString(backendFile, "public class MenuService { /* broken */ }");

        String errorOutput = "[ERROR] " + backendFile + ":[15,32] error: cannot find symbol\n  symbol: class MenuRepository";

        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(taskId);
        when(llm.fixFileContent(anyString(), anyString(), anyString(), any()))
                .thenReturn("public class MenuService { @Autowired MenuRepository repo; }");
        when(fileRepo.findByTaskIdAndFilePath(eq(taskId), anyString())).thenReturn(Optional.empty());

        boolean result = node.fix(errorOutput, FileType.BACKEND, ctx);

        assertThat(result).isTrue();
        assertThat(Files.readString(backendFile))
                .isEqualTo("public class MenuService { @Autowired MenuRepository repo; }");
    }

    @Test
    void llmReturnsEmptyFix_returnsFalse() throws Exception {
        Path backendFile = tempDir.resolve("backend/src/main/java/com/test/model/Product.java");
        Files.createDirectories(backendFile.getParent());
        Files.writeString(backendFile, "broken content");

        String errorOutput = "[ERROR] " + backendFile + ":[5,1] error: class expected";

        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(llm.fixFileContent(anyString(), anyString(), anyString(), any())).thenReturn("");

        boolean result = node.fix(errorOutput, FileType.BACKEND, ctx);

        assertThat(result).isFalse();
        assertThat(Files.readString(backendFile)).isEqualTo("broken content");
    }

    @Test
    void cannotParseFilePath_returnsFalse() {
        boolean result = node.fix("some unrecognised error output", FileType.BACKEND, ctx);

        assertThat(result).isFalse();
        verifyNoInteractions(llm, ctx);
    }

    @Test
    void fileNotFound_returnsFalse() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        String errorOutput = "[ERROR] " + tempDir + "/backend/src/main/java/com/test/Missing.java:[1,1] error: class expected";

        boolean result = node.fix(errorOutput, FileType.BACKEND, ctx);

        assertThat(result).isFalse();
        verifyNoInteractions(llm);
    }

    @Test
    void generatedFileRow_markedFailedAfterFix() throws Exception {
        UUID taskId = UUID.randomUUID();
        Path file = tempDir.resolve("backend/src/main/java/com/test/controller/OrderController.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "broken");

        String errorOutput = "[ERROR] " + file + ":[3,5] error: cannot find symbol";

        GeneratedFile dbRow = GeneratedFile.builder()
                .taskId(taskId)
                .filePath("backend/src/main/java/com/test/controller/OrderController.java")
                .fileType(GeneratedFile.FileType.BACKEND)
                .status(GeneratedFile.FileStatus.GENERATED)
                .build();

        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(taskId);
        when(llm.fixFileContent(anyString(), anyString(), anyString(), any())).thenReturn("fixed content");
        when(fileRepo.findByTaskIdAndFilePath(taskId, "backend/src/main/java/com/test/controller/OrderController.java"))
                .thenReturn(Optional.of(dbRow));

        node.fix(errorOutput, FileType.BACKEND, ctx);

        verify(fileRepo).save(argThat(f -> f.getStatus() == GeneratedFile.FileStatus.FAILED));
    }

    @Test
    void longErrorOutput_isTrimmedToFirst30Lines() throws Exception {
        Path file = tempDir.resolve("backend/src/main/java/com/test/model/Order.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "broken");

        StringBuilder sb = new StringBuilder();
        sb.append("[ERROR] ").append(file).append(":[1,1] error: root cause\n");
        for (int i = 2; i <= 60; i++) sb.append("[ERROR] cascading error line ").append(i).append("\n");
        String longError = sb.toString();

        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(UUID.randomUUID());
        when(llm.fixFileContent(anyString(), anyString(), anyString(), any())).thenReturn("fixed");
        when(fileRepo.findByTaskIdAndFilePath(any(), anyString())).thenReturn(Optional.empty());

        node.fix(longError, FileType.BACKEND, ctx);

        verify(llm).fixFileContent(anyString(), anyString(),
                argThat(err -> err.lines().count() <= 30), any());
    }
}
