package com.business.discovery.services.cline;

import com.business.discovery.model.ArchitectBrief;
import com.business.discovery.model.BusinessEntity;
import com.business.discovery.repository.ArchitectBriefRepository;
import com.business.discovery.repository.BusinessEntityRepository;
import com.business.discovery.repository.ContainerTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves which GitHub repo a Cline session's brief maps to, and the working branch its edits land on.
 *
 * Repo url precedence: the brief's own {@code githubRepoUrl} (set when Cline creates a repo) first, then
 * the latest {@link com.business.discovery.model.ContainerTask}'s repo (the coder pipeline's repo) as a
 * fallback — the same "latest task" lookup {@link ClineChatService} uses. The repo name is derived from
 * the url when present, otherwise slugified from the business title (matching the worker's convention) so
 * a not-yet-created repo still has a deterministic name to create.
 */
@Component
@RequiredArgsConstructor
public class RepoScopeResolver {

    private final ArchitectBriefRepository briefRepository;
    private final ContainerTaskRepository containerTaskRepository;
    private final BusinessEntityRepository businessRepository;

    @Value("${cline.repo.working-branch:cline/edits}")
    private String workingBranch;

    /** Everything the repo tools need for one brief. {@code repoUrl} is null when no repo exists yet. */
    public record RepoScope(
            UUID briefId,
            UUID businessId,
            String businessTitle,
            String repoUrl,
            String repoName,
            String workingBranch,
            boolean hasRepo
    ) {}

    public RepoScope resolve(UUID briefId) {
        ArchitectBrief brief = briefRepository.findById(briefId)
                .orElseThrow(() -> new IllegalArgumentException("ArchitectBrief not found: " + briefId));

        String repoUrl = brief.getGithubRepoUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            repoUrl = containerTaskRepository.findTopByBriefIdOrderByCreatedAtDesc(briefId)
                    .map(t -> t.getGithubRepoUrl())
                    .filter(u -> u != null && !u.isBlank())
                    .orElse(null);
        }

        String businessTitle = brief.getBusinessId() == null ? null
                : businessRepository.findById(brief.getBusinessId())
                    .map(BusinessEntity::getTitle)
                    .orElse(null);

        String repoName = deriveRepoName(repoUrl, businessTitle);

        return new RepoScope(briefId, brief.getBusinessId(), businessTitle,
                repoUrl, repoName, workingBranch, repoUrl != null);
    }

    /** Repo short-name from the url (last path segment, sans .git), else slug of the business title. */
    static String deriveRepoName(String repoUrl, String businessTitle) {
        if (repoUrl != null && !repoUrl.isBlank()) {
            String trimmed = repoUrl.replaceAll("/+$", "");
            String last = trimmed.substring(trimmed.lastIndexOf('/') + 1);
            return last.replaceAll("\\.git$", "");
        }
        return toRepoSlug(businessTitle);
    }

    /** Same slug rule as the worker's GitWorkspaceNode.toRepoName. */
    static String toRepoSlug(String businessName) {
        if (businessName == null || businessName.isBlank()) return "business-site";
        String slug = businessName.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "business-site" : slug;
    }
}
