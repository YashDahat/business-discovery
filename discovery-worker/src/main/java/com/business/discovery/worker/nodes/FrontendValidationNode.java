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

import java.nio.file.Path;

@Component
@Order(10)
@Slf4j
@RequiredArgsConstructor
public class FrontendValidationNode implements WorkerNode {

    private static final int MAX_RETRIES = 3;

    private final BuildToolService buildTool;
    private final ErrorFixNode errorFix;

    @Override
    public void execute(WorkerContext ctx) {
        Path frontendDir = ctx.getWorkspaceDir().resolve("frontend");

        // npm install once before any retry loop — only redo if it fails
        BuildResult install = buildTool.runNpmInstall(frontendDir);
        if (!install.success()) {
            throw new WorkerException(FailureType.INFRA,
                    "npm install failed:\n" + install.output());
        }

        String lastError = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            BuildResult result = buildTool.runNpmBuild(frontendDir);

            if (result.success()) {
                log.info("[FrontendValidationNode] npm run build passed on attempt {}", attempt);
                return;
            }

            lastError = result.output();
            log.warn("[FrontendValidationNode] npm run build failed (attempt {})", attempt);

            if (attempt < MAX_RETRIES) {
                boolean fixed = errorFix.fix(lastError, FileType.FRONTEND, ctx);
                if (!fixed) {
                    log.warn("[FrontendValidationNode] ErrorFixNode could not apply a fix — stopping retries");
                    break;
                }
            }
        }

        throw new WorkerException(FailureType.CODE,
                "Frontend build failed after " + MAX_RETRIES + " attempts:\n" + lastError);
    }
}
