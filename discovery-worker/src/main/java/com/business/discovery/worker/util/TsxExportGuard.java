package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Detects .tsx files where the LLM produced a raw JSX fragment with no component wrapper
 * (no export default, no export function, no export const).
 *
 * Root cause: LLM sometimes outputs only the JSX body (e.g. the <footer>...</footer> block)
 * without the surrounding function declaration and export, producing a file that TypeScript
 * rejects with "Module has no default export."
 *
 * Fix: derive the component name from the filename, wrap the content in:
 *   export default function ComponentName(): JSX.Element { return (<content>); }
 *
 * Runs before ErrorFixAgent in FrontendValidationNode. Without this fix, the agent spends
 * 3-4 rounds just adding the wrapper that could be added deterministically in milliseconds.
 */
@Slf4j
public final class TsxExportGuard {

    private TsxExportGuard() {}

    private static final Pattern HAS_DEFAULT_EXPORT = Pattern.compile(
            "export\\s+default\\s+", Pattern.MULTILINE);
    private static final Pattern HAS_NAMED_EXPORT = Pattern.compile(
            "export\\s+(function|const|class)\\s+", Pattern.MULTILINE);

    /**
     * Walks all .tsx files under frontendSrcDir and wraps any that have no export.
     * Returns true if at least one file was fixed.
     */
    public static boolean fix(Path frontendSrcDir) {
        if (!Files.exists(frontendSrcDir)) return false;
        boolean[] anyFixed = {false};
        try {
            Files.walk(frontendSrcDir)
                    .filter(p -> p.toString().endsWith(".tsx"))
                    .forEach(p -> {
                        try {
                            if (fixFile(p)) anyFixed[0] = true;
                        } catch (IOException e) {
                            log.warn("[TsxExportGuard] Could not fix {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("[TsxExportGuard] Failed to walk {}: {}", frontendSrcDir, e.getMessage());
        }
        return anyFixed[0];
    }

    private static boolean fixFile(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        if (content.isBlank()) return false;

        // Already has an export — fine
        if (HAS_DEFAULT_EXPORT.matcher(content).find()
                || HAS_NAMED_EXPORT.matcher(content).find()) {
            return false;
        }

        // No export at all — check it looks like JSX (starts with < after trimming)
        String trimmed = content.stripLeading();
        if (!trimmed.startsWith("<") && !trimmed.startsWith("(")) {
            return false; // Not raw JSX — leave for ErrorFixAgent
        }

        String componentName = deriveComponentName(filePath);
        String wrapped = wrapInComponent(content, componentName);
        Files.writeString(filePath, wrapped);
        log.info("[TsxExportGuard] Wrapped raw JSX in export default function {} in {}",
                componentName, filePath.getFileName());
        return true;
    }

    private static String deriveComponentName(Path filePath) {
        String name = filePath.getFileName().toString().replace(".tsx", "");
        // Ensure PascalCase — capitalize first letter
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String wrapInComponent(String rawJsx, String componentName) {
        // Indent the raw JSX by 4 spaces for readability inside the return
        String indented = rawJsx.lines()
                .map(line -> "    " + line)
                .reduce("", (a, b) -> a + "\n" + b)
                .stripLeading(); // remove leading blank from the join

        return """
                import React from 'react';

                export default function %s(): JSX.Element {
                  return (
                    %s
                  );
                }
                """.formatted(componentName, indented.stripTrailing());
    }
}
