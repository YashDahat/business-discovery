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
 * Shape-level contracts for intra-frontend module boundaries — hook return types,
 * context hook signatures, and interface/type field shapes — extracted from generated
 * files and injected into every subsequent layer's generation prompts.
 *
 * Sibling of {@link ApiContractCard} (which covers the backend-derived API boundary).
 * This card covers the intra-frontend boundary: what a hook actually returns, what
 * fields a local type declares, what a context exposes — things a page or component
 * must know to import correctly but which the LLM would otherwise guess from intent
 * rather than from the ground-truth file.
 *
 * Two construction paths:
 *   - Incremental (generation): new FrontendContractCard() then register() per file,
 *     called at the same sites exportRegistry.register() is called.
 *   - Disk scan (resume / ErrorFixAgent): build(workspace) or buildImmutableOnly(workspace).
 *
 * buildImmutableOnly reads only files carrying a fence marker (cart spine, foundation
 * scaffold) — safe to inject into the ErrorFixAgent's cached system prompt because the
 * fix agent cannot edit them.
 */
@Slf4j
public final class FrontendContractCard {

    // ── Extraction patterns ───────────────────────────────────────────────────

    // export function useFoo(...): ReturnType {
    private static final Pattern HOOK_FN = Pattern.compile(
            "^export\\s+(?:default\\s+)?function\\s+(use\\w+)\\s*\\([^)]*\\)\\s*:\\s*(.+?)\\s*\\{",
            Pattern.MULTILINE);

    // export const useFoo = (...): ReturnType =>
    private static final Pattern HOOK_ARROW = Pattern.compile(
            "^export\\s+const\\s+(use\\w+)\\s*=\\s*\\([^)]*\\)\\s*:\\s*(.+?)\\s*=>",
            Pattern.MULTILINE);

    // export interface Foo { ... }
    private static final Pattern INTERFACE = Pattern.compile(
            "^export\\s+interface\\s+(\\w+)\\s*\\{([^}]+)\\}",
            Pattern.MULTILINE | Pattern.DOTALL);

    // export type Foo = { ... }
    private static final Pattern TYPE_ALIAS = Pattern.compile(
            "^export\\s+type\\s+(\\w+)\\s*=\\s*\\{([^}]+)\\}",
            Pattern.MULTILINE | Pattern.DOTALL);

    // interface FooProps { ... } — export keyword optional; components rarely export their props type
    private static final Pattern PROPS_INTERFACE = Pattern.compile(
            "^(?:export\\s+)?interface\\s+(\\w+Props)\\s*\\{([^}]+)\\}",
            Pattern.MULTILINE | Pattern.DOTALL);

    // fieldName?: Type  or  fieldName: Type
    private static final Pattern FIELD = Pattern.compile(
            "^\\s*(\\w+)(\\?)?\\s*:\\s*([^;\\n,}]+)",
            Pattern.MULTILINE);

    // Detects any non-type export (const / function / enum / class / let / var).
    // If absent, the file exports types only → emit the ⚠ type-not-value warning.
    private static final Pattern VALUE_EXPORT = Pattern.compile(
            "^export\\s+(?:default\\s+)?(?:const|function|enum|class|let|var)\\s+",
            Pattern.MULTILINE);

    // ── Fence markers (mirrors FrontendGeneratorNode + CartSpineScaffold) ─────

    private static final String CART_SPINE_MARKER      = "GENERATED cart spine";
    private static final String FOUNDATION_MARKER      = "GENERATED foundation scaffold";

    // ── Storage ───────────────────────────────────────────────────────────────

    // importPath (@/hooks/useEvents) → list of rendered signature lines
    private final Map<String, List<String>> contracts = new LinkedHashMap<>();
    // importPath → kind label ("hook", "context", "type", "immutable-context")
    private final Map<String, String> kinds = new LinkedHashMap<>();

    // ── Incremental registration ──────────────────────────────────────────────

    /**
     * Registers a file's contracts. No-op for files that are not hook, context,
     * or local-type files (e.g. pages, components, services, derived types).
     * Safe to call on every file alongside exportRegistry.register().
     */
    public void register(Path filePath, String content) {
        String rel = filePath.toString().replace('\\', '/');
        String alias = toAlias(rel);
        if (alias == null) return;

        String kind = classifyKind(rel, content);
        if (kind == null) return;

        List<String> sigs = extractSignatures(content, kind);
        if (sigs.isEmpty()) return;

        contracts.put(alias, sigs);
        kinds.put(alias, kind);
    }

