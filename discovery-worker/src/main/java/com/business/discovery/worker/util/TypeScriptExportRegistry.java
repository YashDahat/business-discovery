package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Tracks exported symbols from generated TypeScript/TSX files.
 * Built incrementally as files are generated — each call to register() adds a file's exports.
 *
 * Used by TypeScriptImportFixer to resolve broken @/ and relative import specifiers
 * by looking up where a symbol is actually exported from.
 */
@Slf4j
public class TypeScriptExportRegistry {

    // Group 1 = the `default ` keyword (present → default export), group 2 = the symbol name.
    private static final Pattern NAMED_EXPORT = Pattern.compile(
            "^export\\s+(default\\s+)?(?:function|class|const|let|var|interface|type|enum)\\s+([A-Za-z][\\w]*)",
            Pattern.MULTILINE);
    private static final Pattern RE_EXPORT = Pattern.compile("\\bexport\\s+\\{([^}]+)}");

    /** How a symbol is exported — decides `import X` vs `import { X }` at the call site. */
    public enum Binding { DEFAULT, NAMED }

    // symbolName → workspace-relative path without extension
    private final Map<String, String> symbolToPath = new HashMap<>();
    // symbolName → default vs named export form
    private final Map<String, Binding> symbolToBinding = new HashMap<>();
    private final Path workspace;

    public TypeScriptExportRegistry(Path workspace) {
        this.workspace = workspace;
    }

    public void register(Path filePath, String content) {
        String rel = workspace.relativize(filePath).toString();
        String relNoExt = rel.replaceFirst("\\.(tsx?|jsx?)$", "");

        Matcher m = NAMED_EXPORT.matcher(content);
        while (m.find()) {
            String name = m.group(2);
            symbolToPath.put(name, relNoExt);
            symbolToBinding.put(name, m.group(1) != null ? Binding.DEFAULT : Binding.NAMED);
        }

        // Named re-exports: export { Foo, Bar } or export { A as B } — always the named form.
        Matcher rm = RE_EXPORT.matcher(content);
        while (rm.find()) {
            for (String part : rm.group(1).split(",")) {
                String sym = part.trim().split("\\s+")[0].replaceAll("[{}]", "").trim();
                if (!sym.isEmpty() && Character.isUpperCase(sym.charAt(0))) {
                    symbolToPath.put(sym, relNoExt);
                    symbolToBinding.put(sym, Binding.NAMED);
                }
            }
        }
    }

    /**
     * Builds a registry by scanning every .ts/.tsx under {@code frontendSrc}. Used by
     * post-generation pre-passes (e.g. FrontendValidationNode) that need the export map but
     * weren't present while the files were generated incrementally.
     */
    public static TypeScriptExportRegistry buildFromDisk(Path frontendSrc, Path workspace) {
        TypeScriptExportRegistry registry = new TypeScriptExportRegistry(workspace);
        if (!Files.exists(frontendSrc)) return registry;
        try (Stream<Path> files = Files.walk(frontendSrc)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".ts") || p.toString().endsWith(".tsx"))
                 .forEach(p -> {
                     try {
                         registry.register(p, Files.readString(p));
                     } catch (IOException e) {
                         log.warn("[TypeScriptExportRegistry] Could not read {}: {}", p, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.warn("[TypeScriptExportRegistry] Walk failed for {}: {}", frontendSrc, e.getMessage());
        }
        return registry;
    }

    public Optional<String> resolveSpecifier(String symbol) {
        return Optional.ofNullable(symbolToPath.get(symbol));
    }

    /** How {@code symbol} is exported (default vs named), if the registry has seen it. */
    public Optional<Binding> resolveBinding(String symbol) {
        return Optional.ofNullable(symbolToBinding.get(symbol));
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
