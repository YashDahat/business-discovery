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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Deterministic net for issue #6 (docs/frontend-issue-solution-plan-9312afa6.md): the generator
 * imports a lucide-react export that does not exist (e.g. {@code SwimmingPool} — lucide ships
 * {@code Waves} for water). {@link UiImportRewriter} only ADDS a missing import; nothing validates
 * that an already-imported named symbol is real, so a hallucinated icon falls through to tsc + the
 * ErrorFixAgent — which cannot reliably re-enumerate lucide's surface and may pick another fake.
 *
 * <p>This pass validates every {@code import { … } from 'lucide-react'} against the package's REAL
 * export set (via {@link NodeModuleExportRegistry#exportsOfPackage}) and splits the work along the
 * seam between the <em>deterministic</em> and the <em>semantic</em>:
 * <ul>
 *   <li><b>Tier A — auto-fix.</b> An invalid name that normalizes to exactly ONE real export under
 *       case-insensitive / {@code Icon} add-strip / singular-plural rules is a typo, not a
 *       hallucination — rewrite the import spec AND (when un-aliased) every JSX/value usage. Always
 *       correct; saves the round entirely.</li>
 *   <li><b>Tier B — annotate + defer.</b> An invalid name with no certain normalization
 *       ({@code SwimmingPool → Waves} is a semantic judgement) is NOT guessed. A structured
 *       {@code // FIXME[invalid-icon]} comment naming the bad symbol and registry-verified candidates
 *       (lexical near-matches + a tiny concept→lucide hint table + a generic fallback, all validated
 *       against the real set so a suggestion is never itself fake) is inserted above the import; the
 *       import is left untouched, so the build stays red and the ErrorFixAgent runs — but now with the
 *       diagnosis and real options handed to it rather than having to discover them.</li>
 * </ul>
 * No wrong icon is ever auto-shipped (Tier B never rewrites). Zero LLM; idempotent. Same family as
 * {@link UiImportRewriter} / {@code EnumValueImportPatcher}.
 */
@Slf4j
public final class LucideIconValidator {

    private static final Pattern LUCIDE_IMPORT = Pattern.compile(
            "import\\s*\\{([^}]*)}\\s*from\\s*['\"]lucide-react['\"];?");
    private static final Pattern CAMEL_SPLIT = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    /** Tiny, auditable concept→lucide hint table (all candidates validated against the real set before use). */
    private static final Map<String, List<String>> CONCEPT_HINTS = new LinkedHashMap<>();
    static {
        CONCEPT_HINTS.put("swim", List.of("Waves", "Droplets"));
        CONCEPT_HINTS.put("pool", List.of("Waves", "Droplets"));
        CONCEPT_HINTS.put("water", List.of("Waves", "Droplets", "Droplet"));
        CONCEPT_HINTS.put("gym", List.of("Dumbbell", "Activity"));
        CONCEPT_HINTS.put("fitness", List.of("Dumbbell", "Activity"));
        CONCEPT_HINTS.put("workout", List.of("Dumbbell", "Activity"));
        CONCEPT_HINTS.put("exercise", List.of("Dumbbell", "Activity"));
        CONCEPT_HINTS.put("weight", List.of("Dumbbell"));
        CONCEPT_HINTS.put("dumbbell", List.of("Dumbbell"));
        CONCEPT_HINTS.put("yoga", List.of("PersonStanding", "Activity"));
        CONCEPT_HINTS.put("treadmill", List.of("Activity", "Footprints"));
        CONCEPT_HINTS.put("cardio", List.of("Activity", "HeartPulse"));
        CONCEPT_HINTS.put("run", List.of("Activity", "Footprints"));
        CONCEPT_HINTS.put("trainer", List.of("Users", "UserCog"));
        CONCEPT_HINTS.put("coach", List.of("Users", "Whistle"));
        CONCEPT_HINTS.put("class", List.of("CalendarDays", "Users"));
    }
    private static final List<String> GENERIC_FALLBACK = List.of("Star", "Sparkles", "Circle");
    private static final int MAX_CANDIDATES = 4;

    private LucideIconValidator() {}

    /**
     * Validates lucide-react imports across the frontend tree against {@code lucideExports} (the real
     * export set). Returns true only when a Tier A auto-fix was applied (a change that can turn the
     * build green); Tier B annotations are still written to disk but do NOT flip the return, since they
     * cannot help the build and re-running it would be wasted.
     */
    public static boolean fix(Path frontendSrc, Set<String> lucideExports) {
        if (!Files.exists(frontendSrc) || lucideExports == null || lucideExports.isEmpty()) return false;
        // Case-insensitive lookup: lower(realName) -> realName, for Tier A normalization.
        Map<String, String> lower = new LinkedHashMap<>();
        for (String e : lucideExports) lower.putIfAbsent(e.toLowerCase(), e);

        boolean[] tierAChanged = {false};
        try (Stream<Path> files = Files.walk(frontendSrc)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".tsx") || p.toString().endsWith(".ts"))
                 .filter(p -> !p.toString().contains("node_modules"))
                 .filter(p -> !p.toString().contains("/components/ui/")) // never touch shadcn's own files
                 .forEach(p -> {
                     try {
                         String content = Files.readString(p);
                         if (content.stripLeading().startsWith("// GENERATED")) return; // fenced/derived
                         Result r = rewrite(content, lucideExports, lower);
                         if (!r.content.equals(content)) {
                             Files.writeString(p, r.content);
                             if (r.tierA) {
                                 tierAChanged[0] = true;
                                 log.info("[LucideIconValidator] Normalized invalid lucide icon(s) in {}", p.getFileName());
                             } else {
                                 log.info("[LucideIconValidator] Annotated invalid lucide icon(s) for the agent in {}", p.getFileName());
                             }
                         }
                     } catch (IOException e) {
                         log.warn("[LucideIconValidator] Could not process {}: {}", p, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.warn("[LucideIconValidator] Walk failed for {}: {}", frontendSrc, e.getMessage());
        }
        return tierAChanged[0];
    }

    private record Result(String content, boolean tierA) {}

    static Result rewrite(String content, Set<String> exports, Map<String, String> lower) {
        Matcher m = LUCIDE_IMPORT.matcher(content);
        StringBuilder out = new StringBuilder();
        boolean tierA = false;
        // oldLocalName -> newName, applied to the body after the import clauses are rebuilt.
        Map<String, String> bodyRenames = new LinkedHashMap<>();

        while (m.find()) {
            List<String> newSpecs = new ArrayList<>();
            List<String> fixmeLines = new ArrayList<>();
            for (String raw : m.group(1).split(",")) {
                String spec = raw.trim();
                if (spec.isEmpty()) continue;
                String[] asParts = spec.split("\\s+as\\s+");
                String source = asParts[0].trim();
                boolean aliased = asParts.length > 1;

                if (exports.contains(source)) {          // already real
                    newSpecs.add(spec);
                    continue;
                }
                String normalized = normalize(source, exports, lower);
                if (normalized != null) {                // Tier A — certain typo fix
                    tierA = true;
                    newSpecs.add(aliased ? normalized + " as " + asParts[1].trim() : normalized);
                    if (!aliased) bodyRenames.put(source, normalized);
                } else {                                 // Tier B — annotate + defer, keep the import
                    newSpecs.add(spec);
                    if (!content.contains("FIXME[invalid-icon]: '" + source + "'")) {
                        List<String> cands = candidates(source, exports, lower);
                        fixmeLines.add("// FIXME[invalid-icon]: '" + source + "' is not exported by lucide-react. "
                                + "Replace it (import + all usages) with one of these real icons: "
                                + String.join(", ", cands) + ".");
                    }
                }
            }
            StringBuilder repl = new StringBuilder();
            for (String line : fixmeLines) repl.append(line).append("\n");
            repl.append("import { ").append(String.join(", ", newSpecs)).append(" } from 'lucide-react';");
            m.appendReplacement(out, Matcher.quoteReplacement(repl.toString()));
        }
        m.appendTail(out);

        String result = out.toString();
        for (Map.Entry<String, String> e : bodyRenames.entrySet()) {
            result = result.replaceAll("\\b" + Pattern.quote(e.getKey()) + "\\b",
                    Matcher.quoteReplacement(e.getValue()));
        }
        return new Result(result, tierA);
    }

    /**
     * Deterministic normalization: returns the single real export {@code name} resolves to under
     * case-insensitive / {@code Icon} add-strip / singular-plural rules, or null when zero or more than
     * one real export results (ambiguous → defer to Tier B). {@code name} is assumed NOT already real.
     */
    static String normalize(String name, Set<String> exports, Map<String, String> lower) {
        Set<String> hits = new LinkedHashSet<>();
        for (String variant : variants(name)) {
            String hit = lower.get(variant.toLowerCase());
            if (hit != null) hits.add(hit);
        }
        return hits.size() == 1 ? hits.iterator().next() : null;
    }

    private static List<String> variants(String name) {
        List<String> v = new ArrayList<>();
        v.add(name);                                                  // case-insensitive exact
        if (name.endsWith("Icon")) v.add(name.substring(0, name.length() - 4)); // strip Icon
        else v.add(name + "Icon");                                    // add Icon
        if (name.endsWith("s")) v.add(name.substring(0, name.length() - 1));    // plural -> singular
        else v.add(name + "s");                                       // singular -> plural
        return v;
    }

    /**
     * Registry-verified replacement candidates for a hallucinated icon (Tier B): lexical near-matches
     * by edit distance, then concept→lucide hints tokenized from the invented name, then a generic
     * fallback. Every returned value is a real export, so a suggestion is never itself fake.
     */
    static List<String> candidates(String name, Set<String> exports, Map<String, String> lower) {
        LinkedHashSet<String> out = new LinkedHashSet<>();

        // (i) lexical near-matches (edit distance <= 3, closest first)
        String key = name.toLowerCase();
        exports.stream()
               .filter(e -> levenshtein(key, e.toLowerCase()) <= 3)
               .sorted((a, b) -> levenshtein(key, a.toLowerCase()) - levenshtein(key, b.toLowerCase()))
               .limit(2)
               .forEach(out::add);

        // (ii) concept→lucide hints from the invented name's tokens (a token CONTAINS the concept
        //      key, so 'Swimming' matches 'swim')
        for (String tok : CAMEL_SPLIT.split(name)) {
            String t = tok.toLowerCase();
            for (Map.Entry<String, List<String>> e : CONCEPT_HINTS.entrySet()) {
                if (t.contains(e.getKey())) {
                    for (String h : e.getValue()) if (exports.contains(h)) out.add(h);
                }
            }
        }

        // (iii) generic fallback so the comment always offers something valid
        for (String g : GENERIC_FALLBACK) if (exports.contains(g)) out.add(g);

        return new ArrayList<>(out).subList(0, Math.min(out.size(), MAX_CANDIDATES));
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }
}
