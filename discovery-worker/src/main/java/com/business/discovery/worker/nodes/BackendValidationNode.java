package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.service.BuildToolService;
import com.business.discovery.worker.service.BuildToolService.BuildResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(9)
@Slf4j
@RequiredArgsConstructor
public class BackendValidationNode implements WorkerNode {

    private static final int MAX_RETRIES = 3;

    private final BuildToolService buildTool;
    private final ErrorFixNode errorFix;

    @Override
    public void execute(WorkerContext ctx) {
        String lastError = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            BuildResult result = buildTool.runMvnCompile(ctx.getWorkspaceDir().resolve("backend"));

            if (result.success()) {
                log.info("[BackendValidationNode] mvn compile passed on attempt {}", attempt);
                return;
            }

            lastError = result.output();
            log.warn("[BackendValidationNode] mvn compile failed (attempt {})", attempt);

            if (attempt < MAX_RETRIES) {
                boolean fixed = errorFix.fix(lastError, FileType.BACKEND, ctx);
                if (!fixed) {
                    log.warn("[BackendValidationNode] ErrorFixNode could not apply a fix — stopping retries");
                    break;
                }
            }
        }

        throw new WorkerException(FailureType.CODE,
                "Backend compilation failed after " + MAX_RETRIES + " attempts:\n" + lastError);
    }
}
