package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.model.ArchitectBrief;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PullRequestNodeTest {

    @Mock private GitHubApiService gitHubApiService;
    @Mock private ContainerTaskRepository taskRepo;
    @Mock private WorkerContext ctx;

    @InjectMocks
    private PullRequestNode node;

    private static final UUID TASK_ID = UUID.randomUUID();
    private static final String REPO_URL = "https://github.com/owner/shree-cafe";
    private static final String BRANCH = "feature/shree-cafe-attempt-1";
    private static final String PR_URL = "https://github.com/owner/shree-cafe/pull/1";

    private void stubCommonContext() {
        when(ctx.getGithubRepoUrl()).thenReturn(REPO_URL);
        when(ctx.getGithubBranch()).thenReturn(BRANCH);
        when(ctx.getBusiness()).thenReturn(
                BusinessEntity.builder().title("Shree Cafe").category("Restaurant").build());
        when(ctx.getBrief()).thenReturn(
                ArchitectBrief.builder().location("Pune").build());
        when(ctx.getAttemptNumber()).thenReturn(1);
        when(ctx.getBriefId()).thenReturn(UUID.randomUUID());
    }

    @Test
    void createsPr_savesUrlToCtxAndDb() {
        stubCommonContext();
        when(ctx.getTaskId()).thenReturn(TASK_ID);
        when(gitHubApiService.createPullRequest(eq("shree-cafe"), eq(BRANCH), any(), any()))
                .thenReturn(PR_URL);

        node.execute(ctx);

        verify(ctx).setGithubPrUrl(PR_URL);
        verify(taskRepo).updateGithubPrUrl(TASK_ID, PR_URL);
    }

    @Test
    void prAlreadyExists_gitHubServiceReturnsExistingUrl() {
        stubCommonContext();
        when(ctx.getTaskId()).thenReturn(TASK_ID);
        String existingUrl = "https://github.com/owner/shree-cafe/pull/5";
        // GitHubApiService handles 422 internally and returns the existing PR URL
        when(gitHubApiService.createPullRequest(eq("shree-cafe"), eq(BRANCH), any(), any()))
                .thenReturn(existingUrl);

        node.execute(ctx);

        verify(ctx).setGithubPrUrl(existingUrl);
        verify(taskRepo).updateGithubPrUrl(TASK_ID, existingUrl);
    }

    @Test
    void authFails_configAuthExceptionPropagates() {
        stubCommonContext();
        when(gitHubApiService.createPullRequest(any(), any(), any(), any()))
                .thenThrow(new WorkerException(FailureType.CONFIG_AUTH, "GitHub auth failed"));

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> assertThat(((WorkerException) ex).getFailureType())
                        .isEqualTo(FailureType.CONFIG_AUTH));

        verify(taskRepo, never()).updateGithubPrUrl(any(), any());
    }
}
