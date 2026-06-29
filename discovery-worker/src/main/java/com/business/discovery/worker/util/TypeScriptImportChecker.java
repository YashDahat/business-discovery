package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans import specifiers in a generated TypeScript file and warns about any
 * that resolve to a path that neither exists on disk nor appears in the
 * generation manifest (i.e., won't be generated later either).
 *
 * Analogous to JavaPackageSanitizer for the frontend: detects broken imports
 * before tsc runs so the first error message is meaningful rather than buried
 * in a cascade of "Cannot find module" errors.
 *
 * Handles:
 *   - Relative imports   (./foo, ../bar)       → resolved against the file's directory
 *   - @/ alias imports   (@/services/foo)       → resolved to frontend/src/
 *   - npm packages       (react, date-fns, etc) → checked against frontend/node_modules/
 *
 * Tries candidate extensions in order: bare, .ts, .tsx, /index.ts, /index.tsx
 */
@Slf4j
public final class TypeScriptImportChecker {

    private static final Pattern TS_IMPORT =
            Pattern.compile("from\\s+['\"]([^'\"]+)['\"]", Pattern.MULTILINE);

    private static final List<String> EXTENSIONS =
            List.of("", ".ts", ".tsx", "/index.ts", "/index.tsx");

    private TypeScriptImportChecker() {}

    /**
     * @param filePath      absolute path to the generated .ts/.tsx file
     * @param workspace     project workspace root
     * @param manifestPaths relative paths (workspace-relative) of every file that
     *                      will be generated in this run — a path here is "pending",
     *                      not missing
     */
    public static void check(Path filePath, Path workspace, Set<String> manifestPaths) {
        String content;
        try {
            content = Files.readString(filePath);
        } catch (IOException e) {
            return;
        }

        String relFile = workspace.relativize(filePath).toString().replace('\\', '/');
        Path fileDir = filePath.getParent();
        Path nodeModules = workspace.resolve("frontend/node_modules");

        List<String> unresolvable = new ArrayList<>();
        List<String> missingPackages = new ArrayList<>();

        Matcher m = TS_IMPORT.matcher(content);
        while (m.find()) {
            String specifier = m.group(1);
            String base;

            if (specifier.startsWith("./") || specifier.startsWith("../")) {
                Path resolved = fileDir.resolve(specifier).normalize();
                base = workspace.relativize(resolved).toString().replace('\\', '/');
                if (!resolves(base, workspace, manifestPaths)) {
                    unresolvable.add(specifier);
                }
            } else if (specifier.startsWith("@/")) {
                base = "frontend/src/" + specifier.substring(2);
                if (!resolves(base, workspace, manifestPaths)) {
                    unresolvable.add(specifier);
                }
            } else {
                // npm package — extract package name and check node_modules
                String pkg = packageName(specifier);
                if (!Files.exists(nodeModules.resolve(pkg))) {
                    missingPackages.add(pkg);
                }
            }
        }

        if (!unresolvable.isEmpty()) {
            log.warn("[TypeScriptImportChecker] {} — {} import(s) resolve to no file on disk or in manifest: {}",
                    relFile, unresolvable.size(), unresolvable);
        }
        if (!missingPackages.isEmpty()) {
            log.warn("[TypeScriptImportChecker] {} — {} npm package(s) not in node_modules: {}",
                    relFile, missingPackages.size(), missingPackages);
        }
    }

    // Extracts the installable package name from an import specifier.
    // Scoped packages (@org/pkg/deep) → "@org/pkg"
    // Plain packages  (date-fns/format) → "date-fns"
    static String packageName(String specifier) {
        if (specifier.startsWith("@")) {
            String[] parts = specifier.split("/");
            return parts.length >= 2 ? parts[0] + "/" + parts[1] : specifier;
        }
        int slash = specifier.indexOf('/');
        return slash < 0 ? specifier : specifier.substring(0, slash);
    }

    private static boolean resolves(String base, Path workspace, Set<String> manifest) {
        for (String ext : EXTENSIONS) {
            String candidate = base + ext;
            if (Files.exists(workspace.resolve(candidate))) return true;
            if (manifest.contains(candidate)) return true;
        }
        return false;
    }
}
