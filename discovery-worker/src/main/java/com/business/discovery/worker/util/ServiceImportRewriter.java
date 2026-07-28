package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Retargets imports of service modules that no longer exist to the derived SDK module
 * that actually exports each symbol — the identical mechanism to {@link UiImportRewriter}
 * relocating DialogHeader to the shadcn wrapper, applied to the API layer.
 *
 * Needed because ServicePlanPruner deletes planned services the derivation didn't claim
 * (adminOrderService.ts etc.), but the plan already told every admin component to import
 * from them — without this pass each of those imports breaks with TS2307. getAllOrders is
 * not in adminOrderService; it is in the derived orderService, and the export index knows
 * that.
 *
 * Handles both ways an import can be wrong about where a function lives:
 *   TS2307 — the module itself does not exist (ServicePlanPruner deleted it).
 *   TS2305 — the module exists but does not export that symbol. This is the shape enrichment
 *            produces on its own: it writes the instruction BEFORE the SDK is derived, so it
 *            guesses the grouping ("bookingService exports getAllFitnessClasses") while
 *            derivation actually split those functions across classeService/scheduleService.
 *            vikram-s-fitness-studio: 9 such imports, none of them repairable here, so the
 *            fix agent hand-wrote apiClient calls against invented paths instead.
 *
 * Provably-correct moves only. A symbol is relocated when its current module is missing, OR
 * when the module IS a derived (marker-carrying) SDK file that demonstrably lacks the symbol —
 * those files come from TsSdkGenerator in one uniform shape, so a parsed absence is real.
 * Modules outside the derived set (services/local/, LLM-written strays) are never mined for
 * absences, since an export form the regex cannot see would look identical to a missing one.
 * Default and namespace imports, unknown symbols, and non-service modules are left for tsc +
 * the ErrorFixAgent.
 */
@Slf4j
public final class ServiceImportRewriter {

    private static final Pattern NAMED_IMPORT = Pattern.compile(
            "^import\\s+(type\\s+)?\\{([^}]*)}\\s*from\\s*['\"]([^'\"]*(?:services/|Service)[^'\"]*)['\"];?\\s*$",
            Pattern.MULTILINE);
    private static final Pattern EXPORTED_NAME = Pattern.compile(
            "^export\\s+(?:const|function|class|interface|type|enum)\\s+(\\w+)", Pattern.MULTILINE);

    private ServiceImportRewriter() {}

    /** Which derived module exports each symbol, and the full export set of each module. */
    record DerivedSdk(Map<String, String> homeOf, Map<String, Set<String>> exportsOf) {
        boolean isEmpty() { return homeOf.isEmpty(); }
    }

