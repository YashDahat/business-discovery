package com.business.discovery.worker.util;

import java.nio.file.Files;
import java.nio.file.Path;

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

    /** Provider wiring flags — from the plan on first emit, from disk on reconciliation. */
    public record Flags(boolean hasAuth, boolean hasProtected, boolean hasQuery) {

        public static Flags fromDisk(Path frontendSrc) {
            boolean auth = Files.exists(frontendSrc.resolve("context/AuthContext.tsx"));
            boolean prot = Files.exists(frontendSrc.resolve("components/ProtectedRoute.tsx"));
            boolean query = Files.exists(frontendSrc.resolve("api/client.ts"));
            return new Flags(auth, prot, query);
        }
    }

    private RouteManifestGenerator() {}

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
        sb.append("import { BrowserRouter, Routes, Route } from 'react-router-dom'\n");
        if (flags.hasQuery()) {
            sb.append("import { QueryClient, QueryClientProvider } from '@tanstack/react-query'\n");
        }
        if (flags.hasAuth()) sb.append("import { AuthProvider } from './context/AuthContext'\n");
        if (flags.hasProtected()) sb.append("import ProtectedRoute from './components/ProtectedRoute'\n");
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

        String indent = "    ";
        for (int k = 0; k < open.size(); k++) {
            sb.append(indent).append("  ".repeat(k)).append(open.get(k)).append('\n');
        }
        String routesIndent = indent + "  ".repeat(open.size());
        sb.append(routesIndent).append("<Routes>\n");
        for (RouteManifest.Entry e : manifest.entries()) {
            String element = "<" + e.page() + " />";
            if (e.admin() && flags.hasProtected()) {
                element = "<ProtectedRoute>" + element + "</ProtectedRoute>";
            }
            sb.append(routesIndent).append("  <Route path=\"").append(e.path())
              .append("\" element={").append(element).append("} />\n");
        }
        sb.append(routesIndent).append("</Routes>\n");
        for (int k = 0; k < close.size(); k++) {
            sb.append(indent).append("  ".repeat(open.size() - 1 - k)).append(close.get(k)).append('\n');
        }
        sb.append("  )\n}\n");
        return sb.toString();
    }
}
