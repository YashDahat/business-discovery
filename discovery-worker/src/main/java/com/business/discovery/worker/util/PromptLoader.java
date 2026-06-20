package com.business.discovery.worker.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads prompt text files from the prompts/ directory.
 *
 * Resolution order:
 *   1. PROMPTS_DIR env var (filesystem)
 *   2. ./prompts relative to working directory (local dev)
 *   3. classpath:/prompts/ (bundled in JAR for Docker)
 *
 * Files are cached after first load — restart the container to pick up edits.
 */
public final class PromptLoader {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private static final Path BASE_DIR = resolveBaseDir();

    private PromptLoader() {}

    public static String load(String relativeName) {
        return CACHE.computeIfAbsent(relativeName, name -> {
            Path file = BASE_DIR.resolve(name);
            if (Files.exists(file)) {
                try {
                    return Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to read prompt: " + file, e);
                }
            }
            // Fallback: load from classpath (bundled in JAR)
            String classpathPath = "/prompts/" + name;
            try (InputStream is = PromptLoader.class.getResourceAsStream(classpathPath)) {
                if (is != null) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read classpath prompt: " + classpathPath, e);
            }
            throw new IllegalStateException(
                    "Prompt file not found: " + file.toAbsolutePath()
                    + " or classpath:" + classpathPath
                    + " — set PROMPTS_DIR or ensure prompts/ is at the working directory");
        });
    }

    private static Path resolveBaseDir() {
        String envDir = System.getenv("PROMPTS_DIR");
        return Paths.get(envDir != null ? envDir : "prompts");
    }
}
