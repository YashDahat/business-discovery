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

    // ── App.tsx ───────────────────────────────────────────────────────────

    @Test
    void appTsxWiresProvidersRoutesAndProtection() {
        RouteManifest m = manifest("HomePage", "AdminOrdersPage");
        String app = RouteManifestGenerator.emitAppTsx(m,
                new RouteManifestGenerator.Flags(true, true, true, java.util.List.of()));

        assertThat(app).startsWith(RouteManifestGenerator.APP_HEADER);
        // nesting: BrowserRouter outermost, then query, then auth
        int router = app.indexOf("<BrowserRouter>");
        int query = app.indexOf("<QueryClientProvider");
        int auth = app.indexOf("<AuthProvider>");
        int routes = app.indexOf("<Routes>");
        assertThat(router).isLessThan(query);
        assertThat(query).isLessThan(auth);
        assertThat(auth).isLessThan(routes);

        assertThat(app).contains("import HomePage from './pages/HomePage';")
                .contains("<Route path=\"/\" element={<HomePage />} />")
                .contains("<Route path=\"/admin/orders\" element={<ProtectedRoute><AdminOrdersPage /></ProtectedRoute>} />");
    }

    @Test
    void appTsxOmitsProvidersWhenAbsent() {
        String app = RouteManifestGenerator.emitAppTsx(manifest("HomePage", "AdminOrdersPage"),
                new RouteManifestGenerator.Flags(false, false, false, java.util.List.of()));

        assertThat(app).doesNotContain("QueryClientProvider")
                .doesNotContain("AuthProvider")
                .doesNotContain("ProtectedRoute")
                .contains("<Route path=\"/admin/orders\" element={<AdminOrdersPage />} />");
    }

    @Test
    void appTsxMountsDiscoveredContextProviders() {
        RouteManifest m = manifest("HomePage", "OrderPage");
        var providers = java.util.List.of(
                new RouteManifestGenerator.ProviderRef("CartProvider", "./context/CartContext"));
        String app = RouteManifestGenerator.emitAppTsx(m,
                new RouteManifestGenerator.Flags(true, false, true, providers));

        assertThat(app).contains("import { CartProvider } from './context/CartContext'");
        // mounted inside AuthProvider, still wrapping <Routes> — so useCart() never throws
        int auth = app.indexOf("<AuthProvider>");
        int cartOpen = app.indexOf("<CartProvider>");
        int routes = app.indexOf("<Routes>");
        int cartClose = app.indexOf("</CartProvider>");
        assertThat(auth).isLessThan(cartOpen);
        assertThat(cartOpen).isLessThan(routes);
        assertThat(routes).isLessThan(cartClose);
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
