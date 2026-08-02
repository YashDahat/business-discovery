package com.business.discovery.worker.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Emits the two files derived from the {@link RouteManifest} — sibling of
 * {@link TsSdkGenerator}, applied to navigation instead of the wire contract.
 *
 * routes.ts carries ZERO imports (paths + string metadata only) so any component may
 * import ROUTES without creating routes.ts → pages → components → routes.ts, which the
 * eslint import-x/no-cycle gate would fail the build on. App.tsx does the page imports.
 *
 * App.tsx is templated, not LLM-generated: every input it needs is known from the plan,
 * and one Flash call holding the whole route table is how six compiled pages became
 * unreachable blank screens (circuit-house registered 1 admin route of 7).
 */
public final class RouteManifestGenerator {

    /**
     * First line of the templated App.tsx. Deliberately NOT {@link RouteManifest#PLAN_MARKER}
     * — ErrorFixAgent keeps its escape hatch on App.tsx, and every attempt re-derives it anyway.
     */
    public static final String APP_HEADER =
            "// Assembled from routes.ts by the route registry — re-derived every attempt.";

    /**
     * A React context provider the LLM authored under src/context (or src/providers) that App.tsx must
     * mount, or any component calling its hook throws "useX must be used within a XProvider" at runtime.
     * AuthProvider is handled by {@link Flags#hasAuth} and is intentionally excluded from this list.
     */
    public record ProviderRef(String name, String importPath) {}

    /** Provider wiring flags — from the plan on first emit, from disk on reconciliation. */
    public record Flags(boolean hasAuth, boolean hasProtected, boolean hasQuery,
                        List<ProviderRef> contextProviders) {

        public static Flags fromDisk(Path frontendSrc) {
            boolean auth = Files.exists(frontendSrc.resolve("context/AuthContext.tsx"));
            boolean prot = Files.exists(frontendSrc.resolve("components/ProtectedRoute.tsx"));
            boolean query = Files.exists(frontendSrc.resolve("api/client.ts"));
            return new Flags(auth, prot, query, discoverContextProviders(frontendSrc));
        }
    }

    private RouteManifestGenerator() {}

    // ── Context provider discovery ─────────────────────────────────────────

    /** Matches an exported provider component: `export const XProvider`, `export function XProvider`. */
    private static final Pattern EXPORTED_PROVIDER =
            Pattern.compile("export\\s+(?:const|function)\\s+([A-Z]\\w*Provider)\\b");

    /**
     * Scans src/context and src/providers for every exported {@code *Provider} the LLM wrote, so
     * App.tsx can mount each one. This is what keeps a new provider (Cart, Theme, Toast, ...) from
     * shipping unmounted: App.tsx is worker-derived and the LLM cannot edit it, so discovery — not
     * a hardcoded whitelist — is the only thing that connects an LLM-authored provider to the tree.
     * AuthProvider is excluded (mounted via {@link Flags#hasAuth}); results are sorted for a stable emit.
     */
    public static List<ProviderRef> discoverContextProviders(Path frontendSrc) {
        TreeMap<String, ProviderRef> found = new TreeMap<>(); // sorted + de-duped by provider name
        for (String dir : List.of("context", "providers", "cart")) {
            Path base = frontendSrc.resolve(dir);
            if (!Files.isDirectory(base)) continue;
            try (Stream<Path> files = Files.list(base)) {
                files.filter(p -> p.toString().endsWith(".tsx") || p.toString().endsWith(".ts"))
                     .forEach(p -> collectProviders(p, dir, found));
            } catch (IOException ignored) {
                // a missing/unreadable context dir simply yields no extra providers
            }
        }
        found.remove("AuthProvider"); // already wired via Flags.hasAuth — never mount it twice
        return new ArrayList<>(found.values());
    }

    private static void collectProviders(Path file, String dir, TreeMap<String, ProviderRef> out) {
        String fileName = file.getFileName().toString();
        String moduleName = fileName.replaceFirst("\\.tsx?$", "");
        try {
            Matcher m = EXPORTED_PROVIDER.matcher(Files.readString(file));
            while (m.find()) {
                String name = m.group(1);
                out.putIfAbsent(name, new ProviderRef(name, "./" + dir + "/" + moduleName));
            }
        } catch (IOException ignored) {
            // unreadable file → contributes no providers
        }
    }

    // ── routes.ts ─────────────────────────────────────────────────────────

    public static String emitRoutesTs(RouteManifest manifest) {
        StringBuilder sb = new StringBuilder();
        sb.append(RouteManifest.PLAN_MARKER).append('\n')
          .append("// The complete navigation contract: every page, its route, and its nav\n")
          .append("// metadata. Link via ROUTES.*, render nav from routeTable — never hardcode\n")
          .append("// a path string. This file imports NOTHING by design (cycle-safe).\n\n");

        sb.append("export const ROUTES = {\n");
        for (RouteManifest.Entry e : manifest.entries()) {
            sb.append("  ").append(e.key()).append(": '").append(e.path()).append("',\n");
        }
        sb.append("} as const;\n\n");

        sb.append("export interface RouteEntry {\n")
          .append("  key: keyof typeof ROUTES;\n")
          .append("  path: string;\n")
          .append("  page: string;        // component name, e.g. 'AdminOrdersPage'\n")
          .append("  importPath: string;  // string metadata only — App.tsx does the importing\n")
          .append("  label: string;\n")
          .append("  admin: boolean;\n")
          .append("  nav: boolean;\n")
          .append("}\n\n");

        sb.append("export const routeTable: RouteEntry[] = [\n");
        for (RouteManifest.Entry e : manifest.entries()) {
            sb.append("  { key: '").append(e.key())
              .append("', path: ROUTES.").append(e.key())
              .append(", page: '").append(e.page())
              .append("', importPath: '").append(e.importPath())
              .append("', label: '").append(e.label().replace("'", "\\'"))
              .append("', admin: ").append(e.admin())
              .append(", nav: ").append(e.nav())
              .append(" },\n");
        }
        sb.append("];\n");
        return sb.toString();
    }

