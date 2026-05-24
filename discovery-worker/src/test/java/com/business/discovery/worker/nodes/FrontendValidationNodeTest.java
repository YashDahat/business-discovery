package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.service.BuildToolService;
import com.business.discovery.worker.service.BuildToolService.BuildResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FrontendValidationNodeTest {

    @Mock private BuildToolService buildTool;
    @Mock private ErrorFixNode errorFix;
    @Mock private WorkerContext ctx;

    @InjectMocks
    private FrontendValidationNode node;

    @TempDir
    Path tempDir;

    @Test
    void npmInstallAndBuildSucceed_noRetry() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(buildTool.runNpmInstall(tempDir.resolve("frontend")))
                .thenReturn(new BuildResult(0, ""));
        when(buildTool.runNpmBuild(tempDir.resolve("frontend")))
                .thenReturn(new BuildResult(0, ""));

        node.execute(ctx);

        verify(buildTool, times(1)).runNpmBuild(any());
        verifyNoInteractions(errorFix);
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

        verifyNoInteractions(errorFix);
    }

    @Test
    void buildFailsThenPassesAfterFix_passes() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(buildTool.runNpmInstall(tempDir.resolve("frontend")))
                .thenReturn(new BuildResult(0, ""));
        when(buildTool.runNpmBuild(tempDir.resolve("frontend")))
                .thenReturn(new BuildResult(1, "ERROR in src/App.tsx\nTS2304: Cannot find name 'x'"))
                .thenReturn(new BuildResult(0, ""));
        when(errorFix.fix(anyString(), eq(FileType.FRONTEND), eq(ctx))).thenReturn(true);

        node.execute(ctx);

        verify(buildTool, times(2)).runNpmBuild(any());
        verify(errorFix, times(1)).fix(anyString(), eq(FileType.FRONTEND), eq(ctx));
    }

    @Test
    void buildFailsThreeTimes_throwsCodeException() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(buildTool.runNpmInstall(tempDir.resolve("frontend")))
                .thenReturn(new BuildResult(0, ""));
        when(buildTool.runNpmBuild(tempDir.resolve("frontend")))
                .thenReturn(new BuildResult(1, "TypeScript error"));
        when(errorFix.fix(anyString(), eq(FileType.FRONTEND), eq(ctx))).thenReturn(true);

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> assertThat(((WorkerException) ex).getFailureType())
                        .isEqualTo(FailureType.CODE));

        verify(buildTool, times(3)).runNpmBuild(any());
        verify(errorFix, times(2)).fix(anyString(), eq(FileType.FRONTEND), eq(ctx));
    }
}
