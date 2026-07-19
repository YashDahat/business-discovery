package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.FileEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The navigation contract, derived from the plan's PAGE entries — the same move
 * {@link ApiInventory} makes for the API contract, applied to routing.
 *
 * Derived from the PLAN rather than from disk deliberately: a component being written
 * needs to know where it can link TO, but pages are generated after components, so a
 * manifest scanned off pages/ would not exist yet. The plan names every page before a
 * single file is written.
 *
 * The route table used to exist in quadruplicate — App.tsx (Flash wrote it, registered
 * 1 admin route of 7), AdminLayout's hardcoded sidebar, bare string literals in every
 * component, and the pages/ directory itself — with nothing reconciling them. This class
 * is the single source of truth all four are now derived from.
 */
public final class RouteManifest {

    /** Prompt-section key. Named so it reads as law, not as a hint. */
    public static final String PROMPT_KEY =
            "ROUTE MANIFEST (ground truth — derived from the plan, NOT editable)";

    /** First line of routes.ts — ErrorFixAgent refuses to edit files carrying it. */
    public static final String PLAN_MARKER =
            "// GENERATED from the architecture plan — do not edit by hand.";

    /**
     * One page, one route. importPath is a plain string ('./pages/AdminOrdersPage')
     * — routes.ts must emit ZERO import statements or the eslint import-x/no-cycle gate
     * would flag routes.ts → pages → components → routes.ts. Only App.tsx imports pages.
     */
    public record Entry(String key, String path, String page, String importPath,
                        String label, boolean admin, boolean nav) {}

    private final List<Entry> entries;

