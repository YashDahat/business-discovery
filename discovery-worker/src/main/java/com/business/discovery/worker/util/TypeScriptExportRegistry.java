package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks exported symbols from generated TypeScript/TSX files.
 * Built incrementally as files are generated — each call to register() adds a file's exports.
 *
 * Used by TypeScriptImportFixer to resolve broken @/ and relative import specifiers
 * by looking up where a symbol is actually exported from.
 */
@Slf4j
public class TypeScriptExportRegistry {

    private static final Pattern NAMED_EXPORT = Pattern.compile(
            "^export\\s+(?:default\\s+)?(?:function|class|const|let|var|interface|type|enum)\\s+([A-Za-z][\\w]*)",
            Pattern.MULTILINE);
    private static final Pattern RE_EXPORT = Pattern.compile("\\bexport\\s+\\{([^}]+)}");

    // symbolName → workspace-relative path without extension
    private final Map<String, String> symbolToPath = new HashMap<>();
    private final Path workspace;

    public TypeScriptExportRegistry(Path workspace) {
        this.workspace = workspace;
    }

    public void register(Path filePath, String content) {
        String rel = workspace.relativize(filePath).toString();
        String relNoExt = rel.replaceFirst("\\.(tsx?|jsx?)$", "");

        Matcher m = NAMED_EXPORT.matcher(content);
        while (m.find()) {
            symbolToPath.put(m.group(1), relNoExt);
        }

        // Named re-exports: export { Foo, Bar } or export { A as B }
        Matcher rm = RE_EXPORT.matcher(content);
        while (rm.find()) {
            for (String part : rm.group(1).split(",")) {
                String sym = part.trim().split("\\s+")[0].replaceAll("[{}]", "").trim();
                if (!sym.isEmpty() && Character.isUpperCase(sym.charAt(0))) {
                    symbolToPath.put(sym, relNoExt);
                }
            }
        }
    }

    public Optional<String> resolveSpecifier(String symbol) {
        return Optional.ofNullable(symbolToPath.get(symbol));
    }

    public boolean knows(String symbol) {
        return symbolToPath.containsKey(symbol);
    }

    /** True when nothing has been registered yet (e.g. before the first layer generates). */
    public boolean isEmpty() {
        return symbolToPath.isEmpty();
    }

    /**
     * Renders every registered export as a closed-world import catalog for injection into the
     * generation prompt: one line per module, its symbols grouped under the {@code @/} alias the
     * model must import by. Lets Flash import existing components/hooks/types/services by their real
     * names and paths instead of inventing modules the post-hoc fixer then has to repair.
     *
     * <p>Output is deterministic (modules and symbols sorted). Meant to be read during the
     * read-only generation phase — register() must not run concurrently, matching how
     * resolveSpecifier/knows are already used.
     *
     * @return the catalog block, or "" when nothing is registered yet
     */
    public String toImportCatalog() {
        if (symbolToPath.isEmpty()) return "";

        Map<String, TreeSet<String>> byModule = new TreeMap<>();
        for (Map.Entry<String, String> e : symbolToPath.entrySet()) {
            byModule.computeIfAbsent(toAlias(e.getValue()), k -> new TreeSet<>()).add(e.getKey());
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, TreeSet<String>> e : byModule.entrySet()) {
            sb.append(e.getKey()).append(" -> ").append(String.join(", ", e.getValue())).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** frontend/src/foo/Bar -> @/foo/Bar (the import form the tsconfig {@code @/*} alias resolves). */
    private static String toAlias(String relNoExt) {
        String p = relNoExt.replace('\\', '/');
        return p.startsWith("frontend/src/") ? "@/" + p.substring("frontend/src/".length()) : p;
    }
}
