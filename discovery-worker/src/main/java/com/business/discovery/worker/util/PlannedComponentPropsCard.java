package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.PublicFunction;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The planned prop contract for every COMPONENT/PAGE, sourced from the enriched spec's
 * {@code public_functions} — a single up-front ground truth so a parent and its child bind to the
 * SAME prop definition instead of each inventing one during the parallel COMPONENT layer
 * (docs/frontend-hook-generation-and-prompt-segregation.md — sibling-prop drift, Cluster 2).
 *
 * <p>Mechanical: it re-renders the enrichment's own {@code public_functions} params, normalizing the
 * inconsistent "Type name" / "name: Type" forms the enrichment emits into a single {@code name: Type}
 * shape. Built once per run from the static plan, so it exists BEFORE any component is generated —
 * which is exactly why it sidesteps the parallel-layer timing that the export registry / contract card
 * cannot. Injected into the cacheable system-prompt region (byte-identical per run).
 */
@Slf4j
public final class PlannedComponentPropsCard {

    private final Map<String, String> propsByModule;   // @/alias -> "{ name: Type; ... }"
    private final List<String> withoutProps;           // component/page paths the plan gave no props
    private final List<String> opaqueProps;            // props that are a bare "<Name>Props" ref, not fields

    private PlannedComponentPropsCard(Map<String, String> propsByModule, List<String> withoutProps,
                                      List<String> opaqueProps) {
        this.propsByModule = propsByModule;
        this.withoutProps = withoutProps;
        this.opaqueProps = opaqueProps;
    }

    public boolean isEmpty()                    { return propsByModule.isEmpty(); }
    public int moduleCount()                    { return propsByModule.size(); }
    public List<String> componentsWithoutProps() { return withoutProps; }

    /**
     * Modules whose props are an un-enumerated interface reference (e.g. {@code { props:
     * ClassScheduleProps }}) rather than concrete fields — a fieldless contract that cannot pin the
     * parent/child binding. Expected to be empty once {@code SiblingContractReconciler} has run; a
     * non-empty list means a component slipped past reconciliation and will drift.
     */
    public List<String> componentsWithOpaqueProps() { return opaqueProps; }

    public static PlannedComponentPropsCard build(List<FileSpec> files) {
        Map<String, String> byModule = new TreeMap<>();
        List<String> without = new ArrayList<>();
        List<String> opaque = new ArrayList<>();
        if (files == null) return new PlannedComponentPropsCard(byModule, without, opaque);

        for (FileSpec f : files) {
            String path = f.getFilePath();
            if (path == null || !isComponentOrPage(path)) continue;
            String alias = toAlias(path);
            if (alias == null) continue;

            List<PublicFunction> pfs = f.getPublicFunctions();
            if (pfs == null || pfs.isEmpty()) { without.add(path); continue; }

            PublicFunction pf = pickComponentFn(pfs, path);
            List<String> params = pf.getParameters() == null ? List.of() : pf.getParameters();
            List<String> normalized = new ArrayList<>();
            for (String p : params) {
                String n = normalizeParam(p);
                if (!n.isBlank()) normalized.add(n);
            }
            // A component's props are ONE object. Some enrichments list the destructured fields directly
            // (isOpen, onClose, …); others emit a single `props: { … }` param. Unwrap the latter to the
            // fields, so a parent renders <Foo a={…} b={…} />, never <Foo props={{…}} />.
            String unwrapped = normalized.size() == 1 ? unwrapObjectProps(normalized.get(0)) : null;
            String body = unwrapped != null ? unwrapped
                    : normalized.isEmpty() ? "" : String.join("; ", normalized);
            // A single field typed as a bare "<Name>Props" interface is NOT a real contract — the card
            // can't see inside it, so parent and child each invent its fields (the ClassScheduleProps
            // drift). Flag it; SiblingContractReconciler should have replaced it with concrete fields.
            if (isOpaquePropsRef(normalized)) opaque.add(path);
            byModule.put(alias, body.isEmpty() ? "{}" : "{ " + body + " }");
        }
        return new PlannedComponentPropsCard(byModule, without, opaque);
    }