    private RouteManifest(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<Entry> entries() { return entries; }

    public boolean isEmpty() { return entries.isEmpty(); }

    public boolean containsPage(String pageComponent) {
        return entries.stream().anyMatch(e -> e.page().equals(pageComponent));
    }

    /** Manifest plus extra disk-discovered pages (reconciliation appends, never replaces). */
    public RouteManifest withAdditional(List<Entry> extra) {
        if (extra.isEmpty()) return this;
        List<Entry> merged = new ArrayList<>(entries);
        for (Entry e : extra) {
            if (!containsPage(e.page())) merged.add(e);
        }
        return new RouteManifest(merged);
    }

    // ── Derivation ────────────────────────────────────────────────────────

    /** PAGE-layer entries out of the manifest, in a deterministic order (prefix-cache safe). */
    public static RouteManifest fromSpec(List<FileEntry> frontendFiles) {
        Set<String> seenPages = new LinkedHashSet<>();
        List<Entry> out = new ArrayList<>();
        for (FileEntry file : frontendFiles) {
            if (LayerOrderUtil.frontendPriority(file) != 70) continue;
            Entry entry = fromPagePath(file.path());
            if (entry != null && seenPages.add(entry.page())) out.add(entry);
        }
        out.sort(ORDER);
        return new RouteManifest(out);
    }

    /** Same derivation for a single page file discovered on disk (reconciliation path). */
    public static Entry fromPagePath(String path) {
        String normalized = path.replace('\\', '/');
        int idx = normalized.indexOf("src/pages/");
        String rel;
        if (idx >= 0) {
            rel = normalized.substring(idx + "src/".length());
        } else if (normalized.startsWith("pages/")) {
            rel = normalized;
        } else {
            rel = "pages/" + normalized.substring(normalized.lastIndexOf('/') + 1);
        }
        if (!rel.endsWith(".tsx")) return null;
        String importPath = "./" + rel.substring(0, rel.length() - ".tsx".length());
        String component = importPath.substring(importPath.lastIndexOf('/') + 1);
        if (component.isEmpty() || !Character.isUpperCase(component.charAt(0))) return null;
        return derive(component, importPath);
    }

    // Home first, then public routes by path, admin routes after, catch-all last.
    private static final Comparator<Entry> ORDER = Comparator
            .comparing((Entry e) -> "*".equals(e.path()))
            .thenComparing(Entry::admin)
            .thenComparing(e -> !"/".equals(e.path()))
            .thenComparing(Entry::path);

    /**
     * PAGE NAMING → ROUTE convention (documented in arch_outline so the planner names
     * pages accordingly):
     *   HomePage           → /                (nav "Home")
     *   LoginPage          → /login           (no nav)
     *   AdminDashboardPage → /admin           (admin, nav "Dashboard")
     *   Admin<X>Page       → /admin/<x-kebab> (admin, nav)
     *   <X>DetailPage      → /<x-kebab>/:id   (no nav — param routes never appear in nav)
     *   NotFoundPage       → *                (no nav)
     *   <X>Page            → /<x-kebab>       (nav)
     */
    private static Entry derive(String component, String importPath) {
        List<String> tokens = tokens(component);
        boolean admin = !tokens.isEmpty() && tokens.get(0).equals("admin");
        List<String> rest = admin ? tokens.subList(1, tokens.size()) : tokens;

        boolean detail = !rest.isEmpty() && rest.get(rest.size() - 1).equals("detail");
        if (detail) rest = rest.subList(0, rest.size() - 1);

        String key = String.join("_", tokens).toUpperCase();
        String label = title(rest.isEmpty() ? tokens : rest);

        String path;
        boolean nav;
        if (!admin && rest.size() == 1 && rest.get(0).equals("home")) {
            path = "/";
            nav = true;
        } else if (!admin && rest.size() == 1 && rest.get(0).equals("login")) {
            path = "/login";
            nav = false;
        } else if (!admin && tokens.equals(List.of("not", "found"))) {
            path = "*";
            nav = false;
            key = "NOT_FOUND";
            label = "Not Found";
        } else if (admin) {
            path = rest.isEmpty() || (rest.size() == 1 && rest.get(0).equals("dashboard"))
                    ? "/admin" : "/admin/" + String.join("-", rest);
            nav = !detail;
        } else {
            path = "/" + String.join("-", rest);
            nav = !detail;
        }
        if (detail) path = path + "/:id";
        return new Entry(key, path, component, importPath, label, admin, nav);
    }

    private static List<String> tokens(String component) {
        String base = component.replaceAll("Page$", "");
        List<String> out = new ArrayList<>();
        for (String part : base.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) {
            if (!part.isEmpty()) out.add(part.toLowerCase());
        }
        return out;
    }

    private static String title(List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(t.charAt(0))).append(t.substring(1));
        }
        return sb.toString();
    }

    // ── Prompt card ───────────────────────────────────────────────────────

    /**
     * Injected into the cacheable system prompt of every frontend call, beside the API
     * contract card. A component is SHOWN the legal destinations before it is asked to
     * link to one — Flash cannot invent /admin/orders when the manifest never offered it.
     */
    public String toPromptSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("These routes ALREADY EXIST in frontend/src/routes.ts, derived from the plan.\n")
          .append("They are the COMPLETE route set — a route not listed below DOES NOT EXIST. Rules:\n")
          .append("  - Every <Link to=...> and navigate(...) target MUST be ROUTES.<KEY> imported\n")
          .append("    from '@/routes' — never a bare path string literal.\n")
          .append("  - Header/public nav: routeTable.filter(r => r.nav && !r.admin).\n")
          .append("    AdminLayout sidebar: routeTable.filter(r => r.nav && r.admin).\n")
          .append("  - Never write App.tsx or routes.ts — both are generated from the plan.\n\n")
          .append("── ROUTES (key · path · page · flags) ──\n");
        for (Entry e : entries) {
            sb.append("  ROUTES.").append(e.key()).append(" = \"").append(e.path()).append("\"  → ")
              .append(e.page());
            if (e.admin()) sb.append("  [admin]");
            if (e.nav()) sb.append("  [nav: \"").append(e.label()).append("\"]");
            sb.append('\n');
        }
        return sb.toString();
    }
}
