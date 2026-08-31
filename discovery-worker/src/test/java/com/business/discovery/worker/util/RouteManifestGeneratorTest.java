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

    // ── verification against the real abs-fitness 9312afa6 generation ──────

    /**
     * The exact 23-page set from https://github.com/YashDahat/abs-fitness (branch
     * feature/gym-full_platform-9312afa6, frontend/src/routes.ts). Proves the mechanical layer
     * derives the right gate/nav and buckets AppRoutes correctly for the real generation that failed.
     */
    @Test
    void verifiesMechanicalLayerAgainstRealAbsFitnessGeneration() {
        RouteManifest m = RouteManifest.fromSpec(List.of(
                page("HomePage"), page("AboutPage"), page("AccountPage"), page("CheckoutPage"),
                page("ClassesPage"), page("ContactPage"), page("GalleryPage"), page("LoginPage"),
                page("MembershipPage"), page("SignupPage"), page("TrainerDetailPage"), page("TrainersPage"),
                page("AdminDashboardPage"), page("AdminBookingsPage"), page("AdminClassesPage"),
                page("AdminInquiriesPage"), page("AdminMembershipPlansPage"), page("AdminTrainersPage"),
                page("NotFoundPage"), page("CartPage"),
                new FileEntry("frontend/src/pages/admin/AdminMediaPage.tsx", FileType.FRONTEND, "AdminMediaPage")));

        // gate: the two login-required-not-admin pages that the OLD admin:boolean left ungated
        assertThat(entry(m, "AccountPage").gate()).isEqualTo(RouteManifest.RouteGate.AUTH);
        assertThat(entry(m, "CheckoutPage").gate()).isEqualTo(RouteManifest.RouteGate.AUTH);
        // gate: cart is public (not auth), admin pages are admin
        assertThat(entry(m, "CartPage").gate()).isEqualTo(RouteManifest.RouteGate.PUBLIC);
        assertThat(m.entries().stream().filter(e -> e.gate() == RouteManifest.RouteGate.ADMIN).count()).isEqualTo(7);
        // nav flips vs the real routes.ts (which had checkout/cart nav:true)
        assertThat(entry(m, "CheckoutPage").nav()).isFalse();
        assertThat(entry(m, "CartPage").nav()).isFalse();
        assertThat(entry(m, "AccountPage").nav()).isTrue();   // account stays in nav — only checkout/cart flip

        // routes.ts: new gate field replaces the old boolean, with the real AdminMedia nested import path
        String ts = RouteManifestGenerator.emitRoutesTs(m);
        assertThat(ts).contains("export type RouteGate = 'public' | 'auth' | 'admin';")
                .contains("page: 'CheckoutPage', importPath: './pages/CheckoutPage', label: 'Checkout', gate: 'auth', nav: false")
                .contains("page: 'CartPage', importPath: './pages/CartPage', label: 'Cart', gate: 'public', nav: false")
                .contains("importPath: './pages/admin/AdminMediaPage', label: 'Media', gate: 'admin', nav: true")
                .doesNotContain("admin: ");   // old boolean field gone entirely

        // AppRoutes: three gate groups, admin guarded once, auth nested inside SiteLayout, catch-all last
        String routes = RouteManifestGenerator.emitAppRoutes(m,
                new RouteManifestGenerator.Flags(true, true, true, List.of()));
        assertThat(routes)
                .contains("import AdminMediaPage from './pages/admin/AdminMediaPage';")
                .contains("<Route element={<ProtectedRoute allowedRoles={['ADMIN']}><AdminLayout /></ProtectedRoute>}>")
                .contains("<Route path=\"/admin\" element={<AdminDashboardPage />} />")
                .contains("<Route path=\"/admin/media\" element={<AdminMediaPage />} />")
                .contains("<Route element={<ProtectedRoute><Outlet /></ProtectedRoute>}>")
                .contains("<Route path=\"/checkout\" element={<CheckoutPage />} />")
                .doesNotContain("<ProtectedRoute><AdminDashboardPage /></ProtectedRoute>");  // no per-child admin guard
        // structural ordering: SiteLayout → auth guard → checkout → catch-all last
        int site     = routes.indexOf("<SiteLayout config={siteConfig}>");
        int authGate = routes.indexOf("<Route element={<ProtectedRoute><Outlet /></ProtectedRoute>}>");
        int checkout = routes.indexOf("path=\"/checkout\"");
        int catchAll = routes.indexOf("path=\"*\"");
        int adminGrp = routes.indexOf("<AdminLayout /></ProtectedRoute>}>");
        assertThat(adminGrp).isLessThan(site);
        assertThat(site).isLessThan(authGate);
        assertThat(authGate).isLessThan(checkout);
        assertThat(checkout).isLessThan(catchAll);
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
    void gateAndNavForAuthAndCartPages() {
        RouteManifest m = manifest("CheckoutPage", "AccountPage", "CartPage", "MenuPage", "AdminOrdersPage");

        // gate derivation: checkout/account require login; cart/menu public; admin is admin
        assertThat(entry(m, "CheckoutPage").gate()).isEqualTo(RouteManifest.RouteGate.AUTH);
        assertThat(entry(m, "AccountPage").gate()).isEqualTo(RouteManifest.RouteGate.AUTH);
        assertThat(entry(m, "CartPage").gate()).isEqualTo(RouteManifest.RouteGate.PUBLIC);
        assertThat(entry(m, "MenuPage").gate()).isEqualTo(RouteManifest.RouteGate.PUBLIC);
        assertThat(entry(m, "AdminOrdersPage").gate()).isEqualTo(RouteManifest.RouteGate.ADMIN);

        // nav flips: checkout and cart are reached via dedicated UI, not the primary nav
        assertThat(entry(m, "CheckoutPage").nav()).isFalse();
        assertThat(entry(m, "CartPage").nav()).isFalse();
        assertThat(entry(m, "MenuPage").nav()).isTrue();
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
                .contains("export type RouteGate = 'public' | 'auth' | 'admin';")
                .contains("gate: RouteGate;")
                .contains("export const routeTable: RouteEntry[]")
                .contains("importPath: './pages/AdminOrdersPage'")
                .contains("gate: 'admin'")     // AdminOrdersPage
                .contains("gate: 'public'")    // HomePage
                .doesNotContain("admin: ");     // old boolean field is gone
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
                .contains("import AdminLayout from '@/components/AdminLayout'")
                .contains("<Route path=\"/\" element={<HomePage />} />")
                // admin pages are now children of a single guarded AdminLayout layout-route
                .contains("<Route element={<ProtectedRoute allowedRoles={['ADMIN']}><AdminLayout /></ProtectedRoute>}>")
                .contains("<Route path=\"/admin/orders\" element={<AdminOrdersPage />} />")
                .doesNotContain("<AdminLayout></AdminLayout>");
    }

    @Test
    void adminGroupClosesBeforeSiteGroupOpens() {
        // structural: the admin layout-route must close before the SiteLayout group opens
        String routes = RouteManifestGenerator.emitAppRoutes(manifest("HomePage", "AdminOrdersPage"),
                new RouteManifestGenerator.Flags(true, true, true, java.util.List.of()));
        int adminGroup = routes.indexOf("<AdminLayout /></ProtectedRoute>}>");
        int siteGroup  = routes.indexOf("<SiteLayout config={siteConfig}>");
        assertThat(adminGroup).isGreaterThan(-1);
        assertThat(adminGroup).isLessThan(siteGroup);
    }

    @Test
    void authPagesNestUnderGuardInsideSiteLayoutAndCatchAllLast() {
        RouteManifest m = manifest("HomePage", "CheckoutPage", "AccountPage", "NotFoundPage");
        String routes = RouteManifestGenerator.emitAppRoutes(m,
                new RouteManifestGenerator.Flags(true, true, true, java.util.List.of()));

        int site     = routes.indexOf("<SiteLayout config={siteConfig}>");
        int authGate = routes.indexOf("<Route element={<ProtectedRoute><Outlet /></ProtectedRoute>}>");
        int checkout = routes.indexOf("<Route path=\"/checkout\" element={<CheckoutPage />} />");
        int catchAll = routes.indexOf("<Route path=\"*\" element={<NotFoundPage />} />");

        // SiteLayout outside, auth guard inside, checkout within the guard, catch-all emitted last
        assertThat(site).isLessThan(authGate);
        assertThat(authGate).isLessThan(checkout);
        assertThat(checkout).isLessThan(catchAll);
        // auth pages are NOT in the admin group
        assertThat(routes).doesNotContain("allowedRoles={['ADMIN']}");
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
