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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BackendValidationNodeTest {

    @Mock private BuildToolService buildTool;
    @Mock private ErrorFixNode errorFix;
    @Mock private WorkerContext ctx;

    @InjectMocks
    private BackendValidationNode node;

    @TempDir
    Path tempDir;

    @Test
    void mvnCompileSucceeds_noRetry() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(buildTool.runMvnCompile(tempDir.resolve("backend")))
                .thenReturn(new BuildResult(0, ""));

        node.execute(ctx);

        verify(buildTool, times(1)).runMvnCompile(any());
        verifyNoInteractions(errorFix);
    }

    @Test
    void mvnFailsThenSucceedsAfterFix_passes() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(buildTool.runMvnCompile(tempDir.resolve("backend")))
                .thenReturn(new BuildResult(1, "[ERROR] Some.java:[1,1] error: bad code"))
                .thenReturn(new BuildResult(0, ""));
        when(errorFix.fix(anyString(), eq(FileType.BACKEND), eq(ctx))).thenReturn(true);

        node.execute(ctx);

        verify(buildTool, times(2)).runMvnCompile(any());
        verify(errorFix, times(1)).fix(anyString(), eq(FileType.BACKEND), eq(ctx));
    }

    @Test
    void mvnFailsThreeTimes_throwsCodeException() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(buildTool.runMvnCompile(tempDir.resolve("backend")))
                .thenReturn(new BuildResult(1, "[ERROR] compilation failed"));
        when(errorFix.fix(anyString(), eq(FileType.BACKEND), eq(ctx))).thenReturn(true);

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> assertThat(((WorkerException) ex).getFailureType())
                        .isEqualTo(FailureType.CODE));

        verify(buildTool, times(3)).runMvnCompile(any());
        verify(errorFix, times(2)).fix(anyString(), eq(FileType.BACKEND), eq(ctx));
    }

    @Test
    void fixReturnsFalse_stopsRetryingEarly() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(buildTool.runMvnCompile(tempDir.resolve("backend")))
                .thenReturn(new BuildResult(1, "[ERROR] cannot parse"));
        when(errorFix.fix(anyString(), eq(FileType.BACKEND), eq(ctx))).thenReturn(false);

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> assertThat(((WorkerException) ex).getFailureType())
                        .isEqualTo(FailureType.CODE));

        // Only 1 compile attempt + 1 fix attempt (fix returned false → no further retries)
        verify(buildTool, times(1)).runMvnCompile(any());
        verify(errorFix, times(1)).fix(anyString(), any(), any());
    }
}
