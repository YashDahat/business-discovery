package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.model.GeneratedFile;
import com.business.discovery.worker.repository.GeneratedFileRepository;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.business.discovery.worker.util.ArchitectureJsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Single-file fix helper — used by generator nodes (BackendGeneratorNode, FrontendGeneratorNode)
 * for inline per-file compile fixes during generation.
 *
 * ValidationNodes (BackendValidationNode, FrontendValidationNode) now use ErrorFixAgent
 * instead, which runs a full agentic loop owning its own compile-fix cycle.
 *
 * Not a Spring bean (@Component removed) — instantiated directly by tests and not needed
 * in the Spring context since ValidationNodes no longer inject it.
 */
@Slf4j
public class ErrorFixNode {

    // Targeted context cap — much tighter than the general 200K file-generation budget.
    // Error fixing only needs ARCHITECTURE.json + a few related files, not the whole codebase.
    private static final int ERROR_CONTEXT_MAX_CHARS = 30_000;

    private static final Pattern MAVEN_FILE = Pattern.compile(
            "\\[ERROR\\] (.+\\.java):\\[\\d+,\\d+\\]");
    private static final Pattern NPM_FILE = Pattern.compile(
            "(?:ERROR in |error TS\\d+: .+\\n)([^\\n]+\\.tsx?)");
    private static final Pattern TSC_FILE = Pattern.compile(
            "([^\\s]+\\.tsx?)\\(\\d+,\\d+\\):");

    private final LlmGeneratorService llm;
    private final GeneratedFileRepository fileRepo;

    public ErrorFixNode(@Qualifier("claudeSonnet") LlmGeneratorService llm,
                        GeneratedFileRepository fileRepo) {
        this.llm = llm;
        this.fileRepo = fileRepo;
    }

    public boolean fix(String errorOutput, FileType fileType, WorkerContext ctx) {
        Optional<String> maybeAbs = parseAbsolutePath(errorOutput, fileType);
        if (maybeAbs.isEmpty()) {
            log.warn("[ErrorFixNode] Could not parse failing file from error output");
            return false;
        }

        Path absPath = Path.of(maybeAbs.get());
        Path workspace = ctx.getWorkspaceDir();
        String relPath;
        try {
            relPath = workspace.relativize(absPath).toString();
        } catch (IllegalArgumentException e) {
            relPath = maybeAbs.get().replace(workspace.toString() + "/", "");
        }

        if (!Files.exists(absPath)) {
            log.warn("[ErrorFixNode] File not found: {}", absPath);
            return false;
        }

        try {
            String currentContent = Files.readString(absPath);
            String trimmedError = trimError(errorOutput);
            Map<String, String> context = buildTargetedContext(workspace, relPath);

            log.warn("[ErrorFixNode] Fixing {} — error:\n{}", relPath, trimmedError);
            log.info("[ErrorFixNode] Fix context: {} files ({} chars)",
                    context.size(), context.values().stream().mapToInt(String::length).sum());

            String fixedContent = llm.fixFileContent(relPath, currentContent, trimmedError, context);

            if (fixedContent == null || fixedContent.isBlank()) {
                log.warn("[ErrorFixNode] LLM returned empty fix for {}", relPath);
                return false;
            }

            Files.writeString(absPath, fixedContent);
            markFailed(relPath, errorOutput, ctx);

            log.info("[ErrorFixNode] Fixed {}", relPath);
            return true;

        } catch (WorkerException e) {
            throw e;
        } catch (IOException e) {
            throw new WorkerException(FailureType.INFRA,
                    "Failed during error fix for " + relPath + ": " + e.getMessage(), e);
        }
    }

    // ── Context builder ──────────────────────────────────────────────────

