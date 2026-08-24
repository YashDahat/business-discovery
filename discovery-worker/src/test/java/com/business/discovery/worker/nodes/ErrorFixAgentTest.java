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

    private static final BuildResult PASS = new BuildResult(0, "");
    private static final BuildResult FAIL = new BuildResult(1, "compilation error in Foo.java");
    // tsc-format failure for the frontend seed (runTscCheck) — has a real TS error line so the
    // seed does not fall back to npm-build.
    private static final BuildResult TSC_FAIL = new BuildResult(1,
            "src/pages/A.tsx(1,2): error TS2339: Property 'x' does not exist.");

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

    private void stubLoop(boolean loopReturn) {
        when(proLlm.runFixAgentLoop(anyString(), anyString(), anyList(), any(), any(), anyInt()))
                .thenReturn(loopReturn);
    }

    // ── Loop orchestration ────────────────────────────────────────────────

    @Test
    void preCheckAlreadyPasses_skipsLoopEntirely() {
        when(buildTool.runMvnCompile(any())).thenReturn(PASS);

        boolean result = agent.fix(FileType.BACKEND, ctx);

        assertThat(result).isTrue();
        verify(proLlm, never()).runFixAgentLoop(anyString(), anyString(), anyList(), any(), any(), anyInt());
    }

    @Test
    void loopRuns_finalCompilePasses_returnsTrue() {
        when(buildTool.runMvnCompile(any())).thenReturn(FAIL, PASS); // pre-check fails, final passes
        stubLoop(true);

        boolean result = agent.fix(FileType.BACKEND, ctx);

        assertThat(result).isTrue();
    }

    @Test
    void loopExhausted_finalCompileFails_returnsFalse() {
        when(buildTool.runMvnCompile(any())).thenReturn(FAIL, FAIL);
        stubLoop(false);

        boolean result = agent.fix(FileType.BACKEND, ctx);

        assertThat(result).isFalse();
    }

    @Test
    void loopClaimsSuccess_butFinalCompileDisagrees_returnsFalse() {
        when(buildTool.runMvnCompile(any())).thenReturn(FAIL, FAIL);
        stubLoop(true);

        boolean result = agent.fix(FileType.BACKEND, ctx);

        assertThat(result).isFalse();
    }

    @Test
    void triggerIsSeededWithPreCheckErrors() {
        when(buildTool.runMvnCompile(any())).thenReturn(FAIL, PASS);
        ArgumentCaptor<String> triggerCaptor = ArgumentCaptor.forClass(String.class);
        when(proLlm.runFixAgentLoop(anyString(), triggerCaptor.capture(), anyList(), any(), any(), anyInt()))
                .thenReturn(true);

        agent.fix(FileType.BACKEND, ctx);

        assertThat(triggerCaptor.getValue()).contains("compilation error in Foo.java");
    }

    @Test
    void fix_frontend_seedsWithTsc_andFinalChecksWithNpmBuild() {
        // The seed uses tsc (runTscCheck) so the agent gets the COMPLETE TypeScript error list; the
        // final authoritative check is npm run build (tsc + vite bundling) — bare tsc once passed while
        // vite build failed, trapping retries.
        when(buildTool.runTscCheck(any())).thenReturn(TSC_FAIL);
        when(buildTool.runNpmBuild(any())).thenReturn(PASS);
        stubLoop(true);

        boolean result = agent.fix(FileType.FRONTEND, ctx);

        assertThat(result).isTrue();
        verify(buildTool).runTscCheck(tempDir.resolve("frontend"));   // seed = tsc
        verify(buildTool).runNpmBuild(tempDir.resolve("frontend"));   // final = npm run build
        verify(buildTool, never()).runMvnCompile(any());
    }

    // ── F3: deterministic input assembly into the trigger ──────────────────

    @Test
    void trigger_preReadsFilesNamedInErrors() throws Exception {
        Path bar = tempDir.resolve("backend/src/main/java/com/foo/Bar.java");
        Files.createDirectories(bar.getParent());
        Files.writeString(bar, "package com.foo;\npublic class Bar { int SENTINEL_FIELD = 1; }");

        BuildResult fail = new BuildResult(1,
                "[ERROR] " + bar + ":[2,20] cannot find symbol\n  symbol: variable x\n");
        when(buildTool.runMvnCompile(any())).thenReturn(fail, PASS);

        ArgumentCaptor<String> trig = ArgumentCaptor.forClass(String.class);
        when(proLlm.runFixAgentLoop(anyString(), trig.capture(), anyList(), any(), any(), anyInt()))
                .thenReturn(true);

        agent.fix(FileType.BACKEND, ctx);

        assertThat(trig.getValue())
                .contains("FILES NAMED IN THE ERRORS")
                .contains("backend/src/main/java/com/foo/Bar.java")
                .contains("SENTINEL_FIELD");   // the file's content was pre-read into the prompt
    }

    @Test
    void trigger_listsDerivedFilesAsReferenceOnly_notEditableContent() throws Exception {
        Path svc = tempDir.resolve("frontend/src/services/orderService.ts");
        Files.createDirectories(svc.getParent());
        Files.writeString(svc, "// GENERATED from the backend API contract\nexport const getAll = 1;\n");

        when(buildTool.runTscCheck(any())).thenReturn(new BuildResult(1,
                "src/services/orderService.ts(2,14): error TS2339: Property 'x' does not exist."));
        when(buildTool.runNpmBuild(any())).thenReturn(PASS);

        ArgumentCaptor<String> trig = ArgumentCaptor.forClass(String.class);
        when(proLlm.runFixAgentLoop(anyString(), trig.capture(), anyList(), any(), any(), anyInt()))
                .thenReturn(true);

        agent.fix(FileType.FRONTEND, ctx);

        // The derived service is named for reference but not dumped as an editable body.
        assertThat(trig.getValue())
                .contains("frontend/src/services/orderService.ts")
                .contains("Derived/immutable");
    }

    // ── Auto-verify hook ──────────────────────────────────────────────────

    @Test
    void autoVerifyHook_runsCompilerAfterMutatingRound_andSkipsReadOnlyRounds() {
        when(buildTool.runMvnCompile(any())).thenReturn(FAIL, FAIL, PASS); // pre, hook, final

        ArgumentCaptor<Function<List<String>, String>> hookCaptor = ArgumentCaptor.forClass(Function.class);
        when(proLlm.runFixAgentLoop(anyString(), anyString(), anyList(), any(), hookCaptor.capture(), anyInt()))
                .thenAnswer(inv -> {
                    Function<List<String>, String> hook = hookCaptor.getValue();
                    assertThat(hook.apply(List.of("read_file", "search_symbol"))).isNull();
                    String afterWrite = hook.apply(List.of("str_replace"));
                    assertThat(afterWrite).contains("[auto-verify]").contains("remaining errors");
                    return true;
                });

        agent.fix(FileType.BACKEND, ctx);
    }

    // ── Tool handler behaviors ────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String invokeTool(FileType fileType, String toolName, String argsJson) {
        if (fileType == FileType.BACKEND) {
            when(buildTool.runMvnCompile(any())).thenReturn(FAIL, PASS);
        } else {
            // Frontend seeds the loop with tsc (runTscCheck); tsc-format errors avoid the npm-build
            // seed fallback. runNpmBuild is the final authoritative check.
            when(buildTool.runTscCheck(any())).thenReturn(TSC_FAIL);
            when(buildTool.runNpmBuild(any())).thenReturn(FAIL, PASS);
        }

        ArgumentCaptor<Function<dev.langchain4j.agent.tool.ToolExecutionRequest, String>> handlerCaptor =
                ArgumentCaptor.forClass(Function.class);
        final String[] toolResult = new String[1];
        when(proLlm.runFixAgentLoop(anyString(), anyString(), anyList(), handlerCaptor.capture(), any(), anyInt()))
                .thenAnswer(inv -> {
                    var req = mock(dev.langchain4j.agent.tool.ToolExecutionRequest.class);
                    when(req.name()).thenReturn(toolName);
                    when(req.arguments()).thenReturn(argsJson);
                    toolResult[0] = handlerCaptor.getValue().apply(req);
                    return true;
                });

        agent.fix(fileType, ctx);
        return toolResult[0];
    }

    @Test
    void writeFile_writesContentToDisk_andUpdatesDb() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(ctx.getTaskId()).thenReturn(taskId);

        GeneratedFile dbRow = GeneratedFile.builder()
                .taskId(taskId)
                .filePath("frontend/src/types/local/order.ts")
                .status(GeneratedFile.FileStatus.GENERATION_FAILED)
                .build();
        when(fileRepo.findByTaskIdAndFilePath(taskId, "frontend/src/types/local/order.ts"))
                .thenReturn(Optional.of(dbRow));

        String result = invokeTool(FileType.FRONTEND, "write_file",
                "{\"path\":\"frontend/src/types/local/order.ts\",\"content\":\"export type OrderResponse = { id: string; };\"}");

        assertThat(result).isEqualTo("OK");
        assertThat(Files.readString(tempDir.resolve("frontend/src/types/local/order.ts")))
                .isEqualTo("export type OrderResponse = { id: string; };");
        verify(fileRepo).save(argThat(f -> f.getStatus() == GeneratedFile.FileStatus.VALIDATED));
    }

    // ── Derived-file fence ────────────────────────────────────────────────

    @Test
    void strReplace_refusesDerivedFile() throws Exception {
        Path derived = tempDir.resolve("frontend/src/services/trainerService.ts");
        Files.createDirectories(derived.getParent());
        Files.writeString(derived, "// GENERATED from the backend API contract — do not edit by hand.\n"
                + "export const getAllTrainers = async () => {};\n");

        String result = invokeTool(FileType.FRONTEND, "str_replace",
                "{\"path\":\"frontend/src/services/trainerService.ts\","
                + "\"old_string\":\"getAllTrainers\",\"new_string\":\"getTrainers\"}");

        assertThat(result).startsWith("REFUSED");
        assertThat(result).contains("Fix the files that IMPORT it");
        assertThat(Files.readString(derived)).contains("getAllTrainers"); // untouched
    }

    @Test
    void writeFile_refusesCreatingPhantomTypeModule() throws Exception {
        String typeResult = invokeTool(FileType.FRONTEND, "write_file",
                "{\"path\":\"frontend/src/types/gymClass.ts\",\"content\":\"export interface GymClassDto { id: string; }\"}");
        assertThat(typeResult).startsWith("REFUSED");
        assertThat(Files.exists(tempDir.resolve("frontend/src/types/gymClass.ts"))).isFalse();
    }

    @Test
    void writeFile_refusesCreatingPhantomServiceModule() throws Exception {
        String svcResult = invokeTool(FileType.FRONTEND, "write_file",
                "{\"path\":\"frontend/src/services/gymClassService.ts\",\"content\":\"export const x = 1;\"}");
        assertThat(svcResult).startsWith("REFUSED");
        assertThat(Files.exists(tempDir.resolve("frontend/src/services/gymClassService.ts"))).isFalse();
    }

    @Test
    void writeFile_allowsLocalTypes() throws Exception {
        String localResult = invokeTool(FileType.FRONTEND, "write_file",
                "{\"path\":\"frontend/src/types/local/cart.ts\",\"content\":\"export interface CartItem { qty: number; }\"}");
        assertThat(localResult).isEqualTo("OK");
    }

    @Test
    void strReplace_allowsNonDerivedPageFiles() throws Exception {
        // A page without the GENERATED marker is freely editable
        Path page = tempDir.resolve("frontend/src/pages/MembershipsPage.tsx");
        Files.createDirectories(page.getParent());
        Files.writeString(page, "const label = plan.durationMonths;");
        String pageResult = invokeTool(FileType.FRONTEND, "str_replace",
                "{\"path\":\"frontend/src/pages/MembershipsPage.tsx\","
                + "\"old_string\":\"plan.durationMonths\",\"new_string\":\"plan.durationInMonths\"}");
        assertThat(pageResult).startsWith("OK");
    }

    @Test
    void strReplace_replacesUniqueOccurrence() throws Exception {
        Path file = tempDir.resolve("backend/src/Foo.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "public class Foo { int x = 1; }");

        String result = invokeTool(FileType.BACKEND, "str_replace",
                "{\"path\":\"backend/src/Foo.java\",\"old_string\":\"int x = 1;\",\"new_string\":\"int x = 2;\"}");

        assertThat(result).startsWith("OK");
        assertThat(Files.readString(file)).isEqualTo("public class Foo { int x = 2; }");
    }

    @Test
    void strReplace_rejectsAmbiguousMatch() throws Exception {
        Path file = tempDir.resolve("backend/src/Foo.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "int a = 1; int a = 1;");

        String result = invokeTool(FileType.BACKEND, "str_replace",
                "{\"path\":\"backend/src/Foo.java\",\"old_string\":\"int a = 1;\",\"new_string\":\"int b = 2;\"}");

        assertThat(result).contains("occurs 2 times");
        assertThat(Files.readString(file)).isEqualTo("int a = 1; int a = 1;"); // untouched
    }

    @Test
    void strReplace_reportsWhenOldStringMissing() throws Exception {
        Path file = tempDir.resolve("backend/src/Foo.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "public class Foo {}");

        String result = invokeTool(FileType.BACKEND, "str_replace",
                "{\"path\":\"backend/src/Foo.java\",\"old_string\":\"does not exist\",\"new_string\":\"x\"}");

        assertThat(result).contains("not found");
    }

    @Test
    void runNpmInstall_refusesFrameworkPackages() {
        String result = invokeTool(FileType.FRONTEND, "run_npm_install", "{\"package\":\"next\"}");

        assertThat(result).contains("framework package");
        verify(buildTool, never()).runNpmInstallPackage(any(), anyString());
    }

    @Test
    void runNpmInstall_refusedOnBackend() {
        String result = invokeTool(FileType.BACKEND, "run_npm_install", "{\"package\":\"date-fns\"}");

        assertThat(result).contains("frontend-only");
    }

    @Test
    void runNpmInstall_installsLegitimatePackage() {
        when(buildTool.runNpmInstallPackage(any(), eq("date-fns"))).thenReturn(PASS);

        String result = invokeTool(FileType.FRONTEND, "run_npm_install", "{\"package\":\"date-fns\"}");

        assertThat(result).contains("OK");
        verify(buildTool).runNpmInstallPackage(tempDir.resolve("frontend"), "date-fns");
    }

    @Test
    void pathTraversal_blocked_by_writeFile_and_strReplace() {
        String writeResult = invokeTool(FileType.BACKEND, "write_file",
                "{\"path\":\"../../etc/passwd\",\"content\":\"evil\"}");
        assertThat(writeResult).startsWith("ERROR: path traversal not allowed");
    }

    // ── Tool specs ────────────────────────────────────────────────────────

    @Test
    void agent_exposes_9_tools_including_bulkStrReplace() {
        when(buildTool.runMvnCompile(any())).thenReturn(FAIL, PASS);
        ArgumentCaptor<List<ToolSpecification>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        when(proLlm.runFixAgentLoop(anyString(), anyString(), toolsCaptor.capture(), any(), any(), anyInt()))
                .thenReturn(true);

        agent.fix(FileType.BACKEND, ctx);

        assertThat(toolsCaptor.getValue()).extracting(ToolSpecification::name)
                .containsExactlyInAnyOrder(
                        "str_replace", "bulk_str_replace", "write_file", "read_file", "run_compiler",
                        "run_npm_install", "search_symbol", "read_architecture_spec", "list_files");
    }
}
