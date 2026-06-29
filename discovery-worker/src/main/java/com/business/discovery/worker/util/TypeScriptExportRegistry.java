package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
}