    // Builds a targeted context: ARCHITECTURE.json + files the failing file depends on.
    // Falls back to same-directory files if ARCHITECTURE.json has no dependsOn for this file.
    // Capped at ERROR_CONTEXT_MAX_CHARS to keep the fix prompt lean.
    private Map<String, String> buildTargetedContext(Path workspace, String failingFilePath) {
        Map<String, String> ctx = new LinkedHashMap<>();
        int budget = ERROR_CONTEXT_MAX_CHARS;

        Path archPath = workspace.resolve(ArchitectureJsonUtil.ARCH_PATH);
        if (Files.exists(archPath)) {
            try {
                String arch = Files.readString(archPath);
                ctx.put(ArchitectureJsonUtil.ARCH_PATH, arch);
                budget -= arch.length();
            } catch (IOException ignored) {}
        }

        Set<String> deps = getDepsFromSpec(workspace, failingFilePath);
        if (deps.isEmpty()) {
            deps = getSameDirectoryFiles(workspace, failingFilePath);
        }

        for (String dep : deps) {
            if (budget <= 0) break;
            Path file = workspace.resolve(dep);
            if (!Files.exists(file)) continue;
            try {
                String content = Files.readString(file);
                if (content.length() > budget) {
                    content = content.substring(0, budget) + "\n// [truncated]";
                }
                ctx.put(dep, content);
                budget -= content.length();
            } catch (IOException ignored) {}
        }

        return ctx;
    }

    private Set<String> getDepsFromSpec(Path workspace, String failingFilePath) {
        if (!ArchitectureJsonUtil.exists(workspace)) return Set.of();
        try {
            return ArchitectureJsonUtil.findByPath(workspace, failingFilePath)
                    .map(spec -> {
                        Set<String> deps = new LinkedHashSet<>();
                        if (spec.getDependsOn() != null) deps.addAll(spec.getDependsOn());
                        if (spec.getImportsFrom() != null) deps.addAll(spec.getImportsFrom());
                        return deps;
                    })
                    .orElse(Set.of());
        } catch (IOException e) {
            return Set.of();
        }
    }

    private Set<String> getSameDirectoryFiles(Path workspace, String failingFilePath) {
        Path dir = workspace.resolve(failingFilePath).getParent();
        if (dir == null || !Files.exists(dir)) return Set.of();
        try {
            return Files.list(dir)
                    .filter(p -> !workspace.relativize(p).toString().equals(failingFilePath))
                    .map(p -> workspace.relativize(p).toString())
                    .limit(5)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (IOException e) {
            return Set.of();
        }
    }

    // ── Error trimmer ────────────────────────────────────────────────────

    // Maven and npm can dump 50+ cascading errors. Fixing the first error usually fixes the rest.
    // Take the first 30 lines — enough to capture the root cause without noise.
    private String trimError(String errorOutput) {
        String trimmed = errorOutput.lines().limit(30).collect(Collectors.joining("\n"));
        return trimmed.length() > 2000 ? trimmed.substring(0, 2000) : trimmed;
    }

    // ── Other helpers ────────────────────────────────────────────────────

    private Optional<String> parseAbsolutePath(String errorOutput, FileType fileType) {
        if (fileType == FileType.BACKEND) {
            Matcher m = MAVEN_FILE.matcher(errorOutput);
            if (m.find()) return Optional.of(m.group(1));
        } else {
            Matcher m = NPM_FILE.matcher(errorOutput);
            if (m.find()) return Optional.of(m.group(1));
            m = TSC_FILE.matcher(errorOutput);
            if (m.find()) return Optional.of(m.group(1));
        }
        return Optional.empty();
    }

    private void markFailed(String relPath, String errorOutput, WorkerContext ctx) {
        fileRepo.findByTaskIdAndFilePath(ctx.getTaskId(), relPath).ifPresent(f -> {
            f.setStatus(GeneratedFile.FileStatus.FAILED);
            f.setErrorMessage(errorOutput.substring(0, Math.min(2000, errorOutput.length())));
            fileRepo.save(f);
        });
    }
}
