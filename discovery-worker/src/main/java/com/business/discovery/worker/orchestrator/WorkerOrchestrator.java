package com.business.discovery.worker.orchestrator;

import com.business.discovery.worker.nodes.WorkerNode;
import com.business.discovery.worker.context.WorkerContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class WorkerOrchestrator {

    private final WorkerContext ctx;

    // Nodes are discovered via component scanning. Each node MUST carry
    // @Order(N) to guarantee execution sequence (1=FileManifest … 14=PullRequest).
    // required=false allows the module to start with zero nodes during early commits.
    @Autowired(required = false)
    private List<WorkerNode> nodes = new ArrayList<>();

    public WorkerOrchestrator(WorkerContext ctx) {
        this.ctx = ctx;
    }

    public void run() {
        log.info("[worker] Starting — taskId={} briefId={} attempt={}",
                ctx.getTaskIdStr(), ctx.getBriefIdStr(), ctx.getAttemptNumber());

        for (WorkerNode node : nodes) {
            String nodeName = node.getClass().getSimpleName();
            log.info("[{}] executing", nodeName);
            node.execute(ctx);
            log.info("[{}] completed", nodeName);
        }

        log.info("[worker] All {} nodes completed successfully", nodes.size());
    }
}
