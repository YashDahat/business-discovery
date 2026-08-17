package com.business.discovery.worker.util;

import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.service.llm.FileEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouteManifestGeneratorTest {

    private static FileEntry page(String name) {
        return new FileEntry("frontend/src/pages/" + name + ".tsx", FileType.FRONTEND, name);
    }

    private static RouteManifest manifest(String... pages) {
        return RouteManifest.fromSpec(
                java.util.Arrays.stream(pages).map(RouteManifestGeneratorTest::page).toList());
    }

    // ── name → route derivation ───────────────────────────────────────────

    @Test
    void derivesRoutesFromPageNames() {
        RouteManifest m = manifest("HomePage", "LoginPage", "MenuPage", "AdminDashboardPage",
                "AdminOrdersPage", "MenuItemDetailPage", "NotFoundPage");

        assertThat(route(m, "HomePage")).isEqualTo("/");
        assertThat(route(m, "LoginPage")).isEqualTo("/login");
        assertThat(route(m, "MenuPage")).isEqualTo("/menu");
        assertThat(route(m, "AdminDashboardPage")).isEqualTo("/admin");
        assertThat(route(m, "AdminOrdersPage")).isEqualTo("/admin/orders");
        assertThat(route(m, "MenuItemDetailPage")).isEqualTo("/menu-item/:id");
        assertThat(route(m, "NotFoundPage")).isEqualTo("*");
    }

    @Test
    void signupPageDerivesToSignupRouteNoNav() {
        RouteManifest m = manifest("SignupPage");
        assertThat(route(m, "SignupPage")).isEqualTo("/signup");
        assertThat(entry(m, "SignupPage").nav()).isFalse();
    }

    @Test
    void navFlagsFollowConvention() {
        RouteManifest m = manifest("HomePage", "LoginPage", "AdminOrdersPage", "MenuItemDetailPage",
                "NotFoundPage");

        assertThat(entry(m, "HomePage").nav()).isTrue();
        assertThat(entry(m, "LoginPage").nav()).isFalse();
        assertThat(entry(m, "AdminOrdersPage").nav()).isTrue();
        assertThat(entry(m, "AdminOrdersPage").admin()).isTrue();
        assertThat(entry(m, "MenuItemDetailPage").nav()).isFalse();
        assertThat(entry(m, "NotFoundPage").nav()).isFalse();
    }

    @Test
    void ignoresNonPageLayersAndDeduplicates() {
        RouteManifest m = RouteManifest.fromSpec(List.of(
                page("HomePage"), page("HomePage"),
                new FileEntry("frontend/src/components/menu/MenuTable.tsx", FileType.FRONTEND, "c"),
                new FileEntry("frontend/src/services/orderService.ts", FileType.FRONTEND, "s")));

        assertThat(m.entries()).hasSize(1);
        assertThat(m.entries().get(0).page()).isEqualTo("HomePage");
    }

    @Test
    void deterministicOrderHomeFirstAdminAfterCatchAllLast() {
        RouteManifest m = manifest("NotFoundPage", "AdminOrdersPage", "MenuPage", "HomePage");
        List<String> order = m.entries().stream().map(RouteManifest.Entry::page).toList();
        assertThat(order).containsExactly("HomePage", "MenuPage", "AdminOrdersPage", "NotFoundPage");
    }

    // ── routes.ts ─────────────────────────────────────────────────────────

    @Test
    void routesTsHasMarkerFirstLineAndZeroImports() {
        String ts = RouteManifestGenerator.emitRoutesTs(manifest("HomePage", "AdminOrdersPage"));

        assertThat(ts).startsWith(RouteManifest.PLAN_MARKER);
        assertThat(ts.lines().filter(l -> l.stripLeading().startsWith("import "))).isEmpty();
        assertThat(ts).contains("HOME: '/'")
                .contains("ADMIN_ORDERS: '/admin/orders'")
                .contains("export const routeTable: RouteEntry[]")
                .contains("importPath: './pages/AdminOrdersPage'");
    }

    // ── App.tsx shell (frozen) ──────────────────────────────────────────────

    @Test
    void appShellWiresProviderTreeAroundAppRoutes() {
        String shell = RouteManifestGenerator.emitAppShell(
                new RouteManifestGenerator.Flags(true, true, true, java.util.List.of()));

        assertThat(shell).startsWith(RouteManifestGenerator.APP_HEADER);
        // nesting: BrowserRouter outermost, then query, then auth, then AppProviders, then AppRoutes
        int router = shell.indexOf("<BrowserRouter>");
        int query = shell.indexOf("<QueryClientProvider");
        int auth = shell.indexOf("<AuthProvider>");
        int providers = shell.indexOf("<AppProviders>");
        int routes = shell.indexOf("<AppRoutes />");
        assertThat(router).isLessThan(query);
        assertThat(query).isLessThan(auth);
        assertThat(auth).isLessThan(providers);
        assertThat(providers).isLessThan(routes);

        // the shell holds NO route table — that lives in AppRoutes.tsx
        assertThat(shell).doesNotContain("<Route ").doesNotContain("<Routes>")
                .contains("import AppProviders from './AppProviders'")
                .contains("import AppRoutes from './AppRoutes'");
    }

    @Test
    void appShellOmitsProvidersWhenAbsent() {
        String shell = RouteManifestGenerator.emitAppShell(
                new RouteManifestGenerator.Flags(false, false, false, java.util.List.of()));

        assertThat(shell).doesNotContain("QueryClientProvider")
                .doesNotContain("AuthProvider")
                // AppProviders + AppRoutes are always mounted regardless of flags
                .contains("<AppProviders>")
                .contains("<AppRoutes />");
    }

    // ── AppRoutes.tsx (derived) ─────────────────────────────────────────────

    @Test
    void appRoutesEmitsRouteTableWithProtectionAndMarker() {
        RouteManifest m = manifest("HomePage", "AdminOrdersPage");
        String routes = RouteManifestGenerator.emitAppRoutes(m,
                new RouteManifestGenerator.Flags(true, true, true, java.util.List.of()));

        assertThat(routes).startsWith(RouteManifest.PLAN_MARKER);
        assertThat(routes).contains("export default function AppRoutes()")
                .contains("import HomePage from './pages/HomePage';")
                .contains("<Route path=\"/\" element={<HomePage />} />")
                .contains("<Route path=\"/admin/orders\" element={<ProtectedRoute><AdminOrdersPage /></ProtectedRoute>} />");
    }

    @Test
    void appRoutesOmitsProtectionWhenAbsent() {
        String routes = RouteManifestGenerator.emitAppRoutes(manifest("HomePage", "AdminOrdersPage"),
                new RouteManifestGenerator.Flags(false, false, false, java.util.List.of()));

        assertThat(routes).doesNotContain("ProtectedRoute")
                .contains("<Route path=\"/admin/orders\" element={<AdminOrdersPage />} />");
    }

    // ── AppProviders.tsx (derived) ──────────────────────────────────────────

    @Test
    void appProvidersMountsDiscoveredContextProvidersInOrder() {
        var providers = java.util.List.of(
                new RouteManifestGenerator.ProviderRef("CartProvider", "./context/CartContext"));
        String app = RouteManifestGenerator.emitAppProviders(
                new RouteManifestGenerator.Flags(true, false, true, providers));

        assertThat(app).startsWith(RouteManifest.PLAN_MARKER);
        assertThat(app).contains("import { CartProvider } from './context/CartContext'")
                .contains("export default function AppProviders(");
        // wraps {children} so useCart() never throws for any routed page
        int cartOpen = app.indexOf("<CartProvider>");
        int children = app.indexOf("{children}");
        int cartClose = app.indexOf("</CartProvider>");
        assertThat(cartOpen).isLessThan(children);
        assertThat(children).isLessThan(cartClose);
    }

    @Test
    void appProvidersPassesThroughWhenNoneDiscovered() {
        String app = RouteManifestGenerator.emitAppProviders(
                new RouteManifestGenerator.Flags(true, false, true, java.util.List.of()));

        assertThat(app).contains("<>{children}</>");
    }

    @Test
    void discoverContextProviders_findsExportedProviders_excludesAuth(@org.junit.jupiter.api.io.TempDir java.nio.file.Path src) throws Exception {
        java.nio.file.Files.createDirectories(src.resolve("context"));
        java.nio.file.Files.writeString(src.resolve("context/CartContext.tsx"),
                "export const CartProvider = ({ children }) => children;\nexport const useCart = () => {};\n");
        java.nio.file.Files.writeString(src.resolve("context/AuthContext.tsx"),
                "export const AuthProvider = ({ children }) => children;\n");

        var found = RouteManifestGenerator.discoverContextProviders(src);

        assertThat(found).extracting(RouteManifestGenerator.ProviderRef::name).containsExactly("CartProvider");
        assertThat(found.get(0).importPath()).isEqualTo("./context/CartContext");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static RouteManifest.Entry entry(RouteManifest m, String pageName) {
        return m.entries().stream().filter(e -> e.page().equals(pageName)).findFirst().orElseThrow();
    }

    private static String route(RouteManifest m, String pageName) {
        return entry(m, pageName).path();
    }
}
