package com.business.discovery.worker.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AppRouteSynthesizerTest {

    @TempDir
    Path frontend;

    private Path src;

    @BeforeEach
    void setUp() throws Exception {
        src = frontend.resolve("src");
        Files.createDirectories(src.resolve("pages/admin"));
        Files.createDirectories(src.resolve("context"));
        Files.createDirectories(src.resolve("components"));
        Files.createDirectories(src.resolve("api"));
        Files.writeString(frontend.resolve("package.json"),
                "{\"dependencies\":{\"@tanstack/react-query\":\"^5\"}}");
        Files.writeString(src.resolve("api/client.ts"), "export default {}");
        Files.writeString(src.resolve("context/AuthContext.tsx"),
                "export const AuthProvider = ({children}) => children");
        Files.writeString(src.resolve("components/ProtectedRoute.tsx"),
                "export default function ProtectedRoute({children}) { return children }");
        page("pages/HomePage.tsx", "HomePage", "<a href=\"/trainers\">T</a><a href=\"/memberships\">M</a>");
        page("pages/TrainersPage.tsx", "TrainersPage", "");
        page("pages/MembershipPage.tsx", "MembershipPage", "");
        page("pages/LoginPage.tsx", "LoginPage", "");
        page("pages/admin/AdminDashboardPage.tsx", "AdminDashboardPage", "");
        page("pages/admin/AdminTrainersPage.tsx", "AdminTrainersPage", "");
    }

    private void page(String rel, String comp, String body) throws Exception {
        Files.writeString(src.resolve(rel),
                "export default function " + comp + "() { return <div>" + body + "</div> }");
    }

    @Test
    void synthesizesRoutesForBlankApp() throws Exception {
        Files.writeString(src.resolve("App.tsx"), "export default function App() { return <div /> }");

        boolean changed = AppRouteSynthesizer.fixIfMissingRoutes(src);

        assertThat(changed).isTrue();
        String app = Files.readString(src.resolve("App.tsx"));
        // BrowserRouter outermost; providers inside
        assertThat(app).contains("<BrowserRouter>");
        assertThat(app.indexOf("<BrowserRouter>")).isLessThan(app.indexOf("<AuthProvider>"));
        // home + linked targets routed
        assertThat(app).contains("path=\"/\"").contains("<HomePage");
        assertThat(app).contains("path=\"/trainers\"").contains("<TrainersPage");
        assertThat(app).contains("path=\"/memberships\"").contains("<MembershipPage");
        // admin pages wrapped in ProtectedRoute
        assertThat(app).contains("<ProtectedRoute><AdminTrainersPage /></ProtectedRoute>");
        // all pages imported
        assertThat(app).contains("import AdminDashboardPage from './pages/admin/AdminDashboardPage'");
    }

    @Test
    void leavesAlreadyRoutedAppUntouched() throws Exception {
        String routed = "import { Routes, Route } from 'react-router-dom'\n<Route path=\"/\" />";
        Files.writeString(src.resolve("App.tsx"), routed);

        boolean changed = AppRouteSynthesizer.fixIfMissingRoutes(src);

        assertThat(changed).isFalse();
        assertThat(Files.readString(src.resolve("App.tsx"))).isEqualTo(routed);
    }
}
