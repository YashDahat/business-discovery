package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives the correct Java package from a file's physical path and rewrites
 * the package declaration if it doesn't match — the same fix IntelliJ offers
 * as "Change package declaration to X".
 *
 * Rule: everything between src/main/java/ and the filename, with '/' → '.'
 *   backend/src/main/java/com/foo/model/Role.java → com.foo.model
 *
 * Applied after each file is generated (between Stage 3 and Stage 1) so the
 * compiler never sees a declaration/path mismatch.
 */
@Slf4j
public final class JavaPackageSanitizer {

    private static final Pattern PACKAGE_DECL =
            Pattern.compile("^(package\\s+)([\\w.]+)(\\s*;)", Pattern.MULTILINE);

    private static final String SRC_MARKER = "src/main/java/";

    private JavaPackageSanitizer() {}

    /**
     * Rewrites the package declaration in {@code filePath} if it doesn't match
     * the directory structure. No-op if the file doesn't contain a package statement
     * or if the path doesn't contain {@code src/main/java/}.
     *
     * @param filePath absolute path to the .java file
     */
    public static void sanitize(Path filePath) throws IOException {
        String expected = expectedPackage(filePath);
        if (expected == null) return;

        String content = Files.readString(filePath);
        Matcher m = PACKAGE_DECL.matcher(content);
        if (!m.find()) return;

        String declared = m.group(2);
        if (declared.equals(expected)) return;

        String fixed = m.replaceFirst(m.group(1) + expected + m.group(3));
        Files.writeString(filePath, fixed);
        log.info("[JavaPackageSanitizer] Fixed package in {}: '{}' → '{}'",
                filePath.getFileName(), declared, expected);
    }

    static String expectedPackage(Path filePath) {
        String normalized = filePath.toString().replace('\\', '/');
        int idx = normalized.indexOf(SRC_MARKER);
        if (idx < 0) return null;

        String after = normalized.substring(idx + SRC_MARKER.length());
        int lastSlash = after.lastIndexOf('/');
        if (lastSlash <= 0) return null;

        return after.substring(0, lastSlash).replace('/', '.');
    }
}
