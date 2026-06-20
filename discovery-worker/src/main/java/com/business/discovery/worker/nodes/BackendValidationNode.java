package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.service.BuildToolService;
import com.business.discovery.worker.service.BuildToolService.BuildResult;
import com.business.discovery.worker.util.ArchitectureJsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(11)
@Slf4j
@RequiredArgsConstructor
public class BackendValidationNode implements WorkerNode {

    private final BuildToolService buildTool;
    private final ErrorFixAgent errorFixAgent;

    @Override
    public void execute(WorkerContext ctx) {
        BuildResult initial = buildTool.runMvnCompile(ctx.getWorkspaceDir().resolve("backend"));

        if (initial.success()) {
            log.info("[BackendValidationNode] mvn compile passed — no fixes needed");
            markFilesValidated(ctx);
            return;
        }

        log.warn("[BackendValidationNode] mvn compile failed — starting ErrorFixAgent loop");
        boolean fixed = errorFixAgent.fix(FileType.BACKEND, ctx);

        if (!fixed) throw new WorkerException(FailureType.CODE,
                "Backend compilation could not be fixed after " + ErrorFixAgent.MAX_TOOL_ROUNDS + " agent tool rounds");

        markFilesValidated(ctx);
    }

    private void markFilesValidated(WorkerContext ctx) {
        try {
            ArchitectureJsonUtil.markAllByTypeAsValidated(ctx.getWorkspaceDir(), "BACKEND");
            log.info("[BackendValidationNode] Marked backend files as VALIDATED in ARCHITECTURE.json");
        } catch (IOException e) {
            log.warn("[BackendValidationNode] Could not update ARCHITECTURE.json: {}", e.getMessage());
        }
    }
}
