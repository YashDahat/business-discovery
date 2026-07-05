package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Inventory-driven UI import correction — the generic fix for the LLM inventing
 * exports that don't exist. No component knowledge is hardcoded here; every rewrite
 * decision is made against UiComponentInventory (built from installed reality).
 *
 * Rules, in order, per import statement:
 *  1. Named import from @radix-ui/<pkg> that the package does NOT export, but some
 *     shadcn ui file DOES → move the name to `import { X } from '@/components/ui/<file>'`.
 *     (The DialogHeader class of error, solved generally.)
 *  2. Import path @/components/ui/<Name> whose file doesn't exist but the lowercase
 *     file does → canonicalize the path (case-sensitive bundlers break on ui/Button).
 *  Anything unresolvable is left for tsc + the ErrorFixAgent — this pass only makes
 *  changes it can prove correct from the inventory.
 */
@Slf4j
public final class UiImportRewriter {

    private static final Pattern RADIX_IMPORT = Pattern.compile(
            "^import\\s*\\{([^}]*)}\\s*from\\s*['\"](@radix-ui/[a-z0-9-]+)['\"];?\\s*$",
            Pattern.MULTILINE);
    private static final Pattern UI_PATH_IMPORT = Pattern.compile(
            "(from\\s*['\"]@/components/ui/)([A-Za-z0-9-]+)(['\"])");

    private UiImportRewriter() {}

    /** Returns true if any file was modified. */
    public static boolean fix(Path frontendSrc, UiComponentInventory inventory) {
        if (!Files.exists(frontendSrc) || inventory.isEmpty()) return false;
        boolean[] changed = {false};
        try (Stream<Path> files = Files.walk(frontendSrc)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".tsx") || p.toString().endsWith(".ts"))
                 .filter(p -> !p.toString().contains("node_modules"))
                 .filter(p -> !p.toString().contains("/components/ui/")) // never rewrite shadcn's own files
                 .forEach(file -> {
                     try {
                         String original = Files.readString(file);
                         String rewritten = rewrite(original, inventory, file.getFileName().toString());
                         if (!rewritten.equals(original)) {
                             Files.writeString(file, rewritten);
                             changed[0] = true;
                         }
                     } catch (IOException e) {
                         log.warn("[UiImportRewriter] Could not process {}: {}", file, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.warn("[UiImportRewriter] Walk failed: {}", e.getMessage());
        }
        return changed[0];
    }

    static String rewrite(String content, UiComponentInventory inventory, String fileLabel) {
        content = fixUiPathCasing(content, inventory, fileLabel);
        content = relocatePhantomRadixImports(content, inventory, fileLabel);
        return content;
    }

    /** Rule 2: @/components/ui/Button → @/components/ui/button when only the lowercase file exists. */
    private static String fixUiPathCasing(String content, UiComponentInventory inventory, String fileLabel) {
        Matcher m = UI_PATH_IMPORT.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String stem = m.group(2);
            String canonical = stem.toLowerCase();
            if (!inventory.shadcnUiExports().containsKey(stem)
                    && inventory.shadcnUiExports().containsKey(canonical)) {
                log.info("[UiImportRewriter] {}: ui/{} -> ui/{} (canonical casing)", fileLabel, stem, canonical);
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + canonical + m.group(3)));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Rule 1: names imported from a Radix package it doesn't export, that a shadcn ui
     * file does export, move to the shadcn import. Provably-correct moves only.
     */
    private static String relocatePhantomRadixImports(String content, UiComponentInventory inventory,
                                                      String fileLabel) {
        Matcher m = RADIX_IMPORT.matcher(content);
        StringBuilder sb = new StringBuilder();
        Map<String, List<String>> toAdd = new LinkedHashMap<>(); // ui stem → names to import

        while (m.find()) {
            String pkg = m.group(2);
            List<String> pkgExports = inventory.radixExports().get(pkg);
            if (pkgExports == null) { // package not installed/enumerated — don't touch
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            List<String> keep = new ArrayList<>();
            List<String> moved = new ArrayList<>();
            for (String rawSpec : m.group(1).split(",")) {
                String spec = rawSpec.trim();
                if (spec.isEmpty()) continue;
                String exportedName = spec.split("\\s+as\\s+")[0].trim();
                if (pkgExports.contains(exportedName)) {
                    keep.add(spec);
                    continue;
                }
                String uiHome = findUiFileExporting(exportedName, inventory);
                if (uiHome != null) {
                    toAdd.computeIfAbsent(uiHome, k -> new ArrayList<>()).add(spec);
                    moved.add(exportedName + " -> ui/" + uiHome);
                } else {
                    keep.add(spec); // unknown everywhere — leave for tsc/agent
                }
            }
            if (moved.isEmpty()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            } else {
                log.info("[UiImportRewriter] {}: {} not exported by {} — relocated ({})",
                        fileLabel, moved.size(), pkg, String.join(", ", moved));
                String replacement = keep.isEmpty() ? ""
                        : "import { " + String.join(", ", keep) + " } from '" + pkg + "';";
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
        }
        m.appendTail(sb);
        String result = sb.toString();

        for (Map.Entry<String, List<String>> add : toAdd.entrySet()) {
            result = mergeUiImport(result, add.getKey(), add.getValue());
        }
        return result;
    }

    private static String findUiFileExporting(String name, UiComponentInventory inventory) {
        for (Map.Entry<String, List<String>> entry : inventory.shadcnUiExports().entrySet()) {
            if (entry.getValue().contains(name)) return entry.getKey();
        }
        return null;
    }

    /** Adds names to an existing `from '@/components/ui/<stem>'` import or appends a new one. */
    private static String mergeUiImport(String content, String stem, List<String> names) {
        Pattern existing = Pattern.compile(
                "import\\s*\\{([^}]*)}\\s*from\\s*['\"]@/components/ui/" + Pattern.quote(stem) + "['\"];?");
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
                    + "import { " + String.join(", ", merged) + " } from '@/components/ui/" + stem + "';"
                    + content.substring(m.end());
        }
        return "import { " + String.join(", ", names) + " } from '@/components/ui/" + stem + "';\n" + content;
    }
}
