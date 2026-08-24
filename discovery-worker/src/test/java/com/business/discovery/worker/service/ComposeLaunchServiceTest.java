package com.business.discovery.worker.service;

import com.business.discovery.worker.service.ComposeLaunchService.LaunchSpec;
import com.business.discovery.worker.service.llm.ApiEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ComposeLaunchServiceTest {

    @TempDir
    Path workspace;

    private ComposeLaunchService service;

    @BeforeEach
    void setUp() {
        service = new ComposeLaunchService();
        ReflectionTestUtils.setField(service, "sharedNetwork", "shared-network");
        ReflectionTestUtils.setField(service, "inContainer", true);
        ReflectionTestUtils.setField(service, "bootTimeoutSeconds", 1);
    }

    // ── .env preparation ───────────────────────────────────────────────────

    @Test
    void derivesEnvFromExampleAndFillsBlanks() throws Exception {
        Files.writeString(workspace.resolve(".env.example"), """
                DB_URL=jdbc:postgresql://db:5432/demo
                RAZORPAY_KEY_ID=
                JWT_SECRET=short
                ADMIN_EMAIL=owner@x.com
                """);

        service.prepareEnvFile(workspace);

        String env = Files.readString(workspace.resolve(".env"));
        assertThat(env).contains("DB_URL=jdbc:postgresql://db:5432/demo");
        assertThat(env).contains("RAZORPAY_KEY_ID=demo-placeholder");
        assertThat(env).contains("ADMIN_EMAIL=owner@x.com");

        String jwtLine = env.lines().filter(l -> l.startsWith("JWT_SECRET=")).findFirst().orElseThrow();
        // short secrets are replaced with a real random key long enough for JJWT HS256
        assertThat(jwtLine.substring("JWT_SECRET=".length()).length()).isGreaterThanOrEqualTo(44);
    }

    @Test
    void keepsExistingEnvValuesAndOnlyFillsBlanks() throws Exception {
        Files.writeString(workspace.resolve(".env"), """
                DB_PASSWORD=real-password
                RAZORPAY_KEY_ID=
                """);

        service.prepareEnvFile(workspace);

        String env = Files.readString(workspace.resolve(".env"));
        assertThat(env).contains("DB_PASSWORD=real-password");
        assertThat(env).contains("RAZORPAY_KEY_ID=demo-placeholder");
    }

    @Test
    void preservesCommentsAndBlankLines() throws Exception {
        Files.writeString(workspace.resolve(".env.example"), """
                # Database
                DB_URL=x

                # Auto-added by EnvVarScanner
                RAZORPAY_KEY_ID=
                """);

        service.prepareEnvFile(workspace);

        String env = Files.readString(workspace.resolve(".env"));
        assertThat(env).contains("# Database");
        assertThat(env).contains("# Auto-added by EnvVarScanner");
    }

    // ── Override generation ────────────────────────────────────────────────

    @Test
    void inContainerOverrideJoinsSharedNetworkWithAliasAndStripsPorts() throws Exception {
        Path override = service.writeOverride(workspace,
                new LaunchSpec("smoke-abc12345", "ghcr.io/owner/repo:attempt-1", null));

        String yml = Files.readString(override);
        assertThat(override.toString()).contains(".smoke");
        assertThat(yml).contains("image: ghcr.io/owner/repo:attempt-1");
        assertThat(yml).contains("ports: !reset []");
        assertThat(yml).contains("- smoke-abc12345");           // alias = project name
        assertThat(yml).contains("name: shared-network");
        assertThat(yml).contains("external: true");
        assertThat(yml).contains("db:");                        // db keeps default network explicitly
    }

    @Test
    void hostModeOverridePublishesPortAndSkipsExternalNetwork() throws Exception {
        ReflectionTestUtils.setField(service, "inContainer", false);

        Path override = service.writeOverride(workspace,
                new LaunchSpec("smoke-local", "ghcr.io/owner/repo:attempt-1", 18080));

        String yml = Files.readString(override);
        assertThat(yml).contains("ports: !override [\"18080:8080\"]");
        assertThat(yml).doesNotContain("external: true");       // dev host has no shared-network
        assertThat(yml).doesNotContain("aliases");
    }

    @Test
    void omitsImageLineWhenNoImageRef() throws Exception {
        Path override = service.writeOverride(workspace, new LaunchSpec("smoke-x", null, null));
        assertThat(Files.readString(override)).doesNotContain("image:");
    }

    // ── Login probe field derivation (fix E) ─────────────────────────────────

    private void writeAuthDto(String rel, String body) throws Exception {
        Path p = workspace.resolve("backend/src/main/java").resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
    }

    @Test
    void derivesLoginFieldFromAuthRequestDto() throws Exception {
        // circuit-house's DTO took username, not email
        writeAuthDto("com/circuithouse/dto/AuthRequest.java", """
                package com.circuithouse.dto;
                public class AuthRequest {
                    private String username;
                    private String password;
                }
                """);

        assertThat(service.loginIdentifierFields(workspace)).containsExactly("username");
    }

    @Test
    void loginFieldFallsBackToBothWhenNoDtoFound() {
        assertThat(service.loginIdentifierFields(workspace)).containsExactlyInAnyOrder("username", "email");
    }

    @Test
    void loginBodySendsIdentifierUnderEveryField() {
        String body = ComposeLaunchService.loginBody(java.util.List.of("username", "email"), "admin", "adminpass");
        assertThat(body).isEqualTo("{\"username\":\"admin\",\"email\":\"admin\",\"password\":\"adminpass\"}");
    }

    // ── Negative-authz probe (protected endpoints must reject anonymous callers) ─

    @Test
    void isProperlyRejectedOnlyFor401And403() {
        assertThat(ComposeLaunchService.isProperlyRejected(401)).isTrue();
        assertThat(ComposeLaunchService.isProperlyRejected(403)).isTrue();
        assertThat(ComposeLaunchService.isProperlyRejected(200)).isFalse();
        assertThat(ComposeLaunchService.isProperlyRejected(404)).isFalse();  // reached dispatcher = unguarded
        assertThat(ComposeLaunchService.isProperlyRejected(500)).isFalse();
    }

    @Test
    void adminPathIsProtectedByConventionEvenWithoutManifestTier() {
        // the /api/admin/... drift (off /api/v1/admin) is caught by the path convention alone
        assertThat(ComposeLaunchService.shouldBeProtected("GET", "/api/admin/inquiries", java.util.Set.of())).isTrue();
        assertThat(ComposeLaunchService.shouldBeProtected("POST", "/api/admin/gallery", java.util.Set.of())).isTrue();
    }

    @Test
    void nonAdminEndpointProtectedOnlyWhenManifestTiersIt() {
        var keys = java.util.Set.of("GET /api/v1/orders/my-orders");
        assertThat(ComposeLaunchService.shouldBeProtected("GET", "/api/v1/orders/my-orders", keys)).isTrue();
        assertThat(ComposeLaunchService.shouldBeProtected("GET", "/api/v1/menu/items", keys)).isFalse(); // public
    }

    @Test
    void protectedKeysFromKeepsAuthAndAdminTiersOnly() {
        var keys = ComposeLaunchService.protectedKeysFrom(java.util.List.of(
                ep("GET", "/api/v1/menu/items", "public"),
                ep("GET", "/api/v1/orders/my-orders", "authenticated"),
                ep("POST", "/api/v1/admin/gallery", "admin")));
        assertThat(keys).containsExactlyInAnyOrder(
                "GET /api/v1/orders/my-orders", "POST /api/v1/admin/gallery");
    }

    @Test
    void substituteParamsReplacesPathVariables() {
        assertThat(ComposeLaunchService.substituteParams("/api/v1/admin/orders/{orderId}/status"))
                .isEqualTo("/api/v1/admin/orders/1/status");
        assertThat(ComposeLaunchService.substituteParams("/api/gallery")).isEqualTo("/api/gallery");
    }

    private static ApiEndpoint ep(String method, String path, String access) {
        ApiEndpoint e = new ApiEndpoint();
        e.setMethod(method);
        e.setPath(path);
        e.setAccess(access);
        return e;
    }
}
