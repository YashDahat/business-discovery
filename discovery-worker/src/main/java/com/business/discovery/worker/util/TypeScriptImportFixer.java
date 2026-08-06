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
import java.util.stream.Stream;

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

    // Detects `export default` in a TypeScript/TSX file — the file exposes a single default export.
    // Any named import { X } on such a file is wrong: must be `import X from '...'`.
    private static final Pattern DEFAULT_EXPORT_DECL = Pattern.compile(
            "^export\\s+default\\s+", Pattern.MULTILINE);
    // Detects `import { X, Y } from` — named import form.
    private static final Pattern NAMED_IMPORT_FORM = Pattern.compile("import\\s*\\{");
    // Detects `import X from` — pure default import (single identifier, not `type`, not braces,
    // not the `import X, { Y }` mixed form which is followed by a comma rather than `from`).
    private static final Pattern DEFAULT_IMPORT_FORM = Pattern.compile(
            "import\\s+(?!type\\b)([A-Za-z_$][\\w$]*)\\s+from");

    private TypeScriptImportFixer() {}

    /**
     * Runs {@link #fix} across every .ts/.tsx under {@code frontendSrc} (excluding shadcn's own
     * components/ui files). Used as a deterministic pre-pass in FrontendValidationNode, re-applying
     * registry-driven path + default/named correction to the final file set before the ErrorFixAgent.
     *
     * @return true if any file was modified.
     */
    public static boolean fixAll(Path frontendSrc, Path workspace, TypeScriptExportRegistry registry) {
        if (!Files.exists(frontendSrc)) return false;
        boolean[] changed = {false};
        try (Stream<Path> files = Files.walk(frontendSrc)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".tsx") || p.toString().endsWith(".ts"))
                 .filter(p -> !p.toString().contains("/components/ui/"))
                 .forEach(p -> { if (fix(p, workspace, registry)) changed[0] = true; });
        } catch (IOException e) {
            log.warn("[TypeScriptImportFixer] Walk failed for {}: {}", frontendSrc, e.getMessage());
        }
        return changed[0];
    }

    public static boolean fix(Path filePath, Path workspace, TypeScriptExportRegistry registry) {
        try {
            String content = Files.readString(filePath);
            String fixed = fixContent(content, filePath, workspace, registry, filePath.getFileName().toString());
            if (!fixed.equals(content)) {
                Files.writeString(filePath, fixed);
                return true;
            }
        } catch (IOException e) {
            log.warn("[TypeScriptImportFixer] Could not fix imports in {}: {}", filePath, e.getMessage());
        }
        return false;
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

            if (fileExists(resolvedBase)) {
                // File exists — check for a default-vs-named mismatch, the most common structural
                // import error after generation. Read the target once and branch on the import form.
                String importLine = m.group(0);
                Path actualFile = resolveToActualFile(resolvedBase);
                String targetContent = actualFile != null ? readSilent(actualFile) : null;
                boolean targetHasDefault = targetContent != null
                        && DEFAULT_EXPORT_DECL.matcher(targetContent).find();

                if (NAMED_IMPORT_FORM.matcher(importLine).find()) {
                    // `import { Layout } from './Layout'` when Layout.tsx has `export default ...` is
                    // TS2614 ("Did you mean 'import Layout from...'?"). Convert the single named symbol
                    // to a default import.
                    if (targetHasDefault) {
                        Matcher nm2 = NAMED_SYMBOLS.matcher(importLine);
                        if (nm2.find()) {
                            String symbols = nm2.group(1).trim();
                            // Only single-symbol named imports — { A, B } may mix a default re-export
                            // with named; leave those to the agent.
                            if (!symbols.contains(",")) {
                                String sym = symbols.split("\\s+as\\s+")[0].trim();
                                String quote = importLine.contains("'") ? "'" : "\"";
                                String newLine = "import " + sym + " from " + quote + specifier + quote;
                                replacements.add(new String[]{importLine, newLine});
                                log.info("[TypeScriptImportFixer] {}: rewrote named import to default: {{ {} }} → {}",
                                        fileName, sym, sym);
                            }
                        }
                    }
                } else {
                    // `import Layout from './Layout'` when Layout.tsx has NO default export but exports
                    // Layout as a named symbol is TS2613 ("has no default export"). If the registry
                    // confirms the name is a NAMED export, convert to a named import.
                    Matcher dm = DEFAULT_IMPORT_FORM.matcher(importLine);
                    if (dm.find() && targetContent != null && !targetHasDefault) {
                        String name = dm.group(1);
                        if (registry.resolveBinding(name).orElse(null) == TypeScriptExportRegistry.Binding.NAMED) {
                            String quote = importLine.contains("'") ? "'" : "\"";
                            String newLine = "import { " + name + " } from " + quote + specifier + quote;
                            replacements.add(new String[]{importLine, newLine});
                            log.info("[TypeScriptImportFixer] {}: rewrote default import to named: {} → {{ {} }}",
                                    fileName, name, name);
                        }
                    }
                }
                continue;
            }

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

    /** Resolves an import specifier base to the actual file on disk, trying .tsx then .ts. */
    private static Path resolveToActualFile(Path base) {
        for (String ext : new String[]{".tsx", ".ts", "/index.tsx", "/index.ts"}) {
            Path candidate = Path.of(base.toString() + ext);
            if (Files.exists(candidate)) return candidate;
        }
        if (Files.exists(base)) return base; // already has extension
        return null;
    }

    private static String readSilent(Path file) {
        try { return Files.readString(file); } catch (IOException e) { return null; }
    }
}