    /** Returns true if any file was modified. */
    public static boolean fix(Path frontendSrc) {
        DerivedSdk sdk = scanDerivedSdk(frontendSrc);
        if (sdk.isEmpty()) return false;

        boolean[] changed = {false};
        try (Stream<Path> files = Files.walk(frontendSrc)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".tsx") || p.toString().endsWith(".ts"))
                 .filter(p -> !p.toString().contains("node_modules"))
                 .filter(p -> !p.getParent().equals(frontendSrc.resolve("services"))) // never rewrite the SDK itself
                 .forEach(file -> {
                     try {
                         String original = Files.readString(file);
                         String rewritten = rewrite(original, file, frontendSrc, sdk);
                         if (!rewritten.equals(original)) {
                             Files.writeString(file, rewritten);
                             changed[0] = true;
                         }
                     } catch (IOException e) {
                         log.warn("[ServiceImportRewriter] Could not process {}: {}", file, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.warn("[ServiceImportRewriter] Walk failed: {}", e.getMessage());
        }
        return changed[0];
    }

    /**
     * Exported symbol → module stem ('orderService'), from marker-carrying services only.
     * An LLM-written stray in services/ is not contract and must not attract imports.
     * Collisions resolve to the first stem in sorted order, with a warning.
     */
    static DerivedSdk scanDerivedSdk(Path frontendSrc) {
        Path servicesDir = frontendSrc.resolve("services");
        Map<String, String> index = new LinkedHashMap<>();
        Map<String, Set<String>> exportsOf = new LinkedHashMap<>();
        if (!Files.isDirectory(servicesDir)) return new DerivedSdk(index, exportsOf);
        Map<String, String> byStem = new TreeMap<>();
        try (Stream<Path> files = Files.list(servicesDir)) {
            files.filter(p -> p.toString().endsWith(".ts")).sorted().forEach(p -> {
                try {
                    String content = Files.readString(p);
                    if (!content.startsWith("// GENERATED from the backend API contract")) return;
                    String stem = p.getFileName().toString().replaceAll("\\.ts$", "");
                    byStem.put(stem, content);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            log.warn("[ServiceImportRewriter] Could not list services/: {}", e.getMessage());
        }
        byStem.forEach((stem, content) -> {
            Set<String> exports = new LinkedHashSet<>();
            Matcher m = EXPORTED_NAME.matcher(content);
            while (m.find()) {
                exports.add(m.group(1));
                String prev = index.putIfAbsent(m.group(1), stem);
                if (prev != null && !prev.equals(stem)) {
                    log.warn("[ServiceImportRewriter] {} exported by both {} and {} — using {}",
                            m.group(1), prev, stem, prev);
                }
            }
            exportsOf.put(stem, exports);
        });
        return new DerivedSdk(index, exportsOf);
    }

    static String rewrite(String content, Path file, Path frontendSrc, DerivedSdk sdk) {
        Matcher m = NAMED_IMPORT.matcher(content);
        StringBuilder sb = new StringBuilder();
        Map<String, List<String>> toAdd = new LinkedHashMap<>(); // target stem → symbols
        String fileLabel = file.getFileName().toString();

        while (m.find()) {
            String typePrefix = m.group(1) == null ? "" : m.group(1);
            String spec = m.group(3);

            boolean moduleMissing = !moduleResolves(spec, file, frontendSrc);
            String stem = derivedStem(spec, file, frontendSrc);
            // Present symbols of the target, but ONLY when it is a derived file — for anything
            // else an unparsed export form is indistinguishable from a real absence.
            Set<String> present = (!moduleMissing && stem != null && sdk.exportsOf().containsKey(stem))
                    ? sdk.exportsOf().get(stem)
                    : null;

            if (!moduleMissing && present == null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }

            List<String> keep = new ArrayList<>();
            List<String> movedNotes = new ArrayList<>();
            for (String rawSym : m.group(2).split(",")) {
                String sym = rawSym.trim();
                if (sym.isEmpty()) continue;
                String exportedName = sym.split("\\s+as\\s+")[0].trim();

                if (present != null && present.contains(exportedName)) {
                    keep.add(sym);           // already imported from the module that exports it
                    continue;
                }
                String home = sdk.homeOf().get(exportedName);
                if (home != null && !home.equals(stem)) {
                    toAdd.computeIfAbsent(home, k -> new ArrayList<>()).add(sym);
                    movedNotes.add(exportedName + " -> " + home);
                } else {
                    keep.add(sym);
                    log.warn("[ServiceImportRewriter] {}: '{}' from '{}' is exported by no derived "
                            + "service — left for the fix agent", fileLabel, exportedName, spec);
                }
            }
            if (movedNotes.isEmpty()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            } else {
                log.info("[ServiceImportRewriter] {}: module '{}' {} — relocated ({})",
                        fileLabel, spec,
                        moduleMissing ? "does not exist" : "does not export those symbols",
                        String.join(", ", movedNotes));
                String replacement = keep.isEmpty() ? ""
                        : "import " + typePrefix + "{ " + String.join(", ", keep) + " } from '" + spec + "';";
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
        }
        m.appendTail(sb);
        String result = sb.toString();

        for (Map.Entry<String, List<String>> add : toAdd.entrySet()) {
            result = mergeServiceImport(result, add.getKey(), add.getValue());
        }
        return result;
    }

    /**
     * The services/ stem this import targets, or null when it points anywhere else. Resolved
     * against the filesystem rather than matched on the string, so '@/api/orderService' cannot
     * masquerade as the derived 'orderService', and services/local/ stays excluded.
     */
    private static String derivedStem(String spec, Path importingFile, Path frontendSrc) {
        Path base;
        String rel;
        if (spec.startsWith("@/")) {
            base = frontendSrc;
            rel = spec.substring(2);
        } else if (spec.startsWith(".")) {
            base = importingFile.getParent();
            rel = spec;
        } else {
            return null;
        }
        Path resolved = base.resolve(rel).normalize();
        Path parent = resolved.getParent();
        if (parent == null || !parent.equals(frontendSrc.resolve("services"))) return null;
        return resolved.getFileName().toString().replaceAll("\\.ts$", "");
    }

    private static boolean moduleResolves(String spec, Path importingFile, Path frontendSrc) {
        Path base;
        String rel;
        if (spec.startsWith("@/")) {
            base = frontendSrc;
            rel = spec.substring(2);
        } else if (spec.startsWith(".")) {
            base = importingFile.getParent();
            rel = spec;
        } else {
            return true; // bare package specifier — node_modules territory, not ours
        }
        Path resolved = base.resolve(rel).normalize();
        return Files.exists(resolved)
                || Files.exists(resolved.resolveSibling(resolved.getFileName() + ".ts"))
                || Files.exists(resolved.resolveSibling(resolved.getFileName() + ".tsx"));
    }

    /** Adds symbols to an existing `from '@/services/<stem>'` import or prepends a new one. */
    private static String mergeServiceImport(String content, String stem, List<String> names) {
        Pattern existing = Pattern.compile(
                "import\\s*\\{([^}]*)}\\s*from\\s*['\"](?:@/services/|\\.{1,2}/(?:\\.\\./)*services/)"
                        + Pattern.quote(stem) + "['\"];?");
        Matcher m = existing.matcher(content);
        if (m.find()) {
            List<String> merged = new ArrayList<>();
            for (String part : m.group(1).split(",")) {
                if (!part.trim().isEmpty()) merged.add(part.trim());
            }
            for (String name : names) {
                if (!merged.contains(name)) merged.add(name);
            }
            return content.substring(0, m.start())
                    + "import { " + String.join(", ", merged) + " } from '@/services/" + stem + "';"
                    + content.substring(m.end());
        }
        return "import { " + String.join(", ", names) + " } from '@/services/" + stem + "';\n" + content;
    }
}
