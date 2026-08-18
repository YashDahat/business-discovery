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

    // Hook / context accessor declaration — function or arrow-const form. Matches only up to
    // the params' opening '(' ; the return type (which may itself contain nested { } — a mutation
    // result object) is read by brace matching from just past the params, NOT by regex, so nested
    // shapes survive (Gap B) and an unannotated hook is still captured from its return literal (Gap A).
    private static final Pattern HOOK_DECL = Pattern.compile(
            "^export\\s+(?:default\\s+)?(?:function\\s+(use\\w+)"
          + "|const\\s+(use\\w+)\\s*=\\s*(?:async\\s*)?)\\s*\\(",
            Pattern.MULTILINE);

    // export interface Foo { ... }
    private static final Pattern INTERFACE = Pattern.compile(
            "^export\\s+interface\\s+(\\w+)\\s*\\{([^}]+)\\}",
            Pattern.MULTILINE | Pattern.DOTALL);

    // export type Foo = { ... }
    private static final Pattern TYPE_ALIAS = Pattern.compile(
            "^export\\s+type\\s+(\\w+)\\s*=\\s*\\{([^}]+)\\}",
            Pattern.MULTILINE | Pattern.DOTALL);

    // *Props declaration — `interface FooProps {` OR `type FooProps = {` (export optional; components
    // rarely export their props type). Locates the DECLARATION only; the body is read by brace matching
    // so a nested prop type (e.g. onSubmit: (v: {…}) => void) survives instead of truncating (Gap B).
    private static final Pattern PROPS_DECL = Pattern.compile(
            "(?:export\\s+)?(?:interface\\s+(\\w+Props)|type\\s+(\\w+Props)\\s*=)\\s*\\{",
            Pattern.MULTILINE);

    // Component signature — recovers inline props when there is no named *Props type (Gap A).
    private static final Pattern COMPONENT_SIG = Pattern.compile(
            "^export\\s+(?:default\\s+)?(?:function\\s+([A-Z]\\w*)|const\\s+([A-Z]\\w*)\\b[^=]*=)",
            Pattern.MULTILINE);

    // name?: FullType  — one member of a props/return body (DOTALL so multi-line function/object types survive).
    private static final Pattern MEMBER = Pattern.compile(
            "^(\\w+)\\s*(\\??)\\s*:\\s*(.+)$", Pattern.DOTALL);

    // Leading identifier of a return-literal member (`data`, `isLoading: x`, `mutate: create.mutate`).
    private static final Pattern LEADING_IDENT = Pattern.compile("^([A-Za-z_$][\\w$]*)");

    // fieldName?: Type  or  fieldName: Type  (used only by extractTypes/renderFields — the type path is unchanged)
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
            extractHookSignatures(content, sigs);
        }

        // Components and shell: extract *Props — brace-aware (nested types survive), with an inline-props fallback.
        if ("component".equals(kind) || "shell".equals(kind)) {
            extractProps(content, sigs);
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

    // ── Hook return-type extraction (brace-aware — closes Gap B, plus Gap A fallback) ──────────

    /**
     * Extracts every `useX` hook's public shape as `useX(): <return type>`. The return type is read
     * by brace matching from just past the params so a nested object type (a mutation-result object
     * like `{ mutate: (v: {…}) => void; isPending }`) is captured whole instead of truncating at the
     * first inner `{` (Gap B). When a hook omits its return annotation (a rule-2 violation), its shape
     * is recovered from the `return { … }` literal's field names (Gap A) so consumers still get a
     * contract instead of guessing the standard TanStack `{ data, mutate(x,y) }` shape.
     */
    private static void extractHookSignatures(String content, List<String> sigs) {
        Matcher m = HOOK_DECL.matcher(content);
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            int paramOpen = m.end() - 1;                       // pattern ends on the params' '('
            int paramClose = matchDelims(content, paramOpen, '(', ')');
            if (paramClose < 0) continue;

            int i = skipWs(content, paramClose + 1);
            String sig;
            if (i < content.length() && content.charAt(i) == ':') {
                // Explicit return annotation.
                i = skipWs(content, i + 1);
                if (i < content.length() && content.charAt(i) == '{') {          // object return type
                    String body = matchBraces(content, i);
                    if (body == null) continue;
                    sig = name + "(): { " + collapse(body) + " }";
                } else {                                                          // named/generic return type
                    String rt = readReturnType(content, i);
                    if (rt == null || rt.isBlank()) continue;
                    sig = name + "(): " + collapse(rt);
                }
            } else {
                // Gap A — no annotation; recover the shape from the return literal (names only).
                String shape = extractReturnLiteralShape(content, paramClose + 1);
                if (shape == null) continue;
                sig = name + "(): " + shape;
            }
            if (!sigs.contains(sig)) sigs.add(sig);
        }
    }

    /** Reads a named/generic return type from `start` until the function body `{` or arrow `=>` at depth 0. */
    private static String readReturnType(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' || c == '(' || c == '[') depth++;
            else if (c == '>' || c == ')' || c == ']') depth = Math.max(0, depth - 1);
            else if (depth == 0) {
                if (c == '{') return s.substring(start, i);
                if (c == '=' && i + 1 < s.length() && s.charAt(i + 1) == '>') return s.substring(start, i);
            }
        }
        return null;
    }

    /** Names-only shape from the last `return { … }` literal — the Gap-A backstop for an unannotated hook. */
    private static String extractReturnLiteralShape(String content, int fromIdx) {
        String shape = null;
        int r = content.indexOf("return", fromIdx);
        while (r >= 0) {
            int j = skipWs(content, r + "return".length());
            if (j < content.length() && content.charAt(j) == '{') {
                String body = matchBraces(content, j);
                if (body != null) {
                    List<String> keys = new ArrayList<>();
                    for (String member : topLevelMembers(body)) {
                        Matcher id = LEADING_IDENT.matcher(member);
                        if (id.find()) keys.add(id.group(1));
                    }
                    if (!keys.isEmpty()) {
                        shape = "{ " + String.join("; ", keys)
                                + " }  /* fields only — annotate the hook return type (rule 2) */";
                    }
                }
            }
            r = content.indexOf("return", r + 1);
        }
        return shape;
    }

    // ── Component *Props extraction (brace-aware — closes Gap B, plus inline-props Gap A fallback) ──

    /**
     * Extracts a component's `*Props` shape. Reads the body by brace matching so a nested prop type
     * (`onSubmit: (v: {…}) => void`) survives (Gap B); if the component declares no named `*Props` type
     * but destructures an inline props literal (`function Foo({ a, children }: { … })`), the shape is
     * recovered from that literal (Gap A) so props like `children` are not silently dropped.
     */
    private static void extractProps(String content, List<String> sigs) {
        boolean captured = false;
        Matcher d = PROPS_DECL.matcher(content);
        while (d.find()) {
            String name = d.group(1) != null ? d.group(1) : d.group(2);
            String body = matchBraces(content, d.end() - 1);            // pattern ends on the '{'
            String line = (body == null) ? null : renderProps(name, body);
            if (line != null) { sigs.add(line); captured = true; }
        }
        if (!captured) {                                                // Gap A: no named *Props → read inline props
            String[] inline = extractInlineProps(content);
            if (inline != null) {
                String line = renderProps(inline[0], inline[1]);
                if (line != null) sigs.add(line);
            }
        }
    }

    /** Recovers the inline type literal annotating a destructured props param: Foo({…}: { HERE }). */
    private static String[] extractInlineProps(String content) {
        Matcher m = COMPONENT_SIG.matcher(content);
        if (!m.find()) return null;
        String comp = m.group(1) != null ? m.group(1) : m.group(2);
        int paren = content.indexOf('(', m.end());
        if (paren < 0) return null;
        int destructure = content.indexOf('{', paren);
        int close = content.indexOf(')', paren);
        if (destructure < 0 || (close >= 0 && destructure > close)) return null;   // param not destructured
        String param = matchBraces(content, destructure);
        if (param == null) return null;
        int afterParam = destructure + param.length() + 2;                         // past the '}' of { … }
        int typeBrace = content.indexOf('{', afterParam);
        close = content.indexOf(')', afterParam);
        if (typeBrace < 0 || (close >= 0 && typeBrace > close)) return null;        // named type → PROPS_DECL handles it
        String body = matchBraces(content, typeBrace);
        return body == null ? null : new String[]{ comp + "Props", body };
    }

    private static String renderProps(String name, String body) {
        List<String> fields = new ArrayList<>();
        for (String member : topLevelMembers(body)) {
            String r = renderMember(member);
            if (r != null) fields.add(r);
        }
        return fields.isEmpty() ? null : name + ": { " + String.join("; ", fields) + " }";
    }

    private static String renderMember(String member) {
        Matcher m = MEMBER.matcher(member.trim());
        if (!m.find()) return null;                                    // skip call/index sigs, comments
        boolean opt = !m.group(2).isEmpty();
        String type = collapse(m.group(3));
        boolean nullable = type.contains("| null") || type.contains("null |");
        return m.group(1) + (opt ? "?" : "") + ": " + type + (nullable && !opt ? "  /* null-guard required */" : "");
    }

    // ── Brace / delimiter matching (shared by the hook and props paths) ───────────────────────

    /** Body between the '{' at openIdx and its matching '}', nesting-aware. null if unbalanced. */
    private static String matchBraces(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return s.substring(openIdx + 1, i);
        }
        return null;
    }

    /** Index of the matching close delimiter for the open delimiter at openIdx (nesting-aware); -1 if none. */
    private static int matchDelims(String s, int openIdx, char open, char close) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == open) depth++;
            else if (c == close && --depth == 0) return i;
        }
        return -1;
    }

    /** Split a body into members on ';'/newline, but only at brace/paren/angle depth 0. */
    private static List<String> topLevelMembers(String body) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{' || c == '(' || c == '<' || c == '[') depth++;
            else if (c == '}' || c == ')' || c == '>' || c == ']') depth = Math.max(0, depth - 1);
            else if ((c == ';' || c == ',' || c == '\n') && depth == 0) { addTrimmed(out, body.substring(start, i)); start = i + 1; }
        }
        addTrimmed(out, body.substring(start));
        return out;
    }

    private static void addTrimmed(List<String> out, String m) { m = m.trim(); if (!m.isEmpty()) out.add(m); }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static String collapse(String s) { return s.trim().replaceAll("\\s+", " "); }

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
