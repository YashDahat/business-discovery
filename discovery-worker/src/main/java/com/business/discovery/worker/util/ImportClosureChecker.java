package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Enforcement Point B (generation time) of the ARCHITECTURE.json completeness invariant — the
 * ground-truth half. Scans the REAL {@code import}/{@code export … from} statements in generated
 * frontend files and reports every local ({@code @/}, {@code ./}, {@code ../}) specifier that does
 * not resolve to a file on disk. An unresolved local import is a missing producer — the class the
 * ErrorFixAgent cannot author reliably ({@code AdminLayout} survived 3 attempts) — surfaced here as a
 * clear, aggregated, deterministic diagnostic instead of a truncated {@code TS2307} discovered late.
 *
 * <p>Run this AFTER the deterministic path fixers (TypeScriptImportFixer): once wrong paths are
 * rewritten, anything still unresolved is genuinely absent, not mis-pathed. Unlike the manifest checks
 * (Point A), this reads code, so it catches modules invented only at generation time. See
 * {@code docs/architecture-json-completeness-plan.md} §8.
 */
@Slf4j
public final class ImportClosureChecker {

    /** `import … from '<spec>'` and `export … from '<spec>'` (spans multiple lines up to the quote). */
    private static final Pattern MODULE_SPECIFIER = Pattern.compile(
            "^\\s*(?:import|export)\\b[^'\"]*?from\\s+['\"]([^'\"]+)['\"]", Pattern.MULTILINE);
    private static final String[] EXTS = {".ts", ".tsx", ".jsx", ".js", ".d.ts"};
    private static final String[] INDEXES = {"/index.ts", "/index.tsx", "/index.js", "/index.jsx"};

    private ImportClosureChecker() {}

    /** A local module specifier that resolves to no file on disk, and the files that import it. */
    public record Unresolved(String specifier, Set<String> importedBy) {}

    /**
     * @param frontendSrc the {@code frontend/src} directory of the generated project
     * @return unresolved local specifiers (sorted), each with the importing file names
     */
    public static List<Unresolved> check(Path frontendSrc) {
        Map<String, Set<String>> unresolved = new TreeMap<>();
        if (!Files.exists(frontendSrc)) return List.of();

        try (Stream<Path> files = Files.walk(frontendSrc)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".ts") || p.toString().endsWith(".tsx"))
                 .forEach(file -> {
                     String content = readSilent(file);
                     if (content == null) return;
                     Matcher m = MODULE_SPECIFIER.matcher(content);
                     while (m.find()) {
                         String spec = m.group(1).trim();
                         if (!isLocal(spec)) continue;                 // node_modules — not our concern
                         if (resolves(spec, file, frontendSrc)) continue;
                         unresolved.computeIfAbsent(spec, k -> new TreeSet<>())
                                   .add(file.getFileName().toString());
                     }
                 });
        } catch (IOException e) {
            log.warn("[ImportClosureChecker] Walk failed for {}: {}", frontendSrc, e.getMessage());
        }

        List<Unresolved> out = new ArrayList<>();
        unresolved.forEach((spec, importers) -> out.add(new Unresolved(spec, importers)));
        return out;
    }

    private static boolean isLocal(String spec) {
        return spec.startsWith("@/") || spec.startsWith("./") || spec.startsWith("../");
    }

    private static boolean resolves(String spec, Path importingFile, Path frontendSrc) {
        Path base = spec.startsWith("@/")
                ? frontendSrc.resolve(spec.substring(2))
                : importingFile.getParent().resolve(spec).normalize();
        if (Files.exists(base)) return true;                          // literal (e.g. .css/.json/explicit ext)
        for (String ext : EXTS) if (Files.exists(Path.of(base + ext))) return true;
        for (String idx : INDEXES) if (Files.exists(Path.of(base + idx))) return true;
        return false;
    }

    /** Human-readable report of the unresolved specifiers and who imports each. */
    public static String render(List<Unresolved> unresolved) {
        StringBuilder sb = new StringBuilder();
        sb.append(unresolved.size()).append(" unresolved local import(s) — referenced modules that were "
                + "never generated (missing producers):\n");
        for (Unresolved u : unresolved) {
            sb.append("  ").append(u.specifier()).append("   imported by: ")
              .append(String.join(", ", u.importedBy())).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** Writes a ships-on-branch report (advisory), same family as SMOKE_REPORT.md / API_CONTRACT_REPORT.md. */
    public static void writeReport(Path workspace, String body) {
        try {
            Path docs = workspace.resolve("docs");
            Files.createDirectories(docs);
            Files.writeString(docs.resolve("IMPORT_CLOSURE_REPORT.md"),
                    "# Import Closure Report\n\n" + body + "\n");
        } catch (IOException e) {
            log.warn("[ImportClosureChecker] Could not write report: {}", e.getMessage());
        }
    }

    private static String readSilent(Path file) {
        try { return Files.readString(file); } catch (IOException e) { return null; }
    }
}
