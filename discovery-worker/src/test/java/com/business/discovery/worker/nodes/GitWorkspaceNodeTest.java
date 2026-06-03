package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.model.BusinessEntity;
import com.business.discovery.worker.repository.ContainerTaskRepository;
import com.business.discovery.worker.service.GitHubApiService;
import com.business.discovery.worker.service.GitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitWorkspaceNodeTest {

    @Mock private GitHubApiService gitHubApiService;
    @Mock private ContainerTaskRepository taskRepo;
    @Mock private GitService gitService;
    @Mock private WorkerContext ctx;

    @InjectMocks
    private GitWorkspaceNode node;

    @TempDir Path tempDir;

    // ── Repo creation ─────────────────────────────────────────────────────

    @Test
    void repoUrlAlreadySet_skipsApiCall() {
        when(ctx.getGithubRepoUrl()).thenReturn("https://github.com/owner/existing-repo");
        stubWorkspaceDefaults();

        node.execute(ctx);

        verifyNoInteractions(gitHubApiService);
        verifyNoInteractions(taskRepo);
    }

    @Test
    void repoUrlBlank_createsRepoAndUpdatesContext() {
        UUID taskId = UUID.randomUUID();
        String repoUrl = "https://github.com/owner/shree-cafe";
        BusinessEntity business = BusinessEntity.builder().title("Shree Cafe").build();

        when(ctx.getGithubRepoUrl()).thenReturn("", repoUrl);
        when(ctx.getTaskId()).thenReturn(taskId);
        when(ctx.getBusiness()).thenReturn(business);
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getGithubToken()).thenReturn("token");
        when(ctx.getGithubBranch()).thenReturn("feature/test");
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(gitHubApiService.createRepo(eq("shree-cafe"), anyString())).thenReturn(repoUrl);

        node.execute(ctx);

        verify(ctx).setGithubRepoUrl(repoUrl);
        verify(taskRepo).updateGithubRepoUrl(taskId, repoUrl);
    }

    @Test
    void repoUrlNull_createsRepo() {
        UUID taskId = UUID.randomUUID();
        String repoUrl = "https://github.com/owner/pizza-hub";
        BusinessEntity business = BusinessEntity.builder().title("Pizza Hub").build();

        when(ctx.getGithubRepoUrl()).thenReturn(null, repoUrl);
        when(ctx.getTaskId()).thenReturn(taskId);
        when(ctx.getBusiness()).thenReturn(business);
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getGithubToken()).thenReturn("token");
        when(ctx.getGithubBranch()).thenReturn("feature/test");
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(gitHubApiService.createRepo(eq("pizza-hub"), anyString())).thenReturn(repoUrl);

        node.execute(ctx);

        verify(ctx).setGithubRepoUrl(repoUrl);
    }

    @Test
    void githubAuthError_propagatesConfigAuthException() {
        when(ctx.getGithubRepoUrl()).thenReturn(null);
        when(ctx.getBusiness()).thenReturn(BusinessEntity.builder().title("Test Biz").build());
        when(gitHubApiService.createRepo(anyString(), anyString()))
                .thenThrow(new WorkerException(FailureType.CONFIG_AUTH, "HTTP 401"));

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> assertThat(((WorkerException) ex).getFailureType())
                        .isEqualTo(FailureType.CONFIG_AUTH));
    }

    // ── Workspace init ────────────────────────────────────────────────────

    @Test
    void remoteBranchAbsent_callsCheckoutFromMain() {
        when(ctx.getGithubRepoUrl()).thenReturn("https://github.com/owner/test-repo");
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getGithubToken()).thenReturn("ghp_mytoken");
        when(ctx.getGithubBranch()).thenReturn("feature/shree-cafe");
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(ctx.getBusiness()).thenReturn(BusinessEntity.builder().title("Shree Cafe").build());
        when(gitService.remoteBranchExists(tempDir, "feature/shree-cafe")).thenReturn(false);

        node.execute(ctx);

        verify(gitService).init(tempDir);
        verify(gitService).remoteAdd(eq(tempDir), eq("origin"), contains("x-access-token:ghp_mytoken@"));
        verify(gitService).fetchAll(tempDir);
        verify(gitService).checkoutFromMain(tempDir, "feature/shree-cafe");
        verify(gitService, never()).checkoutFromRef(any(), any(), any());
    }

    @Test
    void remoteBranchExists_checksOutFromRemoteBranch() {
        when(ctx.getGithubRepoUrl()).thenReturn("https://github.com/owner/test-repo");
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getGithubToken()).thenReturn("ghp_mytoken");
        when(ctx.getGithubBranch()).thenReturn("feature/shree-cafe");
        when(ctx.getAttemptNumber()).thenReturn(2);
        when(ctx.getBusiness()).thenReturn(BusinessEntity.builder().title("Shree Cafe").build());
        when(gitService.remoteBranchExists(tempDir, "feature/shree-cafe")).thenReturn(true);

        node.execute(ctx);

        verify(gitService).checkoutFromRef(tempDir, "feature/shree-cafe", "origin/feature/shree-cafe");
        verify(gitService).pullRebase(tempDir, "feature/shree-cafe");
        verify(gitService, never()).checkoutFromMain(any(), any());
    }

    @Test
    void authedUrl_embedsTokenCorrectly() {
        when(ctx.getGithubRepoUrl()).thenReturn("https://github.com/owner/my-repo");
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getGithubToken()).thenReturn("ghp_secret123");
        when(ctx.getGithubBranch()).thenReturn("main");
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(ctx.getBusiness()).thenReturn(BusinessEntity.builder().title("Test").build());

        node.execute(ctx);

        verify(gitService).remoteAdd(eq(tempDir), eq("origin"),
                eq("https://x-access-token:ghp_secret123@github.com/owner/my-repo"));
    }

    @Test
    void gitInitFails_throwsInfraException() {
        when(ctx.getGithubRepoUrl()).thenReturn("https://github.com/owner/repo");
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getGithubToken()).thenReturn("token");
        doThrow(new WorkerException(FailureType.INFRA, "git init failed"))
                .when(gitService).init(tempDir);

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> assertThat(((WorkerException) ex).getFailureType())
                        .isEqualTo(FailureType.INFRA));
    }

    // ── Project history loading ───────────────────────────────────────────

    @Test
    void historyFileExists_setsProjectHistory() throws IOException {
        stubWorkspaceForHistory();
        Files.createDirectories(tempDir.resolve("docs"));
        Files.writeString(tempDir.resolve("docs/PROJECT_HISTORY.md"), "# History\n\n## Attempt 1");

        node.execute(ctx);

        verify(ctx).setProjectHistory("# History\n\n## Attempt 1");
    }

    @Test
    void historyFileMissing_continuesWithoutHistory() {
        stubWorkspaceForHistory();

        node.execute(ctx);

        verify(ctx, never()).setProjectHistory(any());
    }

    // ── Static helper tests ───────────────────────────────────────────────

    @Test
    void toRepoName_convertsCorrectly() {
        assertThat(GitWorkspaceNode.toRepoName("Shree Restaurant")).isEqualTo("shree-restaurant");
        assertThat(GitWorkspaceNode.toRepoName("Pizza & Co.!")).isEqualTo("pizza-co");
        assertThat(GitWorkspaceNode.toRepoName("  My Cafe  ")).isEqualTo("my-cafe");
        assertThat(GitWorkspaceNode.toRepoName(null)).isEqualTo("business-site");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void stubWorkspaceDefaults() {
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getGithubToken()).thenReturn("token");
        when(ctx.getGithubBranch()).thenReturn("feature/test");
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(ctx.getBusiness()).thenReturn(BusinessEntity.builder().title("Test").build());
    }

    private void stubWorkspaceForHistory() {
        when(ctx.getGithubRepoUrl()).thenReturn("https://github.com/owner/repo");
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        when(ctx.getGithubToken()).thenReturn("token");
        when(ctx.getGithubBranch()).thenReturn("feature/test");
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(ctx.getBusiness()).thenReturn(BusinessEntity.builder().title("Test").build());
    }
}
