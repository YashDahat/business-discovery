package com.business.discovery.agents.coder;

import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;
import java.util.Optional;

public class CoderAgentState extends AgentState {

    public static final String RUN_ID          = "run_id";
    public static final String BRIEF_ID        = "brief_id";
    public static final String BUSINESS_ID     = "business_id";
    public static final String TASK_ID         = "task_id";
    public static final String TASK_STATUS     = "task_status";
    public static final String CONTAINER_ID    = "container_id";
    public static final String GITHUB_REPO_URL = "github_repo_url";
    public static final String GITHUB_BRANCH   = "github_branch";
    public static final String ERROR           = "error";
    public static final String SKIP_REASON     = "skip_reason";

    public CoderAgentState(Map<String, Object> initData) {
        super(initData);
    }

    public Optional<String> runId() { return value(RUN_ID); }
    public Optional<String> briefId() { return value(BRIEF_ID); }
    public Optional<String> businessId() { return value(BUSINESS_ID); }
    public Optional<String> taskId() { return value(TASK_ID); }
    public Optional<String> taskStatus() { return value(TASK_STATUS); }
    public Optional<String> containerId() { return value(CONTAINER_ID); }
    public Optional<String> githubRepoUrl() { return value(GITHUB_REPO_URL); }
    public Optional<String> githubBranch() { return value(GITHUB_BRANCH); }
    public Optional<String> error() { return value(ERROR); }
    public Optional<String> skipReason() { return value(SKIP_REASON); }
}