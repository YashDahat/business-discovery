package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.model.GeneratedFile;
import com.business.discovery.worker.repository.GeneratedFileRepository;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import dev.langchain4j.web.search.WebSearchEngine;
import dev.langchain4j.web.search.WebSearchRequest;
import dev.langchain4j.web.search.WebSearchResults;
import dev.langchain4j.web.search.WebSearchOrganicResult;

import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper component — called by BackendValidationNode and FrontendValidationNode on failure.
 * Not a WorkerNode; not executed by the orchestrator directly.
 *
 * Flow: parse failing file → Tavily search → LLM fix → overwrite file → mark FAILED in DB.
 * Returns true if a fix was applied; false if parsing failed or LLM returned nothing.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ErrorFixNode {

    private static final Pattern MAVEN_FILE = Pattern.compile(
            "\\[ERROR\\] (.+\\.java):\\[\\d+,\\d+\\]");
    private static final Pattern NPM_FILE = Pattern.compile(
            "(?:ERROR in |error TS\\d+: .+\\n)([^\\n]+\\.tsx?)");
    private static final Pattern TSC_FILE = Pattern.compile(
            "([^\\s]+\\.tsx?)\\(\\d+,\\d+\\):");

    private final LlmGeneratorService llm;
    private final GeneratedFileRepository fileRepo;
    private final WebSearchEngine webSearch;

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
            // path wasn't under workspace — use the raw parse result stripped of workspace prefix
            relPath = maybeAbs.get().replace(workspace.toString() + "/", "");
        }

        if (!Files.exists(absPath)) {
            log.warn("[ErrorFixNode] File not found: {}", absPath);
            return false;
        }

        try {
            String currentContent = Files.readString(absPath);
            String searchResult = search(buildSearchQuery(errorOutput));
            String fixedContent = llm.fixFileContent(relPath, currentContent, errorOutput, searchResult);

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

    // ── Private helpers ──────────────────────────────────────────────────

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

    private String buildSearchQuery(String errorOutput) {
        // Extract the first meaningful error line (skip [INFO]/[WARNING] Maven noise)
        return errorOutput.lines()
                .filter(l -> l.contains("error") || l.contains("ERROR") || l.contains("TS"))
                .findFirst()
                .map(l -> l.replaceAll("\\[ERROR\\]\\s*", "").trim())
                .orElse(errorOutput.substring(0, Math.min(200, errorOutput.length())));
    }

    private String search(String query) {
        try {
            WebSearchResults results = webSearch.search(
                    WebSearchRequest.builder().searchTerms(query).build());
            return results.results().stream()
                    .map(r -> "Source: %s\n%s".formatted(r.url(), r.snippet()))
                    .collect(Collectors.joining("\n\n---\n\n"));
        } catch (Exception e) {
            log.warn("[ErrorFixNode] Tavily search failed: {}", e.getMessage());
            return "Search unavailable.";
        }
    }

    private void markFailed(String relPath, String errorOutput, WorkerContext ctx) {
        fileRepo.findByTaskIdAndFilePath(ctx.getTaskId(), relPath).ifPresent(f -> {
            f.setStatus(GeneratedFile.FileStatus.FAILED);
            f.setErrorMessage(errorOutput.substring(0, Math.min(2000, errorOutput.length())));
            fileRepo.save(f);
        });
    }
}
