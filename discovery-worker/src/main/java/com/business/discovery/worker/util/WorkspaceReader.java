package com.business.discovery.worker.util;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.errorhandler.WorkerException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Read-only view of the workspace directory for use by ProjectPlanningNode during
 * spec enrichment. Generator nodes do NOT use this — they receive pre-determined
 * dependency file contents from the spec's imports_from list.
 */
@Slf4j
public class WorkspaceReader {

    private static final List<String> EXCLUDED_DIRS = List.of(
            "node_modules", "target", ".git", "dist", ".mvn"
    );

    private final Path workspace;

    public WorkspaceReader(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    /**
     * Reads the content of a file relative to the workspace root.
     *
     * @return file content, or "FILE_NOT_FOUND: {path}" if the file does not exist
     * @throws WorkerException(INFRA) on path traversal attempt or IO error
     */
    public String readFile(String relativePath) {
        Path resolved = resolve(relativePath);

        if (!Files.exists(resolved)) {
            log.debug("[WorkspaceReader] File not found: {}", relativePath);
            return "FILE_NOT_FOUND: " + relativePath;
        }

        try {
            return Files.readString(resolved);
        } catch (IOException e) {
            throw new WorkerException(FailureType.INFRA,
                    "Failed to read workspace file " + relativePath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Returns all file paths on disk relative to the workspace root,
     * excluding build artefact directories.
     */
    public List<String> listExistingFiles() {
        if (!Files.exists(workspace)) return List.of();
        try (Stream<Path> walk = Files.walk(workspace)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> !isExcluded(p))
                    .map(p -> workspace.relativize(p).toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("[WorkspaceReader] Could not list workspace files: {}", e.getMessage());
            return List.of();
        }
    }

    public boolean exists(String relativePath) {
        try {
            return Files.exists(resolve(relativePath));
        } catch (WorkerException e) {
            return false;
        }
    }

    // ── Private ───────────────────────────────────────────────────────────

    private Path resolve(String relativePath) {
        Path resolved = workspace.resolve(relativePath).normalize();
        if (!resolved.startsWith(workspace)) {
            throw new WorkerException(FailureType.INFRA,
                    "Path traversal attempt blocked: " + relativePath);
        }
        return resolved;
    }

    private boolean isExcluded(Path path) {
        for (Path part : workspace.relativize(path)) {
            if (EXCLUDED_DIRS.contains(part.toString())) return true;
        }
        return false;
    }
}
