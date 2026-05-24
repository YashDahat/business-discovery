package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.model.GeneratedFile;
import com.business.discovery.worker.repository.GeneratedFileRepository;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import dev.langchain4j.web.search.WebSearchEngine;
import dev.langchain4j.web.search.WebSearchOrganicResult;
import dev.langchain4j.web.search.WebSearchRequest;
import dev.langchain4j.web.search.WebSearchResults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ErrorFixNodeTest {

    @Mock private LlmGeneratorService llm;
    @Mock private GeneratedFileRepository fileRepo;
    @Mock private WebSearchEngine webSearch;
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

        // Build mock BEFORE any when() chain to avoid UnfinishedStubbing
        WebSearchResults sr = buildSearchResults("Use @Autowired to inject the repository");

        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(taskId);
        when(webSearch.search(any(WebSearchRequest.class))).thenReturn(sr);
        when(llm.fixFileContent(anyString(), anyString(), anyString(), anyString()))
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

        WebSearchResults sr = buildSearchResults("some answer");

        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(webSearch.search(any(WebSearchRequest.class))).thenReturn(sr);
        when(llm.fixFileContent(anyString(), anyString(), anyString(), anyString())).thenReturn("");

        boolean result = node.fix(errorOutput, FileType.BACKEND, ctx);

        assertThat(result).isFalse();
        assertThat(Files.readString(backendFile)).isEqualTo("broken content");
    }

    @Test
    void cannotParseFilePath_returnsFalse() {
        // No file path in error → returns false immediately, never calls ctx.getWorkspaceDir()
        boolean result = node.fix("some unrecognised error output", FileType.BACKEND, ctx);

        assertThat(result).isFalse();
        verifyNoInteractions(llm, webSearch, ctx);
    }

    @Test
    void fileNotFound_returnsFalse() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        // Error references a file that doesn't exist on disk
        String errorOutput = "[ERROR] " + tempDir + "/backend/src/main/java/com/test/Missing.java:[1,1] error: class expected";

        boolean result = node.fix(errorOutput, FileType.BACKEND, ctx);

        assertThat(result).isFalse();
        verifyNoInteractions(llm, webSearch);
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

        WebSearchResults sr = buildSearchResults("fix hint");

        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getTaskId()).thenReturn(taskId);
        when(webSearch.search(any(WebSearchRequest.class))).thenReturn(sr);
        when(llm.fixFileContent(anyString(), anyString(), anyString(), anyString())).thenReturn("fixed content");
        when(fileRepo.findByTaskIdAndFilePath(taskId, "backend/src/main/java/com/test/controller/OrderController.java"))
                .thenReturn(Optional.of(dbRow));

        node.fix(errorOutput, FileType.BACKEND, ctx);

        verify(fileRepo).save(argThat(f -> f.getStatus() == GeneratedFile.FileStatus.FAILED));
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    private WebSearchResults buildSearchResults(String snippet) {
        WebSearchOrganicResult organic = mock(WebSearchOrganicResult.class);
        when(organic.url()).thenReturn(URI.create("https://stackoverflow.com/q/1"));
        when(organic.snippet()).thenReturn(snippet);

        WebSearchResults results = mock(WebSearchResults.class);
        when(results.results()).thenReturn(List.of(organic));
        return results;
    }
}
