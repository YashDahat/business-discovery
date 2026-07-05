package com.business.discovery.worker.context;

import com.business.discovery.worker.model.ArchitectBrief;
import com.business.discovery.worker.model.BusinessEntity;
import com.business.discovery.worker.model.ContainerTask;
import com.business.discovery.worker.service.llm.BriefContext;
import com.business.discovery.worker.service.llm.FileEntry;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shared mutable state passed through all WorkerNodes.
 * @Value fields are injected by Spring from env vars at startup.
 * All other fields are written by nodes as execution progresses.
 */
@Component
@Getter
public class WorkerContext {

    // ── Task identity (env vars injected by master ContainerSpawnerNode) ──

    @Value("${worker.task.id:}")
    private String taskIdStr;

    @Value("${worker.task.brief-id:}")
    private String briefIdStr;

    @Value("${worker.task.business-id:}")
    private String businessIdStr;

    @Value("${worker.task.attempt-number:0}")
    private int attemptNumber;

    @Value("${worker.task.desc:{}}")
    private String taskDescJson;

    // ── GitHub (env vars injected by master) ──────────────────────────────

    @Value("${worker.github.token:}")
    private String githubToken;

    @Setter
    @Value("${worker.github.repo-url:}")
    private String githubRepoUrl;      // may be updated by GitWorkspaceNode

    @Value("${worker.github.branch:}")
    private String githubBranch;

    @Value("${worker.github.owner:}")
    private String githubOwner;

    // ── Workspace ─────────────────────────────────────────────────────────

    @Value("${worker.workspace.dir:/workspace}")
    private String workspaceDirStr;

    // ── Runtime state (set by nodes, consumed by subsequent nodes) ────────

    @Setter
    private List<FileEntry> fileManifest = new ArrayList<>();

    // Set by FileManifestGeneratorNode; reused by all generator nodes (nodes 5-7)
    @Setter
    private BriefContext briefCtx;

    // Loaded entities — set by FileManifestGeneratorNode after first DB read
    @Setter
    private ArchitectBrief brief;

    @Setter
    private BusinessEntity business;

    @Setter
    private ContainerTask task;

    @Setter
    private String githubPrUrl; // set by PullRequestNode

    // Set by SmokeTestNode when all gates pass — the locally-built, gate-passing image tag.
    // ImagePublishNode pushes it to GHCR; null when smoke testing is disabled or skipped.
    @Setter
    private String smokeTestedImage;

    // Populated by GitWorkspaceNode — null on first run
    @Setter
    private String projectHistory;

    // ── Convenience accessors ─────────────────────────────────────────────

    public UUID getTaskId() {
        if (taskIdStr == null || taskIdStr.isBlank()) return null;
        return UUID.fromString(taskIdStr);
    }

    public UUID getBriefId() {
        if (briefIdStr == null || briefIdStr.isBlank()) return null;
        return UUID.fromString(briefIdStr);
    }

    public UUID getBusinessId() {
        if (businessIdStr == null || businessIdStr.isBlank()) return null;
        return UUID.fromString(businessIdStr);
    }

    public Path getWorkspaceDir() {
        return Paths.get(workspaceDirStr);
    }
}
