package com.business.discovery.worker.service.llm;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public final class CodebaseContextBuilder {

    private static final int MAX_TOTAL_CHARS = 200_000;
    private static final int MAX_LINES_TRUNCATED = 100;

    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            ".java", ".tsx", ".ts", ".jsx", ".js",
            ".html", ".css", ".scss",
            ".yml", ".yaml", ".xml",
            ".json", ".properties", ".sh");

    private static final Set<String> EXCLUDE_DIRS = Set.of(
            "target", "node_modules", ".git", ".idea", "dist", "build", ".mvn");

    private CodebaseContextBuilder() {}

    /**
     * Collect all source files from workspaceDir, excluding currentFilePath (the file being generated).
     * Result is compacted to MAX_TOTAL_CHARS if the total exceeds the budget.
     */
    public static Map<String, String> build(Path workspaceDir, String currentFilePath) {
        if (!Files.exists(workspaceDir)) return Map.of();

        Map<String, String> result = new LinkedHashMap<>();

        try {
            Files.walkFileTree(workspaceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    return EXCLUDE_DIRS.contains(name)
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    int dot = name.lastIndexOf('.');
                    String ext = dot >= 0 ? name.substring(dot) : "";
                    if (!SOURCE_EXTENSIONS.contains(ext)) return FileVisitResult.CONTINUE;

                    String relative = workspaceDir.relativize(file).toString();
                    if (relative.equals(currentFilePath)) return FileVisitResult.CONTINUE;

                    try {
                        result.put(relative, Files.readString(file));
                    } catch (IOException ignored) {}
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}

        return compact(result);
    }

    private static Map<String, String> compact(Map<String, String> files) {
        int total = files.values().stream().mapToInt(String::length).sum();
        if (total <= MAX_TOTAL_CHARS) return files;

        Map<String, String> compacted = new LinkedHashMap<>();
        int remainingBudget = MAX_TOTAL_CHARS;

        // ARCHITECTURE.json is always included in full and added first — it's the authoritative blueprint
        for (Map.Entry<String, String> entry : files.entrySet()) {
            if (entry.getKey().endsWith("ARCHITECTURE.json")) {
                compacted.put(entry.getKey(), entry.getValue());
                remainingBudget -= entry.getValue().length();
                break;
            }
        }

        for (Map.Entry<String, String> entry : files.entrySet()) {
            if (entry.getKey().endsWith("ARCHITECTURE.json")) continue;
            if (remainingBudget <= 0) break;
            String content = entry.getValue();
            String[] lines = content.split("\n", -1);
            if (lines.length > MAX_LINES_TRUNCATED) {
                content = String.join("\n", Arrays.copyOfRange(lines, 0, MAX_LINES_TRUNCATED))
                        + "\n// ... [truncated — " + lines.length + " lines total]";
            }
            compacted.put(entry.getKey(), content);
            remainingBudget -= content.length();
        }
        return compacted;
    }
}