    // ── Disk-scan factory methods ─────────────────────────────────────────────

    /**
     * Full build from disk — used when resuming from a cloned workspace, or to
     * rehydrate before ErrorFixAgent runs. Scans hooks/, context/, cart/,
     * and types/local/ under frontend/src/.
     */
    public static FrontendContractCard build(Path workspace) {
        FrontendContractCard card = new FrontendContractCard();
        Path src = workspace.resolve("frontend/src");
        if (!Files.isDirectory(src)) return card;

        scanDirectory(card, src.resolve("hooks"),       "hooks");
        scanDirectory(card, src.resolve("context"),    "context");
        scanDirectory(card, src.resolve("cart"),       "cart");
        scanDirectory(card, src.resolve("shell"),      "shell");      // foundation scaffold — AdminLayout etc.
        scanDirectory(card, src.resolve("types"),      "types");      // classifyKind skips derived files
        scanDirectory(card, src.resolve("components"), "components"); // classifyKind skips ui/

        log.info("[FrontendContractCard] Built from disk: {} module(s)", card.moduleCount());
        return card;
    }

    /**
     * Restricted build for ErrorFixAgent — only files carrying a fence marker
     * (cart spine, foundation scaffold). These are closed for modification so
     * the fix agent cannot make the card stale.
     */
    public static FrontendContractCard buildImmutableOnly(Path workspace) {
        FrontendContractCard card = new FrontendContractCard();
        Path src = workspace.resolve("frontend/src");
        if (!Files.isDirectory(src)) return card;

        scanImmutable(card, src.resolve("cart"));
        scanImmutable(card, src.resolve("context"));
        scanImmutable(card, src.resolve("shell"));    // AdminLayout, SiteHeader, SiteLayout, SiteFooter

        log.info("[FrontendContractCard] Built (immutable-only): {} module(s)", card.moduleCount());
        return card;
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    public boolean isEmpty()      { return contracts.isEmpty(); }
    public int     moduleCount()  { return contracts.size(); }

    // ── Rendering ─────────────────────────────────────────────────────────────

    /**
     * Renders all registered contracts as a compact .d.ts-style prompt section.
     * Byte-identical across files within the same layer (the card is frozen before
     * the parallel generation block) so it rides the provider's prefix cache.
     */
    public String toPromptSection() {
        if (contracts.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("── FRONTEND MODULE CONTRACTS (ground truth — extracted from generated files) ──\n");
        sb.append("Import ONLY from these paths, ONLY these names. ");
        sb.append("Match every return field and prop EXACTLY, including `| null` vs optional `?`.\n\n");

        for (Map.Entry<String, List<String>> e : contracts.entrySet()) {
            String path = e.getKey();
            String kind = kinds.getOrDefault(path, "module");
            sb.append(path).append(" [").append(kind).append("]\n");
            for (String sig : e.getValue()) {
                sb.append("  ").append(sig).append("\n");
            }
            sb.append("\n");
        }

        sb.append("──────────────────────────────────────────────────────────────────────────────");
        return sb.toString();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static void scanDirectory(FrontendContractCard card, Path dir, String hint) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(p -> p.toString().endsWith(".ts") || p.toString().endsWith(".tsx"))
                 .filter(p -> !p.toString().contains("node_modules"))
                 .sorted()
                 .forEach(p -> {
                     try { card.register(p, Files.readString(p)); }
                     catch (IOException ignored) {}
                 });
        } catch (IOException e) {
            log.warn("[FrontendContractCard] Could not scan {}: {}", dir, e.getMessage());
        }
    }

    private static void scanImmutable(FrontendContractCard card, Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".ts") || p.toString().endsWith(".tsx"))
                 .sorted()
                 .forEach(p -> {
                     try {
                         String content = Files.readString(p);
                         String firstLine = content.lines().findFirst().orElse("");
                         if (firstLine.contains(CART_SPINE_MARKER) || firstLine.contains(FOUNDATION_MARKER)) {
                             card.register(p, content);
                         }
                     } catch (IOException ignored) {}
                 });
        } catch (IOException e) {
            log.warn("[FrontendContractCard] Could not scan immutable {}: {}", dir, e.getMessage());
        }
    }

    /**
     * Classifies the file's kind based on its workspace-relative path.
     * Returns null for files we don't extract (pages, components, derived services/types).
     */
    private static final String DERIVED_MARKER = "GENERATED from the backend API contract";

    private static String classifyKind(String rel, String content) {
        if (!rel.contains("frontend/src/")) return null;

        // Derived services are covered by ApiContractCard — skip entirely
        if (rel.contains("frontend/src/services/") && !rel.contains("frontend/src/services/local/")) return null;

        String path = rel.replace('\\', '/');
        if (path.contains("/hooks/"))         return "hook";
        if (path.contains("/cart/"))          return "immutable-context";
        if (path.contains("/context/"))       return "context";
        if (path.contains("/shell/"))         return "shell";
        if (path.contains("/components/ui/")) return null; // shadcn — covered by UiComponentInventory
        if (path.contains("/components/"))    return "component";

        // types/ — include planner-written files; skip only files carrying the derived marker
        // (those belong to ApiContractCard which already surfaces them verbatim)
        if (path.contains("/types/")) {
            String firstLine = content.lines().findFirst().orElse("");
            if (firstLine.contains(DERIVED_MARKER)) return null;
            return "type";
        }

        return null;
    }

    private static List<String> extractSignatures(String content, String kind) {
        List<String> sigs = new ArrayList<>();

        if ("hook".equals(kind) || "context".equals(kind) || "immutable-context".equals(kind)) {
            Matcher fn = HOOK_FN.matcher(content);
            while (fn.find()) {
                sigs.add(fn.group(1) + "(): " + fn.group(2).trim());
            }
            Matcher arrow = HOOK_ARROW.matcher(content);
            while (arrow.find()) {
                // Avoid duplicates if both patterns hit the same function
                String sig = arrow.group(1) + "(): " + arrow.group(2).trim();
                if (!sigs.contains(sig)) sigs.add(sig);
            }
        }

        // Components and shell: extract *Props interfaces (export optional — components rarely export them)
        if ("component".equals(kind) || "shell".equals(kind)) {
            Matcher pm = PROPS_INTERFACE.matcher(content);
            while (pm.find()) {
                String fields = renderFields(pm.group(2));
                if (!fields.isBlank()) sigs.add(pm.group(1) + ": { " + fields + " }");
            }
        }

        // Hooks/types/contexts/shell: extract exported interfaces and type aliases
        if (!"component".equals(kind)) {
            extractTypes(content, sigs);
        }

        // ⚠ type-not-value warning: file exports only interfaces/types, no values
        if (!sigs.isEmpty() && !VALUE_EXPORT.matcher(content).find()) {
            sigs.add("⚠ These are TYPES — no enum-style value access (e.g. no Foo.BAR).");
        }

        return sigs;
    }

    private static void extractTypes(String content, List<String> sigs) {
        for (Pattern p : List.of(INTERFACE, TYPE_ALIAS)) {
            Matcher m = p.matcher(content);
            while (m.find()) {
                String name   = m.group(1);
                String body   = m.group(2);
                String fields = renderFields(body);
                if (!fields.isBlank()) {
                    sigs.add(name + ": { " + fields + " }");
                }
            }
        }
    }

    private static String renderFields(String body) {
        List<String> parts = new ArrayList<>();
        Matcher m = FIELD.matcher(body);
        while (m.find()) {
            String fname    = m.group(1);
            boolean opt     = m.group(2) != null;
            String ftype    = m.group(3).trim();
            boolean nullable = ftype.contains("| null") || ftype.contains("null |");
            String rendered = fname + (opt ? "?" : "") + ": " + ftype;
            if (nullable && !opt) rendered += "  /* null-guard required */";
            parts.add(rendered);
        }
        return String.join("; ", parts);
    }

    /** Converts a filesystem path to the @/ alias used in TypeScript imports. */
    private static String toAlias(String rel) {
        String p = rel.replace('\\', '/');
        int idx = p.indexOf("frontend/src/");
        if (idx < 0) return null;
        String suffix = p.substring(idx + "frontend/src/".length())
                         .replaceFirst("\\.(tsx?|jsx?)$", "");
        return "@/" + suffix;
    }
}
