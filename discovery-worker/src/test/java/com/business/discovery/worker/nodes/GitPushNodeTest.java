package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.service.GitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitPushNodeTest {

    @Mock private GitService gitService;
    @Mock private WorkerContext ctx;

    @InjectMocks
    private GitPushNode node;

    @TempDir
    Path tempDir;

    @Test
    void push_succeeds_onFirstAttempt() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getGithubBranch()).thenReturn("feature/test");

        node.execute(ctx);

        verify(gitService).push(tempDir, "feature/test");
        verify(gitService, never()).pullRebase(any(), any());
    }

    @Test
    void push_failsFirst_rebasesThenSucceeds() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getGithubBranch()).thenReturn("feature/test");
        doThrow(new WorkerException(FailureType.INFRA, "push rejected"))
                .doNothing()
                .when(gitService).push(tempDir, "feature/test");

        node.execute(ctx);

        var order = inOrder(gitService);
        order.verify(gitService).push(tempDir, "feature/test");
        order.verify(gitService).pullRebase(tempDir, "feature/test");
        order.verify(gitService).push(tempDir, "feature/test");
    }

    @Test
    void push_failsTwice_throwsInfraException() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getGithubBranch()).thenReturn("feature/test");
        doThrow(new WorkerException(FailureType.INFRA, "push rejected"))
                .when(gitService).push(eq(tempDir), eq("feature/test"));

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> assertThat(((WorkerException) ex).getFailureType())
                        .isEqualTo(FailureType.INFRA));

        verify(gitService, times(2)).push(tempDir, "feature/test");
        verify(gitService).pullRebase(tempDir, "feature/test");
    }
}
