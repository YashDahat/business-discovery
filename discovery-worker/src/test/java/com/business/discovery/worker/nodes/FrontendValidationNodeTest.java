package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.service.BuildToolService;
import com.business.discovery.worker.service.BuildToolService.BuildResult;
import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.util.ArchitectureJsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FrontendValidationNodeTest {

    @Mock private BuildToolService buildTool;
    @Mock private ErrorFixAgent errorFixAgent;
    @Mock private WorkerContext ctx;

    @InjectMocks
    private FrontendValidationNode node;

    @TempDir
    Path tempDir;

    /** Success paths hit the blank-SPA guard — give the workspace a routed App.tsx. */
    private void writeRoutedAppTsx() {
        try {
            Path app = tempDir.resolve("frontend/src/App.tsx");
            java.nio.file.Files.createDirectories(app.getParent());
            java.nio.file.Files.writeString(app,
                    "import { BrowserRouter, Routes, Route } from 'react-router-dom'\n"
                    + "export default function App() { return <BrowserRouter><Routes>"
                    + "<Route path=\"/\" element={<div/>} /></Routes></BrowserRouter> }\n");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void npmInstallAndBuildSucceed_agentNotCalled() {
        writeRoutedAppTsx();
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(buildTool.runNpmInstall(tempDir.resolve("frontend")))
                .thenReturn(new BuildResult(0, ""));
        when(buildTool.runNpmBuild(tempDir.resolve("frontend")))
                .thenReturn(new BuildResult(0, ""));

        node.execute(ctx);

        verify(buildTool, times(1)).runNpmBuild(any());
        verifyNoInteractions(errorFixAgent);
    }

    @Test
    void npmInstallFails_throwsInfraException() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(buildTool.runNpmInstall(tempDir.resolve("frontend")))
                .thenReturn(new BuildResult(1, "npm install failed: ENOENT"));

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> assertThat(((WorkerException) ex).getFailureType())
                        .isEqualTo(FailureType.INFRA));

        verifyNoInteractions(errorFixAgent);
    }

    @Test
    void buildFails_agentFixes_finalBuildPasses() {
        writeRoutedAppTsx();
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(buildTool.runNpmInstall(tempDir.resolve("frontend")))
                .thenReturn(new BuildResult(0, ""));
        when(buildTool.runNpmBuild(tempDir.resolve("frontend")))
                .thenReturn(new BuildResult(1, "TypeScript error"))
                .thenReturn(new BuildResult(0, ""));
        when(errorFixAgent.fix(eq(FileType.FRONTEND), eq(ctx))).thenReturn(true);

        node.execute(ctx);

        verify(errorFixAgent).fix(FileType.FRONTEND, ctx);
        verify(buildTool, times(2)).runNpmBuild(any()); // initial fail + final authoritative build
    }

    @Test
    void buildFails_agentCannotFix_throwsCodeException() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(buildTool.runNpmInstall(tempDir.resolve("frontend")))
                .thenReturn(new BuildResult(0, ""));
        when(buildTool.runNpmBuild(tempDir.resolve("frontend")))
                .thenReturn(new BuildResult(1, "TypeScript error"));
        when(errorFixAgent.fix(eq(FileType.FRONTEND), eq(ctx))).thenReturn(false);

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> assertThat(((WorkerException) ex).getFailureType())
                        .isEqualTo(FailureType.CODE));
    }

    @Test
    void buildFails_agentFixes_butFinalBuildStillFails_throwsCodeException() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(buildTool.runNpmInstall(tempDir.resolve("frontend")))
                .thenReturn(new BuildResult(0, ""));
        when(buildTool.runNpmBuild(tempDir.resolve("frontend")))
                .thenReturn(new BuildResult(1, "TypeScript error"))
                .thenReturn(new BuildResult(1, "still broken after agent"));
        when(errorFixAgent.fix(eq(FileType.FRONTEND), eq(ctx))).thenReturn(true);

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> assertThat(((WorkerException) ex).getFailureType())
                        .isEqualTo(FailureType.CODE));
    }

    @Test
    void buildPasses_marksFrontendFilesAsValidated() throws Exception {
        writeRoutedAppTsx();
        FileSpec frontendFile = FileSpec.builder()
                .filePath("frontend/src/App.tsx")
                .fileType("FRONTEND")
                .status("GENERATED")
                .build();
        ArchitectureJsonUtil.write(tempDir, ArchitectureSpec.builder().files(List.of(frontendFile)).build());

        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(buildTool.runNpmInstall(tempDir.resolve("frontend"))).thenReturn(new BuildResult(0, ""));
        when(buildTool.runNpmBuild(tempDir.resolve("frontend"))).thenReturn(new BuildResult(0, ""));

        node.execute(ctx);

        ArchitectureSpec updated = ArchitectureJsonUtil.read(tempDir);
        assertThat(updated.getFiles().get(0).getStatus()).isEqualTo("VALIDATED");
    }
}
