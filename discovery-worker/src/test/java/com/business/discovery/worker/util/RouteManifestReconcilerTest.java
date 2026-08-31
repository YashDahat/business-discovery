package com.business.discovery.worker.util;

import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FileSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteManifestReconcilerTest {

    @TempDir
    Path workspace;

    private void writeSpec(String... pagePaths) throws Exception {
        ArchitectureSpec spec = new ArchitectureSpec();
        spec.setFiles(java.util.Arrays.stream(pagePaths)
                .map(p -> FileSpec.builder()
                        .fileName(p.substring(p.lastIndexOf('/') + 1))
                        .filePath(p)
                        .fileType("FRONTEND")
                        .layer("PAGE")
                        .build())
                .collect(java.util.stream.Collectors.toList()));
        ArchitectureJsonUtil.write(workspace, spec);
    }

    private void writePage(String name) throws Exception {
        Path page = workspace.resolve("frontend/src/pages/" + name + ".tsx");
        Files.createDirectories(page.getParent());
        Files.writeString(page, "export default function " + name + "() { return <div/> }\n");
    }

    @Test
    void failsNamingMissingPages() throws Exception {
        writeSpec("frontend/src/pages/HomePage.tsx", "frontend/src/pages/AdminOrdersPage.tsx");
        writePage("HomePage"); // AdminOrdersPage never generated

        assertThatThrownBy(() -> RouteManifestReconciler.reconcile(workspace))
                .isInstanceOf(WorkerException.class)
                .hasMessageContaining("AdminOrdersPage");
    }

    @Test
    void appendsDiskOnlyPagesAndReemitsAppRoutes() throws Exception {
        writeSpec("frontend/src/pages/HomePage.tsx");
        writePage("HomePage");
        writePage("ContactPage"); // agent-created, not in the plan

        boolean reconciled = RouteManifestReconciler.reconcile(workspace);

        assertThat(reconciled).isTrue();
        String routes = Files.readString(workspace.resolve("frontend/src/routes.ts"));
        assertThat(routes).contains("CONTACT: '/contact'");
        String appRoutes = Files.readString(workspace.resolve("frontend/src/AppRoutes.tsx"));
        assertThat(appRoutes).contains("<Route path=\"/contact\" element={<ContactPage />} />")
                .contains("<Route path=\"/\" element={<HomePage />} />");
        // the shell is written and delegates to <AppRoutes/> — never holds the table itself
        assertThat(Files.readString(workspace.resolve("frontend/src/App.tsx")))
                .contains("<AppRoutes />").doesNotContain("<Route ");
    }

    @Test
    void routesFoundationAuthPagesFromDisk() throws Exception {
        // The foundation clone ships LoginPage/SignupPage; they are NOT in the business plan.
        // The disk→manifest pass must route them so every generated app gets /login and /signup.
        writeSpec("frontend/src/pages/HomePage.tsx");
        writePage("HomePage");
        writePage("LoginPage");   // foundation-cloned, unplanned
        writePage("SignupPage");  // foundation-cloned, unplanned

        RouteManifestReconciler.reconcile(workspace);

        String routes = Files.readString(workspace.resolve("frontend/src/routes.ts"));
        assertThat(routes).contains("LOGIN: '/login'").contains("SIGNUP: '/signup'");
        String appRoutes = Files.readString(workspace.resolve("frontend/src/AppRoutes.tsx"));
        assertThat(appRoutes)
                .contains("import LoginPage from './pages/LoginPage';")
                .contains("import SignupPage from './pages/SignupPage';")
                .contains("<Route path=\"/login\" element={<LoginPage />} />")
                .contains("<Route path=\"/signup\" element={<SignupPage />} />");
    }

    @Test
    void isIdempotent() throws Exception {
        writeSpec("frontend/src/pages/HomePage.tsx", "frontend/src/pages/MenuPage.tsx");
        writePage("HomePage");
        writePage("MenuPage");

        assertThat(RouteManifestReconciler.reconcile(workspace)).isTrue();
        String firstRoutes = Files.readString(workspace.resolve("frontend/src/routes.ts"));
        String firstAppRoutes = Files.readString(workspace.resolve("frontend/src/AppRoutes.tsx"));
        String firstApp = Files.readString(workspace.resolve("frontend/src/App.tsx"));

        assertThat(RouteManifestReconciler.reconcile(workspace)).isTrue();
        assertThat(Files.readString(workspace.resolve("frontend/src/routes.ts"))).isEqualTo(firstRoutes);
        assertThat(Files.readString(workspace.resolve("frontend/src/AppRoutes.tsx"))).isEqualTo(firstAppRoutes);
        // the frozen shell is byte-identical across attempts too
        assertThat(Files.readString(workspace.resolve("frontend/src/App.tsx"))).isEqualTo(firstApp);
    }

    @Test
    void healsAgentDamagedAppRoutes() throws Exception {
        writeSpec("frontend/src/pages/HomePage.tsx", "frontend/src/pages/AdminOrdersPage.tsx");
        writePage("HomePage");
        writePage("AdminOrdersPage");
        // previous attempt's agent left a partial route table registering only one route
        Path appRoutes = workspace.resolve("frontend/src/AppRoutes.tsx");
        Files.writeString(appRoutes, "import { Route } from 'react-router-dom'\n<Route path=\"/\" />\n");

        RouteManifestReconciler.reconcile(workspace);

        assertThat(Files.readString(appRoutes))
                .contains("<Route path=\"/admin/orders\"")
                .contains("<Route path=\"/\" element={<HomePage />} />");
    }

    @Test
    void frozenShellSurvivesPartialPlan() throws Exception {
        // A prior full run left a complete route table; an update run then arrives with NO PAGE
        // entries in the plan. The disk fallback must rebuild every route rather than dropping them.
        writePage("HomePage");
        writePage("CartPage");
        writePage("AdminOrdersPage");
        // no writeSpec — the plan carries no pages

        assertThat(RouteManifestReconciler.reconcile(workspace)).isTrue();

        String appRoutes = Files.readString(workspace.resolve("frontend/src/AppRoutes.tsx"));
        assertThat(appRoutes)
                .contains("<Route path=\"/\" element={<HomePage />} />")
                .contains("<Route path=\"/cart\" element={<CartPage />} />")
                .contains("<Route path=\"/admin/orders\"");
    }

    @Test
    void skipsPagesWithoutDefaultExport() throws Exception {
        writeSpec("frontend/src/pages/HomePage.tsx");
        writePage("HomePage");
        Path helper = workspace.resolve("frontend/src/pages/helpers.tsx");
        Files.writeString(helper, "export const x = 1;\n");

        RouteManifestReconciler.reconcile(workspace);

        assertThat(Files.readString(workspace.resolve("frontend/src/routes.ts")))
                .doesNotContain("helpers");
    }

    @Test
    void protectsAdminRoutesWhenProtectedRouteExists() throws Exception {
        writeSpec("frontend/src/pages/HomePage.tsx", "frontend/src/pages/AdminOrdersPage.tsx");
        writePage("HomePage");
        writePage("AdminOrdersPage");
        Path prot = workspace.resolve("frontend/src/components/ProtectedRoute.tsx");
        Files.createDirectories(prot.getParent());
        Files.writeString(prot, "export default function ProtectedRoute() { return null }\n");

        RouteManifestReconciler.reconcile(workspace);

        assertThat(Files.readString(workspace.resolve("frontend/src/AppRoutes.tsx")))
                .contains("<Route element={<ProtectedRoute allowedRoles={['ADMIN']}><AdminLayout /></ProtectedRoute>}>")
                .contains("<Route path=\"/admin/orders\" element={<AdminOrdersPage />} />");
    }

    @Test
    void reconciledRouteCountMatchesPageFiles() throws Exception {
        List<String> pages = List.of("HomePage", "MenuPage", "AdminDashboardPage",
                "AdminOrdersPage", "AdminMenuPage");
        writeSpec(pages.stream()
                .map(p -> "frontend/src/pages/" + p + ".tsx")
                .toArray(String[]::new));
        for (String p : pages) writePage(p);

        RouteManifestReconciler.reconcile(workspace);

        String appRoutes = Files.readString(workspace.resolve("frontend/src/AppRoutes.tsx"));
        long routeCount = appRoutes.lines().filter(l -> l.contains("<Route path=")).count();
        assertThat(routeCount).isEqualTo(pages.size());
    }
}
