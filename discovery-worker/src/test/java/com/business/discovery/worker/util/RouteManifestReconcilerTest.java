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
    void appendsDiskOnlyPagesAndReemitsAppTsx() throws Exception {
        writeSpec("frontend/src/pages/HomePage.tsx");
        writePage("HomePage");
        writePage("ContactPage"); // agent-created, not in the plan

        boolean reconciled = RouteManifestReconciler.reconcile(workspace);

        assertThat(reconciled).isTrue();
        String routes = Files.readString(workspace.resolve("frontend/src/routes.ts"));
        assertThat(routes).contains("CONTACT: '/contact'");
        String app = Files.readString(workspace.resolve("frontend/src/App.tsx"));
        assertThat(app).contains("<Route path=\"/contact\" element={<ContactPage />} />")
                .contains("<Route path=\"/\" element={<HomePage />} />");
    }

    @Test
    void isIdempotent() throws Exception {
        writeSpec("frontend/src/pages/HomePage.tsx", "frontend/src/pages/MenuPage.tsx");
        writePage("HomePage");
        writePage("MenuPage");

        assertThat(RouteManifestReconciler.reconcile(workspace)).isTrue();
        String firstRoutes = Files.readString(workspace.resolve("frontend/src/routes.ts"));
        String firstApp = Files.readString(workspace.resolve("frontend/src/App.tsx"));

        assertThat(RouteManifestReconciler.reconcile(workspace)).isTrue();
        assertThat(Files.readString(workspace.resolve("frontend/src/routes.ts"))).isEqualTo(firstRoutes);
        assertThat(Files.readString(workspace.resolve("frontend/src/App.tsx"))).isEqualTo(firstApp);
    }

    @Test
    void healsAgentDamagedAppTsx() throws Exception {
        writeSpec("frontend/src/pages/HomePage.tsx", "frontend/src/pages/AdminOrdersPage.tsx");
        writePage("HomePage");
        writePage("AdminOrdersPage");
        // previous attempt's agent left a partial router registering only one route
        Path app = workspace.resolve("frontend/src/App.tsx");
        Files.writeString(app, "import { Route } from 'react-router-dom'\n<Route path=\"/\" />\n");

        RouteManifestReconciler.reconcile(workspace);

        assertThat(Files.readString(app))
                .contains("<Route path=\"/admin/orders\"")
                .contains("<Route path=\"/\" element={<HomePage />} />");
    }

    @Test
    void noPlanMeansNothingToReconcile() throws Exception {
        writePage("HomePage");
        assertThat(RouteManifestReconciler.reconcile(workspace)).isFalse();
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

        assertThat(Files.readString(workspace.resolve("frontend/src/App.tsx")))
                .contains("<ProtectedRoute><AdminOrdersPage /></ProtectedRoute>");
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

        String app = Files.readString(workspace.resolve("frontend/src/App.tsx"));
        long routeCount = app.lines().filter(l -> l.contains("<Route path=")).count();
        assertThat(routeCount).isEqualTo(pages.size());
    }
}
