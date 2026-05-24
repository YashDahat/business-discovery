package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.model.BusinessEntity;
import com.business.discovery.worker.service.GitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
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
class GitCommitNodeTest {

    @Mock private GitService gitService;
    @Mock private WorkerContext ctx;

    @InjectMocks
    private GitCommitNode node;

    @TempDir
    Path tempDir;

    @Test
    void commitMessage_containsBusinessNameAndAttemptNumber() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getBusiness()).thenReturn(BusinessEntity.builder().title("Shree Cafe").build());
        when(ctx.getAttemptNumber()).thenReturn(2);

        node.execute(ctx);

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(gitService).addAll(tempDir);
        verify(gitService).commit(eq(tempDir), msgCaptor.capture());

        assertThat(msgCaptor.getValue())
                .contains("Shree Cafe")
                .contains("attempt 2")
                .startsWith("feat:");
    }

    @Test
    void addAll_calledBeforeCommit() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getBusiness()).thenReturn(BusinessEntity.builder().title("Pizza Hub").build());
        when(ctx.getAttemptNumber()).thenReturn(1);

        node.execute(ctx);

        var order = inOrder(gitService);
        order.verify(gitService).addAll(tempDir);
        order.verify(gitService).commit(eq(tempDir), any());
    }

    @Test
    void gitAddFails_throwsInfraException() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getBusiness()).thenReturn(BusinessEntity.builder().title("Test Biz").build());
        when(ctx.getAttemptNumber()).thenReturn(1);
        doThrow(new WorkerException(FailureType.INFRA, "git add -A failed"))
                .when(gitService).addAll(tempDir);

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> assertThat(((WorkerException) ex).getFailureType())
                        .isEqualTo(FailureType.INFRA));

        verify(gitService, never()).commit(any(), any());
    }

    @Test
    void gitCommitFails_throwsInfraException() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getBusiness()).thenReturn(BusinessEntity.builder().title("Test Biz").build());
        when(ctx.getAttemptNumber()).thenReturn(1);
        doThrow(new WorkerException(FailureType.INFRA, "git commit failed"))
                .when(gitService).commit(eq(tempDir), any());

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> assertThat(((WorkerException) ex).getFailureType())
                        .isEqualTo(FailureType.INFRA));
    }
}
