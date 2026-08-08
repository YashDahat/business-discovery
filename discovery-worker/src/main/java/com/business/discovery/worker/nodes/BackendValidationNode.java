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
import com.business.discovery.worker.util.AuthExceptionHandlerPatcher;
import com.business.discovery.worker.util.BackendClassSynthesizer;
import com.business.discovery.worker.util.MissingBeanPatcher;
import com.business.discovery.worker.util.RepositoryMethodInjector;
import com.business.discovery.worker.util.JpaBidirectionalSavePatcher;
import com.business.discovery.worker.util.JpaBinaryColumnPatcher;
import com.business.discovery.worker.util.JwtCircularDependencyPatcher;
import com.business.discovery.worker.util.PasswordEncoderExtractor;
import com.business.discovery.worker.util.RolePrefixPatcher;
import com.business.discovery.worker.util.SecurityConfigPatcher;
import com.business.discovery.worker.util.UserDetailsServicePatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

@Component
@Order(8)
@Slf4j
@RequiredArgsConstructor
public class BackendValidationNode implements WorkerNode {

    private final BuildToolService buildTool;
    private final ErrorFixAgent errorFixAgent;

    @Override
    public void execute(WorkerContext ctx) {
        Path backendDir = ctx.getWorkspaceDir().resolve("backend");
        Path backendSrcJava = backendDir.resolve("src/main/java");

        // Unconditional runtime-correctness patches — must run whether or not the code compiles,
        // because these defect classes compile clean and only surface at boot/request time.
        RolePrefixPatcher.fix(backendSrcJava);
        JwtCircularDependencyPatcher.fix(backendSrcJava);
        // parent.setChildren(list) + repo.save(parent) without setting the child @ManyToOne back-ref —
        // cascade inserts children with a null FK and the write path 500s (circuit-house order_items)
        JpaBidirectionalSavePatcher.fix(backendSrcJava);
        // MySQL columnDefinition="BINARY(16)" on UUID columns — Postgres has no BINARY type, so
        // ddl-auto fails at boot ('type "binary" does not exist'), the table is never created and the
        // app never goes healthy (MultiFit Aundh smoke-boot death 2026-07-26)
        JpaBinaryColumnPatcher.fix(backendSrcJava);
        // the @Lazy patcher above cannot see the PasswordEncoder cycle (it never passes
        // through JwtAuthFilter) — yeti attempt 4 and circuit-house both shipped it
        PasswordEncoderExtractor.fix(backendSrcJava);
        // injected-but-never-declared infrastructure beans (RestTemplate) — circuit-house
        // attempt 2 compiled clean and then crash-looped on context refresh
        MissingBeanPatcher.fix(backendSrcJava);
        // UserDetailsService injected by SecurityConfig/JwtAuthFilter but never implemented —
        // Vikram's Fitness Studio compiled clean and crash-looped on context refresh; unlike
        // RestTemplate there's no safe no-arg default, so this is wired to the real
        // UserRepository/User/Role shape the project actually generated
        UserDetailsServicePatcher.fix(backendSrcJava);
        // SecurityConfig structural beans + the tiered /api authorization that opens the public
        // catalog to anonymous visitors. Runtime-correctness: a blanket /api/** lockdown compiles
        // clean and 403s the whole storefront (circuit-house 2026-07-17 attempt 4 compiled first
        // try and still shipped it), so this MUST run unconditionally, not only on compile failure.
        SecurityConfigPatcher.patch(backendSrcJava, ctx.getWorkspaceDir());
        // a wrong password must be 401, not 500 — the advice's generic Exception handler otherwise
        // maps the BadCredentialsException thrown inside the login controller to a server error.
        AuthExceptionHandlerPatcher.fix(backendSrcJava);

        BuildResult initial = buildTool.runMvnCompile(backendDir);

        if (initial.success()) {
            log.info("[BackendValidationNode] mvn compile passed — no fixes needed");
            markFilesValidated(ctx);
            return;
        }

        // Pre-ErrorFixAgent mechanical fixes for compile errors (SecurityConfig is already
        // patched unconditionally above):
        //   1. MavenDependencyInjector: adds missing classpath jars (ErrorFixAgent can't fix these)
        //   2. RepositoryMethodInjector: adds missing Spring Data findBy* declarations that the
        //      SERVICE layer called but the REPOSITORY template didn't generate (cross-file gap)
        boolean depsInjected = MavenDependencyInjector.injectFromCompileErrors(
                backendDir.resolve("pom.xml"), initial.output());
        boolean methodsInjected = RepositoryMethodInjector.injectMissingMethods(
                backendDir, initial.output());

        BuildResult latest = initial;
        if (depsInjected || methodsInjected) {
            latest = buildTool.runMvnCompile(backendDir);
            if (latest.success()) {
                log.info("[BackendValidationNode] mvn compile passed after mechanical injection — skipping ErrorFixAgent");
                rescanValueBindings(ctx);
                markFilesValidated(ctx);
                return;
            }
            log.info("[BackendValidationNode] Mechanical injection incomplete — handing off remaining errors to ErrorFixAgent");
        } else {
            log.warn("[BackendValidationNode] mvn compile failed — starting ErrorFixAgent loop");
        }

        // Enforcement Point B (backend gen-time closure): synthesize a minimal placeholder for every
        // "cannot find symbol: class X" where X is a project class that was never generated — the
        // OrderItemResponse case. javac is the detector; this resolves the missing-TYPE error, and any
        // residual member errors (getters/ctors) become a tractable in-file task for the ErrorFixAgent.
        int synthesized = BackendClassSynthesizer.synthesize(backendSrcJava, latest.output());
        if (synthesized > 0) {
            BuildResult postSynth = buildTool.runMvnCompile(backendDir);
            if (postSynth.success()) {
                log.info("[BackendValidationNode] mvn compile passed after synthesizing {} missing class(es) — skipping ErrorFixAgent", synthesized);
                rescanValueBindings(ctx);
                markFilesValidated(ctx);
                return;
            }
            log.info("[BackendValidationNode] Synthesized {} missing class(es) — residual errors to ErrorFixAgent", synthesized);
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
