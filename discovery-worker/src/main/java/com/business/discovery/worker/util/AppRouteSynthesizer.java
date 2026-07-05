package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Deterministic repair for the blank-SPA failure: when App.tsx has no router wiring
 * (the LLM shipped `return <div/>` while page content sits in unrouted files), rebuild
 * App.tsx from the page files on disk + the app's own link graph.
 *
 * This turns a task-killing failure into an in-place fix — the same hand-repair that
 * worked on multifit-aundh, made mechanical. Runs as an unconditional pre-pass before
 * the frontend build (a blank App.tsx compiles, so it would otherwise sail past the
 * build and only trip the routing assertion at the end).
 *
 * Route paths come from the link graph (to=/navigate targets — authoritative for what
 * the UI actually navigates to); each linked path is matched to its best page component,
 * and any page not otherwise linked gets its natural path too, so nothing is unreachable.
 */
@Slf4j
public final class AppRouteSynthesizer {

    private static final Pattern PAGE_DEFAULT_EXPORT =
            Pattern.compile("export\\s+default\\s+(?:function\\s+)?([A-Z][A-Za-z0-9_]*)");
    private static final Pattern LINK_TARGET =
            Pattern.compile("(?:to|href)=\"(/[A-Za-z0-9/_-]*)\"|navigate\\(\\s*[\"'`](/[A-Za-z0-9/_-]*)");

    private AppRouteSynthesizer() {}

    private record PageInfo(String component, String importPath, List<String> tokens, boolean admin) {}

    /** Returns true if App.tsx was (re)written. */
    public static boolean fixIfMissingRoutes(Path frontendSrc) {
        Path appTsx = frontendSrc.resolve("App.tsx");
        try {
            if (Files.exists(appTsx)) {
                String content = Files.readString(appTsx);
                if (content.contains("<Route") || content.contains("createBrowserRouter")
                        || content.contains("RouterProvider")) {
                    return false; // already routed — leave the model's work alone
                }
            }
            List<PageInfo> pages = scanPages(frontendSrc);
            if (pages.isEmpty()) {
                log.warn("[AppRouteSynthesizer] No page components found — cannot synthesize App.tsx");
                return false;
            }
            String synthesized = synthesize(frontendSrc, pages);
            Files.writeString(appTsx, synthesized);
            log.info("[AppRouteSynthesizer] Rebuilt blank App.tsx with {} routes from page files + link graph",
                    pages.size());
            return true;
        } catch (IOException e) {
            log.warn("[AppRouteSynthesizer] Could not synthesize App.tsx: {}", e.getMessage());
            return false;
        }
    }

    // ── Discovery ─────────────────────────────────────────────────────────

