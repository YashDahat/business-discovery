package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fixes common javax → jakarta import mistakes in generated Java files.
 *
 * Spring Boot 3.x uses Jakarta EE 9+ which renamed all javax.* packages to
 * jakarta.*. The Flash LLM is trained on older code and often writes the wrong
 * namespace. This runs deterministically after generation, before compilation.
 *
 * Handles:
 *   javax.persistence.*  → jakarta.persistence.*
 *   javax.validation.*   → jakarta.validation.*
 *   javax.servlet.*      → jakarta.servlet.*
 *   javax.transaction.*  → jakarta.transaction.*
 *
 * Also removes duplicate import lines that may result from fix attempts.
 */
@Slf4j
public final class JavaImportSanitizer {

    private static final Pattern IMPORT_LINE =
            Pattern.compile("^(import\\s+)(javax)(\\.(persistence|validation|servlet|transaction)[^;]*;)$",
                    Pattern.MULTILINE);

    private JavaImportSanitizer() {}

    public static void sanitize(Path filePath) throws IOException {
        String content = Files.readString(filePath);

        Matcher m = IMPORT_LINE.matcher(content);
        if (!m.find()) return;

        // Replace javax → jakarta for the four migrated namespaces
        String replaced = IMPORT_LINE.matcher(content).replaceAll("$1jakarta$3");

        // Deduplicate import lines (fix attempts may have added jakarta imports alongside javax ones)
        replaced = deduplicateImports(replaced);

        if (!replaced.equals(content)) {
            Files.writeString(filePath, replaced);
            log.info("[JavaImportSanitizer] Fixed javax→jakarta imports in {}", filePath.getFileName());
        }
    }

    private static String deduplicateImports(String source) {
        String[] lines = source.split("\n", -1);
        List<String> result = new ArrayList<>(lines.length);
        Set<String> seenImports = new LinkedHashSet<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("import ") && trimmed.endsWith(";")) {
                if (!seenImports.add(trimmed)) {
                    continue; // skip duplicate
                }
            }
            result.add(line);
        }

        return String.join("\n", result);
    }
}
