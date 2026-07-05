package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.service.BuildToolService;
import com.business.discovery.worker.service.BuildToolService.BuildResult;
import com.business.discovery.worker.util.ArchitectureJsonUtil;
import com.business.discovery.worker.util.EnvVarScanner;
import com.business.discovery.worker.util.MavenDependencyInjector;
import com.business.discovery.worker.util.RepositoryMethodInjector;
import com.business.discovery.worker.util.RolePrefixPatcher;
import com.business.discovery.worker.util.SecurityConfigPatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

@Component
@Order(11)
@Slf4j
@RequiredArgsConstructor
public class BackendValidationNode implements WorkerNode {

    private final BuildToolService buildTool;
    private final ErrorFixAgent errorFixAgent;

    @Override
    public void execute(WorkerContext ctx) {
        Path backendDir = ctx.getWorkspaceDir().resolve("backend");
        Path backendSrcJava = backendDir.resolve("src/main/java");

        // Unconditional runtime-correctness patch — must run whether or not the code compiles,
        // because a ROLE_/hasRole mismatch compiles fine but 403s every admin endpoint at runtime.
        RolePrefixPatcher.fix(backendSrcJava);

        BuildResult initial = buildTool.runMvnCompile(backendDir);

        if (initial.success()) {
            log.info("[BackendValidationNode] mvn compile passed — no fixes needed");
            markFilesValidated(ctx);
            return;
        }

        // Pre-ErrorFixAgent mechanical fixes:
        //   1. SecurityConfigPatcher: ensures PasswordEncoder, AuthenticationManager, static asset
        //      permits and anyRequest().permitAll() are present — structural gaps the LLM misses
        //   2. MavenDependencyInjector: adds missing classpath jars (ErrorFixAgent can't fix these)
        //   3. RepositoryMethodInjector: adds missing Spring Data findBy* declarations that the
        //      SERVICE layer called but the REPOSITORY template didn't generate (cross-file gap)
        boolean securityPatched = SecurityConfigPatcher.patch(backendSrcJava);
        boolean depsInjected = MavenDependencyInjector.injectFromCompileErrors(
                backendDir.resolve("pom.xml"), initial.output());
        boolean methodsInjected = RepositoryMethodInjector.injectMissingMethods(
                backendDir, initial.output());

        if (securityPatched || depsInjected || methodsInjected) {
            BuildResult postInjection = buildTool.runMvnCompile(backendDir);
            if (postInjection.success()) {
                log.info("[BackendValidationNode] mvn compile passed after mechanical injection — skipping ErrorFixAgent");
                rescanValueBindings(ctx);
                markFilesValidated(ctx);
                return;
            }
            log.info("[BackendValidationNode] Mechanical injection incomplete — handing off remaining errors to ErrorFixAgent");
        } else {
            log.warn("[BackendValidationNode] mvn compile failed — starting ErrorFixAgent loop");
        }

        boolean fixed = errorFixAgent.fix(FileType.BACKEND, ctx);

        if (!fixed) throw new WorkerException(FailureType.CODE,
                "Backend compilation could not be fixed after " + ErrorFixAgent.MAX_TOOL_ROUNDS + " agent tool rounds");

        // ErrorFixAgent may have rewritten Java files and introduced new @Value keys —
        // re-scan so those keys land in application.properties before Spring Boot starts.
        rescanValueBindings(ctx);

        markFilesValidated(ctx);
    }

    private void rescanValueBindings(WorkerContext ctx) {
        Path workspace = ctx.getWorkspaceDir();
        Path backendSrc = workspace.resolve("backend/src/main/java");
        Path propsFile  = workspace.resolve("backend/src/main/resources/application.properties");
        Set<String> valueKeys = EnvVarScanner.scanJavaFiles(backendSrc);
        EnvVarScanner.augmentApplicationProperties(propsFile, valueKeys);
        EnvVarScanner.augmentDotEnvExample(workspace, valueKeys);
        log.info("[BackendValidationNode] @Value re-scan complete after ErrorFixAgent");
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
