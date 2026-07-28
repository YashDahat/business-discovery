package com.business.discovery.worker.scaffold;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class AuthScaffoldModuleTest {

    private final AuthScaffoldModule module = new AuthScaffoldModule();

    @Test
    void write_emitsAllTwelveAuthFiles_underBasePackage(@TempDir Path tempDir) throws IOException {
        Path javaRoot = tempDir.resolve("backend/src/main/java");
        module.write(javaRoot, "com.circuithouse");

        Path pkg = javaRoot.resolve("com/circuithouse");
        List<String> expected = List.of(
                "model/Role.java", "model/User.java", "repository/UserRepository.java",
                "dto/AuthRequest.java", "dto/AuthResponse.java", "util/JwtUtil.java",
                "security/JwtAuthFilter.java", "service/UserService.java",
                "config/PasswordEncoderConfig.java", "config/SecurityConfig.java",
                "controller/AuthController.java", "config/AdminInitializer.java");
        for (String rel : expected) {
            assertThat(pkg.resolve(rel)).as(rel).exists();
        }
    }

    @Test
    void write_leavesNoUnsubstitutedToken_andHoldsCorrectnessInvariants(@TempDir Path tempDir) throws IOException {
        Path javaRoot = tempDir.resolve("backend/src/main/java");
        module.write(javaRoot, "com.circuithouse");
        Path pkg = javaRoot.resolve("com/circuithouse");

        try (var walk = Files.walk(pkg)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String src = Files.readString(p);
                assertThat(src).as("no __BP__ token left in " + p).doesNotContain("__BP__");
                assertThat(src).as("no %s placeholder left in " + p).doesNotContain("%s");
                assertThat(src).as("package substituted in " + p).contains("package com.circuithouse");
            }
        }

        // Role bug (ID/NAME) is structurally impossible now.
        assertThat(Files.readString(pkg.resolve("model/Role.java")))
                .contains("ADMIN").contains("USER").doesNotContain("ID").doesNotContain("NAME");
        // Bare authority, never ROLE_-prefixed — consistent with User.getAuthorities().
        assertThat(Files.readString(pkg.resolve("config/SecurityConfig.java")))
                .contains("hasAuthority(\"ADMIN\")").doesNotContain("ROLE_");
        // PasswordEncoder is its own dependency-free @Bean (cycle-safe).
        assertThat(Files.readString(pkg.resolve("config/PasswordEncoderConfig.java")))
                .contains("@Bean").contains("PasswordEncoder passwordEncoder()");
        // JWT util pinned to the JJWT 0.11.5 API.
        assertThat(Files.readString(pkg.resolve("util/JwtUtil.java")))
                .contains("parserBuilder");
        // Stale/tampered JWT is swallowed → anonymous, never a 500.
        assertThat(Files.readString(pkg.resolve("security/JwtAuthFilter.java")))
                .contains("catch (JwtException");
        // AdminInitializer is the sole user seeder, props-driven.
        assertThat(Files.readString(pkg.resolve("config/AdminInitializer.java")))
                .contains("admin.email").contains("Role.ADMIN");
    }

    @Test
    void ownedFilePatterns_matchCanonicalAndRenames_butNotBusinessOrFrontend() {
        List<Pattern> owned = module.ownedFilePatterns();

        for (String name : List.of("Role.java", "User.java", "UserRepository.java",
                "UserService.java", "UserDetailsServiceImpl.java", "AuthController.java",
                "AuthRequest.java", "AuthResponse.java", "SecurityConfig.java",
                "SecurityConfiguration.java", "JwtUtil.java", "JwtService.java",
                "JwtAuthFilter.java", "PasswordEncoderConfig.java", "AdminInitializer.java")) {
            assertThat(matchesAny(owned, name)).as("should own " + name).isTrue();
        }

        for (String name : List.of("MenuItem.java", "MenuService.java", "Reservation.java",
                "OrderController.java", "authService.ts", "auth.ts", "UserCard.tsx")) {
            assertThat(matchesAny(owned, name)).as("should NOT own " + name).isFalse();
        }
    }

    private static boolean matchesAny(List<Pattern> owned, String name) {
        return owned.stream().anyMatch(p -> p.matcher(name).find());
    }
}
