package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.model.BusinessEntity;
import com.business.discovery.worker.repository.ContainerTaskRepository;
import com.business.discovery.worker.service.GitHubApiService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitHubRepoNodeTest {

    @Mock private GitHubApiService gitHubApiService;
    @Mock private ContainerTaskRepository taskRepo;
    @Mock private WorkerContext ctx;

    @InjectMocks
    private GitHubRepoNode node;

    @Test
    void repoUrlAlreadySet_skipsApiCall() {
        when(ctx.getGithubRepoUrl()).thenReturn("https://github.com/owner/existing-repo");

        node.execute(ctx);

        verifyNoInteractions(gitHubApiService);
        verifyNoInteractions(taskRepo);
    }

    @Test
    void repoUrlBlank_createsRepoAndUpdatesContext() {
        UUID taskId = UUID.randomUUID();
        when(ctx.getGithubRepoUrl()).thenReturn("");
        when(ctx.getTaskId()).thenReturn(taskId);
        when(ctx.getBusiness()).thenReturn(BusinessEntity.builder().title("Shree Cafe").build());
        when(gitHubApiService.createRepo(eq("shree-cafe"), anyString()))
                .thenReturn("https://github.com/owner/shree-cafe");

        node.execute(ctx);

        verify(ctx).setGithubRepoUrl("https://github.com/owner/shree-cafe");
        verify(taskRepo).updateGithubRepoUrl(taskId, "https://github.com/owner/shree-cafe");
    }

    @Test
    void repoUrlNull_createsRepoAndUpdatesContext() {
        UUID taskId = UUID.randomUUID();
        when(ctx.getGithubRepoUrl()).thenReturn(null);
        when(ctx.getTaskId()).thenReturn(taskId);
        when(ctx.getBusiness()).thenReturn(BusinessEntity.builder().title("Pizza Hub").build());
        when(gitHubApiService.createRepo(eq("pizza-hub"), anyString()))
                .thenReturn("https://github.com/owner/pizza-hub");

        node.execute(ctx);

        verify(ctx).setGithubRepoUrl("https://github.com/owner/pizza-hub");
        verify(taskRepo).updateGithubRepoUrl(taskId, "https://github.com/owner/pizza-hub");
    }

    @Test
    void apiReturnsConfigAuthError_propagatesException() {
        when(ctx.getGithubRepoUrl()).thenReturn(null);
        when(ctx.getBusiness()).thenReturn(BusinessEntity.builder().title("Test Biz").build());
        when(gitHubApiService.createRepo(anyString(), anyString()))
                .thenThrow(new WorkerException(FailureType.CONFIG_AUTH, "GitHub auth failed — check GITHUB_TOKEN. HTTP 401"));

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> assertThat(((WorkerException) ex).getFailureType())
                        .isEqualTo(FailureType.CONFIG_AUTH));
    }

    @Test
    void toRepoName_convertsCorrectly() {
        assertThat(GitHubRepoNode.toRepoName("Shree Restaurant")).isEqualTo("shree-restaurant");
        assertThat(GitHubRepoNode.toRepoName("Pizza & Co.!")).isEqualTo("pizza-co");
        assertThat(GitHubRepoNode.toRepoName("  My Cafe  ")).isEqualTo("my-cafe");
        assertThat(GitHubRepoNode.toRepoName(null)).isEqualTo("business-site");
    }
}
