package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Adds missing TanStack Query hook imports (Pattern C in docs/frontend-error-pattern-analysis.md).
 * The generator routinely uses a hook it forgot to import — e.g. `import { useMutation, useQueryClient }`
 * then calls `useQuery(...)` → `TS2304: Cannot find name 'useQuery'`. `TypeScriptImportFixer` only fixes
 * *existing* import lines; it never adds a symbol that has no import at all, so this class fills that gap
 * for the one library where it recurs.
 *
 * For each file: the hooks that are USED but not imported are merged into the existing
 * `@tanstack/react-query` import (preserving its symbols), or a new import line is added when none exists.
 * Deterministic, additive; a file already importing everything it uses is untouched.
 */
@Slf4j
public final class TanStackImportFixer {

    private static final String MODULE = "@tanstack/react-query";
    // The value hooks the generator actually reaches for. Ordered for stable output.
    private static final List<String> HOOKS = List.of(
            "useQuery", "useMutation", "useQueryClient", "useInfiniteQuery",
            "useSuspenseQuery", "useQueries", "useIsFetching", "useIsMutating");

    private static final Pattern EXISTING_IMPORT = Pattern.compile(
            "import\\s*\\{([^}]*)}\\s*from\\s*['\"]@tanstack/react-query['\"]\\s*;?");

    private TanStackImportFixer() {}

    /** Returns true if any file was modified. */
    public static boolean fix(Path frontendSrc) {
        if (!Files.exists(frontendSrc)) return false;
        boolean[] changed = {false};
        try (Stream<Path> files = Files.walk(frontendSrc)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".tsx") || p.toString().endsWith(".ts"))
                 .filter(p -> !p.toString().contains("node_modules"))
                 .forEach(p -> {
                     try {
                         String content = Files.readString(p);
                         String fixed = fixContent(content);
                         if (!fixed.equals(content)) {
                             Files.writeString(p, fixed);
                             changed[0] = true;
                             log.info("[TanStackImportFixer] Added missing react-query hook import(s) to {}",
                                     p.getFileName());
                         }
                     } catch (IOException e) {
                         log.warn("[TanStackImportFixer] Could not process {}: {}", p, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.warn("[TanStackImportFixer] Walk failed for {}: {}", frontendSrc, e.getMessage());
        }
        return changed[0];
    }

    static String fixContent(String content) {
        // Which hooks are used as identifiers anywhere in the file?
        Set<String> used = new LinkedHashSet<>();
        for (String hook : HOOKS) {
            if (Pattern.compile("\\b" + hook + "\\b").matcher(content).find()) used.add(hook);
        }
        if (used.isEmpty()) return content;

        Matcher im = EXISTING_IMPORT.matcher(content);
        if (im.find()) {
            List<String> imported = parseNames(im.group(1));
            Set<String> missing = new TreeSet<>(used);
            missing.removeAll(imported);
            if (missing.isEmpty()) return content;

            List<String> all = new ArrayList<>(imported);
            all.addAll(missing);
            String newImport = "import { " + String.join(", ", all) + " } from '" + MODULE + "';";
            return new StringBuilder(content)
                    .replace(im.start(), im.end(), newImport)
                    .toString();
        }

        // No existing react-query import — add one (all used hooks are missing).
        String newImport = "import { " + String.join(", ", new TreeSet<>(used)) + " } from '" + MODULE + "';\n";
        return newImport + content;
    }

    /** Parses `{ a, b as c, type D }` → [a, b, D] (identifier, before any `as`; drops a leading `type`). */
    private static List<String> parseNames(String braceBody) {
        List<String> names = new ArrayList<>();
        for (String raw : braceBody.split(",")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            t = t.replaceFirst("^type\\s+", "");
            t = t.split("\\s+as\\s+")[0].trim();
            if (!t.isEmpty()) names.add(t);
        }
        return names;
    }
}
