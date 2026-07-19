package com.business.discovery.worker.service;

import com.business.discovery.worker.service.ComposeLaunchService.LaunchSpec;
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
}
