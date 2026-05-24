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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitCloneNodeTest {

    @Mock private GitService gitService;
    @Mock private WorkerContext ctx;

    @InjectMocks
    private GitCloneNode node;

    @TempDir
    Path tempDir;

    @Test
    void successfulInit_callsGitOpsInOrder() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getGithubRepoUrl()).thenReturn("https://github.com/owner/test-repo");
        when(ctx.getGithubToken()).thenReturn("ghp_mytoken");
        when(ctx.getGithubBranch()).thenReturn("feature/shree-cafe");

        node.execute(ctx);

        verify(gitService).init(tempDir);
        verify(gitService).remoteAdd(eq(tempDir), eq("origin"),
                contains("x-access-token:ghp_mytoken@"));
        verify(gitService).checkout(tempDir, "feature/shree-cafe", true);
    }

    @Test
    void authedUrlContainsToken() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getGithubRepoUrl()).thenReturn("https://github.com/owner/my-repo");
        when(ctx.getGithubToken()).thenReturn("ghp_secret123");
        when(ctx.getGithubBranch()).thenReturn("main");

        node.execute(ctx);

        verify(gitService).remoteAdd(eq(tempDir), eq("origin"),
                eq("https://x-access-token:ghp_secret123@github.com/owner/my-repo"));
    }

    @Test
    void gitInitFails_throwsInfraException() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getGithubRepoUrl()).thenReturn("https://github.com/owner/repo");
        when(ctx.getGithubToken()).thenReturn("token");
        when(ctx.getGithubBranch()).thenReturn("main");
        doThrow(new WorkerException(FailureType.INFRA, "git init failed (exit 128): permission denied"))
                .when(gitService).init(tempDir);

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> org.assertj.core.api.Assertions
                        .assertThat(((WorkerException) ex).getFailureType())
                        .isEqualTo(FailureType.INFRA));
    }

    @Test
    void gitCheckoutFails_throwsInfraException() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getGithubRepoUrl()).thenReturn("https://github.com/owner/repo");
        when(ctx.getGithubToken()).thenReturn("token");
        when(ctx.getGithubBranch()).thenReturn("feature/branch");
        doThrow(new WorkerException(FailureType.INFRA, "git checkout failed for branch feature/branch"))
                .when(gitService).checkout(tempDir, "feature/branch", true);

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> org.assertj.core.api.Assertions
                        .assertThat(((WorkerException) ex).getFailureType())
                        .isEqualTo(FailureType.INFRA));
    }
}