    /** True when props is a single field whose type is a bare {@code <Name>Props} interface reference. */
    private static boolean isOpaquePropsRef(List<String> normalized) {
        if (normalized.size() != 1) return false;
        String field = normalized.get(0);
        int colon = topLevelColon(field);
        if (colon < 0) return false;
        String type = field.substring(colon + 1).trim();
        return type.matches("[A-Z][A-Za-z0-9]*Props");
    }

    public String toPromptSection() {
        if (propsByModule.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("── PLANNED COMPONENT PROPS (ground truth — when you RENDER a component listed here, pass "
                + "EXACTLY these props; when you ARE one, declare EXACTLY these props. Never invent prop names) ──\n");
        propsByModule.forEach((mod, props) -> sb.append(mod).append(": ").append(props).append('\n'));
        sb.append("──────────────────────────────────────────────────────────────────────────────");
        return sb.toString();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static boolean isComponentOrPage(String path) {
        String p = path.replace('\\', '/');
        if (!p.contains("frontend/src/")) return false;
        if (p.contains("/components/ui/")) return false;               // shadcn — not app components
        return p.contains("/components/") || p.contains("/pages/");
    }

    /** The public_function describing this component — matched by name, else the first entry. */
    private static PublicFunction pickComponentFn(List<PublicFunction> pfs, String path) {
        String base = baseName(path);
        for (PublicFunction pf : pfs) {
            if (pf != null && base.equalsIgnoreCase(pf.getName())) return pf;
        }
        return pfs.get(0);
    }

    /** Normalizes "Type name" and "name: Type" (and function-typed) forms to a single "name: Type". */
    static String normalizeParam(String raw) {
        if (raw == null) return "";
        String p = collapse(raw);
        if (p.isEmpty()) return "";
        if (topLevelColon(p) >= 0) return p;                           // already name: Type
        int ws = lastTopLevelWhitespace(p);
        if (ws < 0) return p;                                          // single token — leave as-is
        String name = p.substring(ws + 1).trim();
        String type = p.substring(0, ws).trim();
        return (name.isEmpty() || type.isEmpty()) ? p : name + ": " + type;
    }

    /**
     * If a single param IS the props object ({@code props: { … }} or {@code props: ({ … })}), returns the
     * object's inner field list; else null (a genuine single named prop like {@code item: MenuItemDto} or
     * {@code images: GalleryImageDto[]} is left alone).
     */
    static String unwrapObjectProps(String param) {
        int colon = topLevelColon(param);
        String type = (colon >= 0 ? param.substring(colon + 1) : param).trim();
        if (type.startsWith("(") && type.endsWith(")")) type = type.substring(1, type.length() - 1).trim();
        if (type.startsWith("{") && type.endsWith("}")) return collapse(type.substring(1, type.length() - 1));
        return null;
    }

    private static int topLevelColon(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' || c == '(' || c == '{' || c == '[') depth++;
            else if (c == '>' || c == ')' || c == '}' || c == ']') depth = Math.max(0, depth - 1);
            else if (c == ':' && depth == 0) return i;
        }
        return -1;
    }

    private static int lastTopLevelWhitespace(String s) {
        int depth = 0, last = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' || c == '(' || c == '{' || c == '[') depth++;
            else if (c == '>' || c == ')' || c == '}' || c == ']') depth = Math.max(0, depth - 1);
            else if (Character.isWhitespace(c) && depth == 0) last = i;
        }
        return last;
    }

    private static String baseName(String path) {
        String p = path.replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1).replaceFirst("\\.(tsx?|jsx?)$", "");
    }

    private static String toAlias(String path) {
        String p = path.replace('\\', '/');
        int idx = p.indexOf("frontend/src/");
        if (idx < 0) return null;
        return "@/" + p.substring(idx + "frontend/src/".length()).replaceFirst("\\.(tsx?|jsx?)$", "");
    }

    private static String collapse(String s) { return s.trim().replaceAll("\\s+", " "); }
}
