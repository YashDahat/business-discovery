package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Enforcement Point B — the repair half (Change 2 synthesis). Given the missing producers found by
 * {@link ImportClosureChecker}, writes a permissive placeholder module for each so the unresolved
 * imports resolve and the build proceeds. This is the gen-time safety net for modules invented in
 * generated code that the plan-time completeness pass (Point A) could not see.
 *
 * <p>The placeholder is deliberately maximal-compatibility: it exports a default AND every named
 * symbol the consumers ask for, as both a value and a type, and — crucially — the component form
 * <em>passes {@code children} through</em> so a synthesized layout never silently drops page content.
 * It compiles and renders; residual type mismatches (props, etc.) are a bounded problem left to the
 * ErrorFixAgent. See {@code docs/architecture-json-completeness-plan.md} §8.
 */
@Slf4j
public final class MissingModuleSynthesizer {

    /** `import … from '<spec>'` / `export … from '<spec>'` — group 1 = the clause, group 2 = specifier. */
    private static final Pattern MODULE = Pattern.compile(
            "^\\s*(?:import|export)\\b([^'\"]*?)\\bfrom\\s+['\"]([^'\"]+)['\"]", Pattern.MULTILINE);
    private static final Pattern NAMED_BLOCK = Pattern.compile("\\{([^}]*)}");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final String[] EXTS = {".ts", ".tsx", ".jsx", ".js", ".d.ts"};

    private MissingModuleSynthesizer() {}

    private static final class Need {
        Path base;                                    // resolved target path, no extension
        boolean needsDefault;
        final Set<String> named = new TreeSet<>();
    }

    /**
     * Writes a placeholder module for each unresolved specifier. Returns the number written. Skips any
     * whose target already resolves on disk (race). Never throws.
     */
    public static int synthesize(Path frontendSrc, List<ImportClosureChecker.Unresolved> unresolved) {
        if (unresolved == null || unresolved.isEmpty() || !Files.exists(frontendSrc)) return 0;
        Set<String> targets = unresolved.stream()
                .map(ImportClosureChecker.Unresolved::specifier).collect(Collectors.toSet());
        Map<String, Need> needs = scan(frontendSrc, targets);

        int written = 0;
        for (Map.Entry<String, Need> e : needs.entrySet()) {
            Need need = e.getValue();
            if (need.base == null || resolvesOnDisk(need.base)) continue;
            Path target = Path.of(need.base + ".tsx");
            try {
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                Files.writeString(target, stub(need));
                written++;
                log.info("[MissingModuleSynthesizer] Synthesized placeholder for '{}' -> {}",
                        e.getKey(), target.getFileName());
            } catch (IOException ex) {
                log.warn("[MissingModuleSynthesizer] Could not write placeholder for {}: {}", e.getKey(), ex.getMessage());
            }
        }
        return written;
    }

    /** One walk: for each import/export of a target specifier, record the resolved base + needed exports. */
    private static Map<String, Need> scan(Path frontendSrc, Set<String> targets) {
        Map<String, Need> needs = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(frontendSrc)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".ts") || p.toString().endsWith(".tsx"))
                 .forEach(file -> {
                     String content = read(file);
                     if (content == null) return;
                     Matcher m = MODULE.matcher(content);
                     while (m.find()) {
                         String spec = m.group(2).trim();
                         if (!targets.contains(spec)) continue;
                         Need need = needs.computeIfAbsent(spec, k -> new Need());
                         if (need.base == null) need.base = resolveBase(spec, file, frontendSrc);
                         parseClause(m.group(1), need);
                     }
                 });
        } catch (IOException e) {
            log.warn("[MissingModuleSynthesizer] Walk failed for {}: {}", frontendSrc, e.getMessage());
        }
        return needs;
    }

    /** Determines needed exports from an import clause (the text between import/export and `from`). */
    static void parseClause(String clause, Need need) {
        Matcher nm = NAMED_BLOCK.matcher(clause);
        boolean hasNamed = false;
        while (nm.find()) {
            hasNamed = true;
            for (String raw : nm.group(1).split(",")) {
                String s = raw.trim().replaceFirst("^type\\s+", "").split("\\s+as\\s+")[0].trim();
                if (s.matches("[A-Za-z_$][\\w$]*")) need.named.add(s);
            }
        }
        String outside = clause.replaceAll("\\{[^}]*}", "").replaceAll("\\btype\\b", "").trim();
        if (outside.contains("*")) { need.needsDefault = true; return; }   // import * as X → ensure non-empty
        if (IDENTIFIER.matcher(outside).find()) need.needsDefault = true;  // default import
        else if (!hasNamed) need.needsDefault = true;                      // `export * from` / bare
    }

    private static Path resolveBase(String spec, Path importer, Path frontendSrc) {
        return spec.startsWith("@/")
                ? frontendSrc.resolve(spec.substring(2))
                : importer.getParent().resolve(spec).normalize();
    }

    private static boolean resolvesOnDisk(Path base) {
        if (Files.exists(base)) return true;
        for (String ext : EXTS) if (Files.exists(Path.of(base + ext))) return true;
        return false;
    }

    /** Maximal-compatibility placeholder: default + every named symbol, as value and type; passes children through. */
    static String stub(Need need) {
        StringBuilder sb = new StringBuilder();
        sb.append("// AUTO-GENERATED placeholder — referenced but never generated (Point B synthesis).\n");
        sb.append("// Replace with the real implementation.\n");
        sb.append("const __stub: any = (props: any) => (props && props.children) ?? null;\n");
        if (need.needsDefault || need.named.isEmpty()) sb.append("export default __stub;\n");
        for (String s : need.named) {
            sb.append("export const ").append(s).append(": any = __stub;\n");
            sb.append("export type ").append(s).append(" = any;\n");
        }
        return sb.toString();
    }

    private static String read(Path p) {
        try { return Files.readString(p); } catch (IOException e) { return null; }
    }
}