    // ── App.tsx ───────────────────────────────────────────────────────────

    /**
     * Provider nesting identical to what AppRouteSynthesizer proved out on multifit-aundh:
     * BrowserRouter OUTERMOST (contexts calling router hooks must sit inside it), then
     * QueryClientProvider, then AuthProvider, then Routes.
     */
    public static String emitAppTsx(RouteManifest manifest, Flags flags) {
        StringBuilder sb = new StringBuilder();
        sb.append(APP_HEADER).append('\n');
        sb.append("import './index.css'\n");
        sb.append("import { BrowserRouter, Routes, Route, Outlet } from 'react-router-dom'\n");
        if (flags.hasQuery()) {
            sb.append("import { QueryClient, QueryClientProvider } from '@tanstack/react-query'\n");
        }
        if (flags.hasAuth()) sb.append("import { AuthProvider } from './context/AuthContext'\n");
        if (flags.hasProtected()) sb.append("import ProtectedRoute from './components/ProtectedRoute'\n");
        for (ProviderRef p : flags.contextProviders()) {
            sb.append("import { ").append(p.name()).append(" } from '").append(p.importPath()).append("'\n");
        }
        // Foundation shell — SiteLayout wraps all public routes; admin routes use their own layout
        sb.append("import { SiteLayout } from '@/shell'\n");
        sb.append("import siteConfig from '@/config/siteConfig'\n");
        sb.append('\n');
        for (RouteManifest.Entry e : manifest.entries()) {
            sb.append("import ").append(e.page()).append(" from '").append(e.importPath()).append("';\n");
        }
        sb.append('\n');
        if (flags.hasQuery()) sb.append("const queryClient = new QueryClient()\n\n");

        sb.append("export default function App() {\n  return (\n");
        java.util.List<String> open = new java.util.ArrayList<>();
        java.util.List<String> close = new java.util.ArrayList<>();
        open.add("<BrowserRouter>"); close.add(0, "</BrowserRouter>");
        if (flags.hasQuery()) {
            open.add("<QueryClientProvider client={queryClient}>");
            close.add(0, "</QueryClientProvider>");
        }
        if (flags.hasAuth()) { open.add("<AuthProvider>"); close.add(0, "</AuthProvider>"); }
        for (ProviderRef p : flags.contextProviders()) {
            open.add("<" + p.name() + ">");
            close.add(0, "</" + p.name() + ">");
        }

        String indent = "    ";
        for (int k = 0; k < open.size(); k++) {
            sb.append(indent).append("  ".repeat(k)).append(open.get(k)).append('\n');
        }
        String routesIndent = indent + "  ".repeat(open.size());

        // Split routes into admin and public.
        // Admin routes keep their AdminLayout (generated page components include it).
        // Public routes are wrapped in <SiteLayout config={siteConfig}> so Header + Footer
        // appear globally without every page component needing to import Layout individually.
        java.util.List<RouteManifest.Entry> adminRoutes = manifest.entries().stream()
                .filter(RouteManifest.Entry::admin).toList();
        java.util.List<RouteManifest.Entry> publicRoutes = manifest.entries().stream()
                .filter(e -> !e.admin()).toList();

        sb.append(routesIndent).append("<Routes>\n");

        // Admin routes — no SiteLayout, pages use AdminLayout internally
        for (RouteManifest.Entry e : adminRoutes) {
            String element = "<" + e.page() + " />";
            if (flags.hasProtected()) element = "<ProtectedRoute>" + element + "</ProtectedRoute>";
            sb.append(routesIndent).append("  <Route path=\"").append(e.path())
              .append("\" element={").append(element).append("} />\n");
        }

        // Public routes — wrapped in SiteLayout shell (Header + Footer from foundation)
        if (!publicRoutes.isEmpty()) {
            sb.append(routesIndent).append("  <Route element={<SiteLayout config={siteConfig}><Outlet /></SiteLayout>}>\n");
            sb.append(routesIndent).append("    {/* Outlet receives the matched child route */}\n");
            for (RouteManifest.Entry e : publicRoutes) {
                sb.append(routesIndent).append("    <Route path=\"").append(e.path())
                  .append("\" element={<").append(e.page()).append(" />} />\n");
            }
            sb.append(routesIndent).append("  </Route>\n");
        }

        sb.append(routesIndent).append("</Routes>\n");
        for (int k = 0; k < close.size(); k++) {
            sb.append(indent).append("  ".repeat(open.size() - 1 - k)).append(close.get(k)).append('\n');
        }
        sb.append("  )\n}\n");
        return sb.toString();
    }
}
