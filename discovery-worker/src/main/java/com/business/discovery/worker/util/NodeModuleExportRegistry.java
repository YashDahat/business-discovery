package com.business.discovery.worker.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans the frontend's {@code node_modules} — restricted to the packages declared in
 * {@code package.json} "dependencies" (the project's defined dependency scope) — and maps each
 * EXPORTED symbol to the single package that exports it. Consumed by {@link UiImportRewriter} to add
 * a missing import for a symbol the generator used but never imported (e.g. a lucide icon dropped into
 * JSX, {@code <ShoppingCart/>}).
 *
 * Precision over recall: a symbol exported by two or more scoped packages is AMBIGUOUS and dropped, so
 * the registry never resolves to the wrong package. Barrel re-exports ({@code export * from '…'}) are
 * not followed (a documented gap) and default exports are ignored (they have no canonical import name).
 * The type-declaration entry is taken from each package's {@code types}/{@code typings}, with common
 * fallbacks.
 *
 * Sibling-by-design: a backend {@code MavenDependencyRegistry} can mirror this — scan the resolved
 * dependency jars (scoped to the pom) for simple-name → FQN, feeding {@link JavaImportResolver}.
 */
@Slf4j
public final class NodeModuleExportRegistry {

    // export [declare] [abstract] const|let|var|function|class|enum|namespace|type|interface NAME
    private static final Pattern EXPORT_DECL = Pattern.compile(
            "export\\s+(?:declare\\s+)?(?:abstract\\s+)?"
          + "(?:const|let|var|function|class|enum|namespace|type|interface)\\s+([A-Za-z_$][\\w$]*)");
    // export { A, B as C, type D } [from '…']  — captures the brace body (may span the whole file for barrels)
    private static final Pattern EXPORT_BRACE = Pattern.compile("export\\s+(?:type\\s+)?\\{([^}]*)}");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][\\w$]*");

    private final Map<String, String> symbolToPackage;

    private NodeModuleExportRegistry(Map<String, String> symbolToPackage) {
        this.symbolToPackage = symbolToPackage;
    }

    /** The single scoped package that exports {@code symbol}, or empty if unknown/ambiguous. */
    public Optional<String> packageFor(String symbol) {
        return Optional.ofNullable(symbolToPackage.get(symbol));
    }

    /**
     * The RAW, un-collapsed export-name set of a SINGLE package (empty if the package is absent or its
     * {@code .d.ts} is unreadable). Unlike {@link #packageFor}, this does NOT drop symbols shared with
     * other scoped packages — {@code Route} is exported by BOTH {@code lucide-react} and
     * {@code react-router-dom}, so the ambiguity-collapsed map would wrongly omit it. A validator asking
     * "does {@code lucide-react} really export this name?" needs the package's true surface, not the map.
     * Used by {@link LucideIconValidator} (issue #6, docs/frontend-issue-solution-plan-9312afa6.md).
     */
    public static Set<String> exportsOfPackage(Path frontendDir, String pkg) {
        Path nodeModules = frontendDir.resolve("node_modules");
        if (!Files.isDirectory(nodeModules)) return Set.of();
        return exportsOf(nodeModules, pkg, new ObjectMapper());
    }

    public boolean isEmpty() { return symbolToPackage.isEmpty(); }
    public int size()        { return symbolToPackage.size(); }

    public static NodeModuleExportRegistry empty() {
        return new NodeModuleExportRegistry(Map.of());
    }

    public static NodeModuleExportRegistry build(Path frontendDir) {
        Path pkgJson = frontendDir.resolve("package.json");
        Path nodeModules = frontendDir.resolve("node_modules");
        if (!Files.isRegularFile(pkgJson) || !Files.isDirectory(nodeModules)) return empty();

        ObjectMapper om = new ObjectMapper();
        Map<String, Set<String>> symbolToPkgs = new HashMap<>();
        int scanned = 0;
        try {
            JsonNode deps = om.readTree(pkgJson.toFile()).get("dependencies");
            if (deps == null) return empty();
            for (Iterator<String> it = deps.fieldNames(); it.hasNext(); ) {
                String pkg = it.next();
                scanned++;
                for (String sym : exportsOf(nodeModules, pkg, om)) {
                    symbolToPkgs.computeIfAbsent(sym, k -> new HashSet<>()).add(pkg);
                }
            }
        } catch (IOException e) {
            log.warn("[NodeModuleExportRegistry] Could not read {}: {}", pkgJson, e.getMessage());
            return empty();
        }

        Map<String, String> unambiguous = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : symbolToPkgs.entrySet()) {
            if (e.getValue().size() == 1) unambiguous.put(e.getKey(), e.getValue().iterator().next());
        }
        log.info("[NodeModuleExportRegistry] {} unambiguous symbols from {} scoped dependencies "
                + "({} dropped as ambiguous)", unambiguous.size(), scanned, symbolToPkgs.size() - unambiguous.size());
        return new NodeModuleExportRegistry(unambiguous);
    }

    private static Set<String> exportsOf(Path nodeModules, String pkg, ObjectMapper om) {
        Path dts = resolveDts(nodeModules, pkg, om);
        if (dts == null) return Set.of();
        Set<String> out = new HashSet<>();
        try {
            String content = Files.readString(dts);
            Matcher d = EXPORT_DECL.matcher(content);
            while (d.find()) out.add(d.group(1));
            Matcher b = EXPORT_BRACE.matcher(content);
            while (b.find()) {
                for (String raw : b.group(1).split(",")) {
                    String s = raw.trim().replaceFirst("^type\\s+", "");
                    if (s.isEmpty()) continue;
                    String[] asParts = s.split("\\s+as\\s+");
                    String name = asParts[asParts.length - 1].trim(); // exported name = after `as`
                    if (!name.equals("default") && IDENT.matcher(name).matches()) out.add(name);
                }
            }
        } catch (IOException ignored) {
            // unreadable .d.ts — contribute nothing
        }
        return out;
    }

    /** Resolves a package's TypeScript declaration entry: types/typings field, else common fallbacks. */
    private static Path resolveDts(Path nodeModules, String pkg, ObjectMapper om) {
        Path pkgDir = nodeModules.resolve(pkg); // resolve handles scoped @scope/name
        Path pj = pkgDir.resolve("package.json");
        if (Files.isRegularFile(pj)) {
            try {
                JsonNode n = om.readTree(pj.toFile());
                String rel = n.hasNonNull("types") ? n.get("types").asText()
                           : n.hasNonNull("typings") ? n.get("typings").asText() : null;
                if (rel != null) {
                    Path p = pkgDir.resolve(rel);
                    if (Files.isRegularFile(p)) return p;
                }
            } catch (IOException ignored) {
                // fall through to conventional locations
            }
        }
        for (String cand : List.of("index.d.ts", "dist/index.d.ts", "dist/index.d.mts",
                "build/legacy/index.d.ts", "lib/index.d.ts", "types/index.d.ts")) {
            Path p = pkgDir.resolve(cand);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }
}
