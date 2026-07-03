package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fixes broken @/ alias and relative import paths in generated TypeScript/TSX files
 * by looking up the correct location in the TypeScriptExportRegistry.
 *
 * Runs after each file is generated, before TypeScriptImportChecker.check().
 * Zero LLM — purely structural fix using the registry's symbol-to-path map.
 */
@Slf4j
public final class TypeScriptImportFixer {

    private static final Pattern IMPORT_FROM = Pattern.compile(
            "^(import\\b[^'\"]*from\\s+)['\"]([^'\"]+)['\"]",
            Pattern.MULTILINE);
    private static final Pattern NAMED_SYMBOLS = Pattern.compile("\\{([^}]+)}");

    private TypeScriptImportFixer() {}

    public static void fix(Path filePath, Path workspace, TypeScriptExportRegistry registry) {
        try {
            String content = Files.readString(filePath);
            String fixed = fixContent(content, filePath, workspace, registry, filePath.getFileName().toString());
            if (!fixed.equals(content)) {
                Files.writeString(filePath, fixed);
            }
        } catch (IOException e) {
            log.warn("[TypeScriptImportFixer] Could not fix imports in {}: {}", filePath, e.getMessage());
        }
    }

    // Known wrong-path → correct-path rewrites for @/ alias imports.
    // These happen when shadcn deprecated a location (toast moved out of components/ui)
    // or the LLM assumes a path that doesn't match the generated project structure.
    private static final Map<String, String> KNOWN_PATH_REWRITES = Map.of(
            "@/components/ui/use-toast", "@/hooks/use-toast"
    );

    static String fixContent(String content, Path filePath, Path workspace,
                             TypeScriptExportRegistry registry, String fileName) {
        // Apply known path rewrites before the registry-based resolution
        for (Map.Entry<String, String> rewrite : KNOWN_PATH_REWRITES.entrySet()) {
            String wrong = rewrite.getKey();
            String correct = rewrite.getValue();
            Path correctPath = workspace.resolve("frontend/src")
                    .resolve(correct.substring(2)); // strip @/
            if (fileExists(correctPath)) {
                content = content
                        .replace("from '" + wrong + "'", "from '" + correct + "'")
                        .replace("from \"" + wrong + "\"", "from \"" + correct + "\"");
            }
        }

        Matcher m = IMPORT_FROM.matcher(content);
        List<String[]> replacements = new ArrayList<>();

        while (m.find()) {
            String prefix = m.group(1);
            String specifier = m.group(2);

            if (!specifier.startsWith("@/") && !specifier.startsWith("./") && !specifier.startsWith("../")) {
                continue; // skip node_modules imports
            }

            // Resolve the specifier to a filesystem path
            Path resolvedBase;
            if (specifier.startsWith("@/")) {
                resolvedBase = workspace.resolve("frontend/src").resolve(specifier.substring(2));
            } else {
                resolvedBase = filePath.getParent().resolve(specifier).normalize();
            }

            if (fileExists(resolvedBase)) continue; // already correct

            // File doesn't exist — look up the imported symbol(s) in the registry
            Matcher nm = NAMED_SYMBOLS.matcher(m.group(0));
            if (!nm.find()) continue;

            String correctedSpecifier = null;
            for (String part : nm.group(1).split(",")) {
                String symbol = part.trim().split("\\s+as\\s+")[0].trim().replaceAll("[{}]", "").trim();
                if (symbol.isEmpty() || !registry.knows(symbol)) continue;

                String regPath = registry.resolveSpecifier(symbol).orElse(null);
                if (regPath == null) continue;

                if (regPath.startsWith("frontend/src/")) {
                    correctedSpecifier = "@/" + regPath.substring("frontend/src/".length());
                } else {
                    Path targetAbs = workspace.resolve(regPath);
                    String rel = filePath.getParent().relativize(targetAbs).toString().replace('\\', '/');
                    correctedSpecifier = rel.startsWith("../") ? rel : "./" + rel;
                }
                break;
            }

            if (correctedSpecifier != null && !correctedSpecifier.equals(specifier)) {
                String oldLine = m.group(0);
                String newLine = prefix + "'" + correctedSpecifier + "'";
                replacements.add(new String[]{oldLine, newLine});
                log.info("[TypeScriptImportFixer] Fix import in {}: '{}' → '{}'",
                        fileName, specifier, correctedSpecifier);
            }
        }

        if (replacements.isEmpty()) return content;
        String result = content;
        for (String[] rep : replacements) result = result.replace(rep[0], rep[1]);
        return result;
    }

    private static boolean fileExists(Path base) {
        if (Files.exists(base)) return true;
        for (String ext : List.of(".ts", ".tsx", ".js", ".jsx")) {
            if (Files.exists(Path.of(base + ext))) return true;
        }
        for (String idx : List.of("/index.ts", "/index.tsx")) {
            if (Files.exists(Path.of(base + idx))) return true;
        }
        return false;
    }
}
