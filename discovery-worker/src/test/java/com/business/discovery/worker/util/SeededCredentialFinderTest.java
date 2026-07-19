package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixtures are circuit-house attempt 2's two racing seeders — DataSeeder plants
 * "adminpass", AdminInitializer plants "adminpassword", both for the same email, and
 * whichever runs first wins. Neither honours the ADMIN_EMAIL/ADMIN_PASSWORD properties
 * the generator itself wrote. The smoke flows therefore cannot assume credentials; they
 * must read what the code actually plants and try each candidate.
 */
class SeededCredentialFinderTest {

    @TempDir
    Path root;

    private Path srcDir() throws Exception {
        Path p = root.resolve("backend/src/main/java");
        Files.createDirectories(p);
        return p;
    }

    private void write(String rel, String content) throws Exception {
        Path p = srcDir().resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void recoversBothRacingSeedersCredentials() throws Exception {
        write("com/circuithouse/config/DataSeeder.java", """
                package com.circuithouse.config;

                public class DataSeeder {
                    private void seedUsers() {
                        adminUser.setEmail("admin@circuithouse.com");
                        adminUser.setPassword(passwordEncoder.encode("adminpass"));
                    }
                }
                """);
        write("com/circuithouse/config/AdminInitializer.java", """
                package com.circuithouse.config;

                public class AdminInitializer {
                    public void run(String... args) {
                        User adminUser = new User(
                                "admin@circuithouse.com",
                                passwordEncoder.encode("adminpassword"),
                                Set.of(Role.ROLE_ADMIN));
                    }
                }
                """);

        List<SeededCredentialFinder.Credential> creds =
                SeededCredentialFinder.find(srcDir(), root.resolve("nope.properties"));

        assertThat(creds).extracting(SeededCredentialFinder.Credential::password)
                .contains("adminpass", "adminpassword");
        assertThat(creds).allSatisfy(c ->
                assertThat(c.identifier()).isEqualTo("admin@circuithouse.com"));
        // both seeders are surfaced so the gate can try each — it cannot know which won the race
        assertThat(creds).extracting(SeededCredentialFinder.Credential::source)
                .contains("DataSeeder.java", "AdminInitializer.java");
    }

    @Test
    void recoversRawConstructorSeeder() throws Exception {
        // circuit-house 2026-07-17: username identifier, raw password literal, encoding happens
        // inside createUser — no .encode("...") at the call site, so the old finder saw nothing.
        write("com/circuithouse/config/AdminInitializer.java", """
                package com.circuithouse.config;

                public class AdminInitializer {
                    public void run(String... args) {
                        if (!userService.existsByUsername("admin")) {
                            User adminUser = new User("admin", "adminpass", Set.of(Role.ADMIN));
                            userService.createUser(adminUser);
                        }
                    }
                }
                """);

        List<SeededCredentialFinder.Credential> creds =
                SeededCredentialFinder.find(srcDir(), root.resolve("nope.properties"));

        assertThat(creds).extracting(SeededCredentialFinder.Credential::identifier).contains("admin");
        assertThat(creds).extracting(SeededCredentialFinder.Credential::password).contains("adminpass");
    }

    @Test
    void fallsBackToPropertyDefaults() throws Exception {
        srcDir();  // no seeders at all
        Path props = root.resolve("application.properties");
        Files.writeString(props, """
                admin.email=${ADMIN_EMAIL:owner@example.com}
                admin.password=${ADMIN_PASSWORD:changeme123}
                """);

        List<SeededCredentialFinder.Credential> creds = SeededCredentialFinder.find(srcDir(), props);

        assertThat(creds).hasSize(1);
        assertThat(creds.get(0).identifier()).isEqualTo("owner@example.com");
        assertThat(creds.get(0).password()).isEqualTo("changeme123");
    }

    @Test
    void noSeederAndNoPropertiesYieldsNothing() throws Exception {
        assertThat(SeededCredentialFinder.find(srcDir(), root.resolve("absent.properties"))).isEmpty();
    }
}
