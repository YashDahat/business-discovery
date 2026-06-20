package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.model.GeneratedFile;
import com.business.discovery.worker.repository.GeneratedFileRepository;
import com.business.discovery.worker.service.BuildToolService;
import com.business.discovery.worker.service.BuildToolService.BuildResult;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ErrorFixAgentTest {

    @Mock private LlmGeneratorService proLlm;
    @Mock private BuildToolService buildTool;
    @Mock private GeneratedFileRepository fileRepo;
    @Mock private WorkerContext ctx;

    @TempDir Path tempDir;

    private ErrorFixAgent agent;

    @BeforeEach
    void setUp() {
        agent = new ErrorFixAgent(proLlm, buildTool, fileRepo);
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
    }

    @Test
    void agentLoopSucceeds_compilePasses_returnsTrue() {
        when(proLlm.runFixAgentLoop(anyString(), anyString(), anyList(), any(), anyInt())).thenReturn(true);
        when(buildTool.runMvnCompile(any())).thenReturn(new BuildResult(0, ""));

        boolean result = agent.fix(FileType.BACKEND, ctx);

        assertThat(result).isTrue();
    }

    @Test
    void agentLoopExhausted_compileFails_returnsFalse() {
        when(proLlm.runFixAgentLoop(anyString(), anyString(), anyList(), any(), anyInt())).thenReturn(false);
        when(buildTool.runMvnCompile(any())).thenReturn(new BuildResult(1, "compilation error"));

        boolean result = agent.fix(FileType.BACKEND, ctx);

        assertThat(result).isFalse();
    }

    @Test
    void agentLoopSucceeds_butCompileStillFails_returnsFalse() {
        // Agent thinks it's done but final authoritative compile check disagrees
        when(proLlm.runFixAgentLoop(anyString(), anyString(), anyList(), any(), anyInt())).thenReturn(true);
        when(buildTool.runMvnCompile(any())).thenReturn(new BuildResult(1, "still broken"));

        boolean result = agent.fix(FileType.BACKEND, ctx);

        assertThat(result).isFalse();
    }

    @Test
    void writeFile_tool_writesContentToDisk_andUpdatesDb() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(ctx.getTaskId()).thenReturn(taskId);

        Path targetFile = tempDir.resolve("frontend/src/types/order.ts");
        Files.createDirectories(targetFile.getParent());

        GeneratedFile dbRow = GeneratedFile.builder()
                .taskId(taskId)
                .filePath("frontend/src/types/order.ts")
                .status(GeneratedFile.FileStatus.GENERATION_FAILED)
                .build();
        when(fileRepo.findByTaskIdAndFilePath(taskId, "frontend/src/types/order.ts"))
                .thenReturn(Optional.of(dbRow));

        // Capture the tool handler passed to runFixAgentLoop
        ArgumentCaptor<Function<dev.langchain4j.agent.tool.ToolExecutionRequest, String>> handlerCaptor =
                ArgumentCaptor.forClass(Function.class);

        when(proLlm.runFixAgentLoop(anyString(), anyString(), anyList(), handlerCaptor.capture(), anyInt()))
                .thenAnswer(inv -> {
                    // Simulate the LLM calling write_file
                    var req = mock(dev.langchain4j.agent.tool.ToolExecutionRequest.class);
                    when(req.name()).thenReturn("write_file");
                    when(req.arguments()).thenReturn(
                            "{\"path\":\"frontend/src/types/order.ts\",\"content\":\"export type OrderResponse = { id: string; };\"}");
                    handlerCaptor.getValue().apply(req);
                    return true;
                });
        when(buildTool.runTscCheck(any())).thenReturn(new BuildResult(0, ""));

        agent.fix(FileType.FRONTEND, ctx);

        assertThat(Files.readString(targetFile)).isEqualTo("export type OrderResponse = { id: string; };");
        verify(fileRepo).save(argThat(f -> f.getStatus() == GeneratedFile.FileStatus.VALIDATED));
    }

    @Test
    void fix_backend_uses_mvnCompile_for_final_check() {
        when(proLlm.runFixAgentLoop(anyString(), anyString(), anyList(), any(), anyInt())).thenReturn(true);
        when(buildTool.runMvnCompile(any())).thenReturn(new BuildResult(0, ""));

        agent.fix(FileType.BACKEND, ctx);

        verify(buildTool).runMvnCompile(tempDir.resolve("backend"));
        verify(buildTool, never()).runTscCheck(any());
    }

    @Test
    void fix_frontend_uses_tscCheck_for_final_check() {
        when(proLlm.runFixAgentLoop(anyString(), anyString(), anyList(), any(), anyInt())).thenReturn(true);
        when(buildTool.runTscCheck(any())).thenReturn(new BuildResult(0, ""));

        agent.fix(FileType.FRONTEND, ctx);

        verify(buildTool).runTscCheck(tempDir.resolve("frontend"));
        verify(buildTool, never()).runMvnCompile(any());
    }

    @Test
    void agent_receives_6_tool_specs() {
        ArgumentCaptor<List<ToolSpecification>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        when(proLlm.runFixAgentLoop(anyString(), anyString(), toolsCaptor.capture(), any(), anyInt())).thenReturn(true);
        when(buildTool.runMvnCompile(any())).thenReturn(new BuildResult(0, ""));

        agent.fix(FileType.BACKEND, ctx);

        List<ToolSpecification> tools = toolsCaptor.getValue();
        assertThat(tools).hasSize(6);
        assertThat(tools).extracting(ToolSpecification::name)
                .containsExactlyInAnyOrder(
                        "run_compiler", "read_file", "write_file",
                        "search_symbol", "read_architecture_spec", "list_files");
    }

    @Test
    void pathTraversal_blocked_by_writeFile_tool() {
        ArgumentCaptor<Function<dev.langchain4j.agent.tool.ToolExecutionRequest, String>> handlerCaptor =
                ArgumentCaptor.forClass(Function.class);

        when(proLlm.runFixAgentLoop(anyString(), anyString(), anyList(), handlerCaptor.capture(), anyInt()))
                .thenAnswer(inv -> {
                    var req = mock(dev.langchain4j.agent.tool.ToolExecutionRequest.class);
                    when(req.name()).thenReturn("write_file");
                    when(req.arguments()).thenReturn(
                            "{\"path\":\"../../etc/passwd\",\"content\":\"evil\"}");
                    String result = handlerCaptor.getValue().apply(req);
                    assertThat(result).startsWith("ERROR: path traversal not allowed");
                    return true;
                });
        when(buildTool.runMvnCompile(any())).thenReturn(new BuildResult(0, ""));

        agent.fix(FileType.BACKEND, ctx);
    }
}