    private static List<PageInfo> scanPages(Path frontendSrc) throws IOException {
        Path pagesDir = frontendSrc.resolve("pages");
        if (!Files.exists(pagesDir)) return List.of();
        List<PageInfo> pages = new ArrayList<>();
        try (Stream<Path> s = Files.walk(pagesDir)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".tsx")).toList()) {
                String content = Files.readString(f);
                Matcher m = PAGE_DEFAULT_EXPORT.matcher(content);
                if (!m.find()) continue;
                String component = m.group(1);
                String rel = frontendSrc.relativize(f).toString().replace(".tsx", "").replace('\\', '/');
                boolean admin = rel.toLowerCase().contains("admin");
                pages.add(new PageInfo(component, "./" + rel, tokens(component), admin));
            }
        }
        return pages;
    }

    private static Set<String> scanLinkTargets(Path frontendSrc) throws IOException {
        Set<String> targets = new TreeSet<>();
        try (Stream<Path> s = Files.walk(frontendSrc)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".tsx") || p.toString().endsWith(".ts")).toList()) {
                Matcher m = LINK_TARGET.matcher(Files.readString(f));
                while (m.find()) {
                    String t = m.group(1) != null ? m.group(1) : m.group(2);
                    if (t != null && !t.contains("#")) targets.add(t);
                }
            }
        }
        return targets;
    }

    // ── Synthesis ─────────────────────────────────────────────────────────

    private static String synthesize(Path frontendSrc, List<PageInfo> pages) throws IOException {
        boolean hasAuth = Files.exists(frontendSrc.resolve("context/AuthContext.tsx"));
        boolean hasProtected = Files.exists(frontendSrc.resolve("components/ProtectedRoute.tsx"));
        boolean hasQuery = Files.exists(frontendSrc.resolve("api/client.ts"))
                || hasDependency(frontendSrc, "@tanstack/react-query");

        // path -> page, preferring the link graph, then each page's natural path
        Map<String, PageInfo> routes = new LinkedHashMap<>();
        PageInfo home = pickHome(pages);
        routes.put("/", home);

        for (String target : scanLinkTargets(frontendSrc)) {
            if (routes.containsKey(target)) continue;
            PageInfo best = bestMatch(target, pages);
            if (best != null) routes.put(target, best);
        }
        for (PageInfo p : pages) {
            String natural = naturalPath(p);
            routes.putIfAbsent(natural, p);
        }

        // Build the file
        StringBuilder imports = new StringBuilder();
        List<PageInfo> ordered = pages.stream().distinct().toList();
        for (PageInfo p : ordered) {
            imports.append("import ").append(p.component()).append(" from '").append(p.importPath()).append("';\n");
        }

        StringBuilder routeLines = new StringBuilder();
        routes.forEach((path, page) -> {
            String element = "<" + page.component() + " />";
            if (page.admin() && hasProtected) {
                element = "<ProtectedRoute>" + element + "</ProtectedRoute>";
            }
            routeLines.append("            <Route path=\"").append(path).append("\" element={")
                      .append(element).append("} />\n");
        });

        StringBuilder sb = new StringBuilder();
        sb.append("import './index.css'\n");
        sb.append("import { BrowserRouter, Routes, Route } from 'react-router-dom'\n");
        if (hasQuery) sb.append("import { QueryClient, QueryClientProvider } from '@tanstack/react-query'\n");
        if (hasAuth) sb.append("import { AuthProvider } from './context/AuthContext'\n");
        if (hasProtected) sb.append("import ProtectedRoute from './components/ProtectedRoute'\n");
        sb.append("\n").append(imports).append("\n");
        if (hasQuery) sb.append("const queryClient = new QueryClient()\n\n");
        sb.append("export default function App() {\n  return (\n");

        // Nesting: BrowserRouter OUTERMOST (contexts calling router hooks must be inside it)
        String indent = "    ";
        List<String> open = new ArrayList<>();
        List<String> close = new ArrayList<>();
        open.add("<BrowserRouter>"); close.add(0, "</BrowserRouter>");
        if (hasQuery) { open.add("<QueryClientProvider client={queryClient}>"); close.add(0, "</QueryClientProvider>"); }
        if (hasAuth) { open.add("<AuthProvider>"); close.add(0, "</AuthProvider>"); }

        for (int k = 0; k < open.size(); k++) sb.append(indent).append("  ".repeat(k)).append(open.get(k)).append("\n");
        String routesIndent = indent + "  ".repeat(open.size());
        sb.append(routesIndent).append("<Routes>\n");
        sb.append(routeLines);
        sb.append(routesIndent).append("</Routes>\n");
        for (int k = 0; k < close.size(); k++) {
            sb.append(indent).append("  ".repeat(open.size() - 1 - k)).append(close.get(k)).append("\n");
        }
        sb.append("  )\n}\n");
        return sb.toString();
    }

    // ── Matching helpers ──────────────────────────────────────────────────

    private static PageInfo pickHome(List<PageInfo> pages) {
        return pages.stream().filter(p -> p.component().toLowerCase().contains("home")).findFirst()
                .orElse(pages.stream().filter(p -> !p.admin()).findFirst().orElse(pages.get(0)));
    }

    private static PageInfo bestMatch(String path, List<PageInfo> pages) {
        List<String> pathTokens = tokensOf(path);
        boolean adminPath = path.startsWith("/admin");
        PageInfo best = null;
        int bestScore = 0;
        for (PageInfo p : pages) {
            if (adminPath != p.admin()) continue; // keep admin/public separate
            int score = overlap(pathTokens, p.tokens());
            if (score > bestScore) { bestScore = score; best = p; }
        }
        // "/admin" with no strong token match → the dashboard/first admin page
        if (best == null && adminPath) {
            best = pages.stream().filter(p -> p.admin() && p.component().toLowerCase().contains("dashboard"))
                    .findFirst().orElse(pages.stream().filter(PageInfo::admin).findFirst().orElse(null));
        }
        return best;
    }

    private static String naturalPath(PageInfo p) {
        // AdminLeadsPage -> /admin/leads ; TrainersPage -> /trainers ; HomePage -> /
        List<String> t = new ArrayList<>(p.tokens());
        if (t.isEmpty()) return "/" + p.component().toLowerCase();
        if (t.size() == 1 && t.get(0).equals("home")) return "/";
        if (p.admin()) {
            List<String> rest = t.stream().filter(x -> !x.equals("admin")).toList();
            return "/admin" + (rest.isEmpty() ? "" : "/" + String.join("-", rest));
        }
        return "/" + String.join("-", t);
    }

    private static List<String> tokens(String component) {
        String base = component.replaceAll("Page$", "");
        List<String> out = new ArrayList<>();
        for (String part : base.split("(?<=[a-z])(?=[A-Z])")) {
            if (!part.isEmpty()) out.add(part.toLowerCase());
        }
        return out;
    }

    private static List<String> tokensOf(String path) {
        List<String> out = new ArrayList<>();
        for (String part : path.split("/")) if (!part.isEmpty()) {
            // singularize simple plurals so /trainers matches "trainer(s)"
            out.add(part.toLowerCase());
            if (part.endsWith("s")) out.add(part.substring(0, part.length() - 1).toLowerCase());
        }
        return out;
    }

    private static int overlap(List<String> a, List<String> b) {
        int score = 0;
        for (String x : a) for (String y : b) if (x.equals(y)) score++;
        return score;
    }

    private static boolean hasDependency(Path frontendSrc, String dep) {
        try {
            Path pkg = frontendSrc.getParent().resolve("package.json");
            return Files.exists(pkg) && Files.readString(pkg).contains("\"" + dep + "\"");
        } catch (IOException e) {
            return false;
        }
    }
}
