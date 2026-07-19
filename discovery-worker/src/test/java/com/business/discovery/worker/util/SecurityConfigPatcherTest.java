package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.ApiEndpoint;
import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FileSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigPatcherTest {

    /** The buggy shape circuit-house shipped: a blanket /api/** lockdown, no public GET line. */
    private static final String BLANKET_LOCKDOWN = """
            package com.circuithouse.config;

            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            import org.springframework.security.config.annotation.web.builders.HttpSecurity;
            import org.springframework.security.web.SecurityFilterChain;

            @Configuration
            public class SecurityConfig {
                @Bean
                public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
                    http
                        .csrf(csrf -> csrf.disable())
                        .authorizeHttpRequests(authorize -> authorize
                            .requestMatchers("/api/v1/auth/**", "/index.html", "/assets/**").permitAll()
                            .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                            .requestMatchers("/api/**").authenticated()
                            .anyRequest().permitAll()
                        );
                    return http.build();
                }
            }
            """;

    private static final List<String> PUBLIC = List.of("/api/v1/menus/**", "/api/v1/events/**");

    @Test
    void insertsPublicGetPermitBeforeTheApiCatchAll() {
        String out = SecurityConfigPatcher.applyPatches(BLANKET_LOCKDOWN, PUBLIC);

        assertThat(out).contains(
                ".requestMatchers(HttpMethod.GET, \"/api/v1/menus/**\", \"/api/v1/events/**\").permitAll()");
        // must come BEFORE the authenticated catch-all — Spring authorizes top-down
        assertThat(out.indexOf("HttpMethod.GET"))
                .isLessThan(out.indexOf(".requestMatchers(\"/api/**\").authenticated()"));
        // and AFTER the admin rule stays intact
        assertThat(out).contains(".requestMatchers(\"/api/v1/admin/**\").hasRole(\"ADMIN\")");
    }

    @Test
    void addsHttpMethodImport() {
        String out = SecurityConfigPatcher.applyPatches(BLANKET_LOCKDOWN, PUBLIC);
        assertThat(out).contains("import org.springframework.http.HttpMethod;");
    }

    @Test
    void isIdempotent() {
        String once = SecurityConfigPatcher.applyPatches(BLANKET_LOCKDOWN, PUBLIC);
        String twice = SecurityConfigPatcher.applyPatches(once, PUBLIC);
        assertThat(twice).isEqualTo(once);
        // exactly one public GET line, not two
        assertThat(twice.split("HttpMethod.GET", -1)).hasSize(2); // one occurrence → split yields 2 parts
    }

    @Test
    void noPublicPatternsLeavesAuthorizationUntouched() {
        String out = SecurityConfigPatcher.applyPatches(BLANKET_LOCKDOWN, List.of());
        assertThat(out).doesNotContain("HttpMethod.GET");
        assertThat(out).contains(".requestMatchers(\"/api/**\").authenticated()");
    }

    @Test
    void skipsWhenNoBlanketCatchAllPresent() {
        // a config that already scopes auth without a broad /api/** authenticated line
        // (whitespace-tolerant strip — text-block indentation is not hand-countable)
        String scoped = BLANKET_LOCKDOWN.replaceAll(
                "(?m)^\\s*\\.requestMatchers\\(\"/api/\\*\\*\"\\)\\.authenticated\\(\\)\\n", "");
        assertThat(scoped).doesNotContain(".requestMatchers(\"/api/**\").authenticated()");
        String out = SecurityConfigPatcher.applyPatches(scoped, PUBLIC);
        assertThat(out).doesNotContain("HttpMethod.GET");
    }

    @Test
    void emitsOnePermitLinePerHttpMethod() {
        // a gym catalogue plus an anonymous enquiry form — the public write must stay exact
        java.util.Map<String, List<String>> byMethod = new java.util.LinkedHashMap<>();
        byMethod.put("GET", List.of("/api/v1/classes/**", "/api/v1/trainers/**"));
        byMethod.put("POST", List.of("/api/v1/enquiries"));

        String out = SecurityConfigPatcher.applyPatches(BLANKET_LOCKDOWN, byMethod);

        assertThat(out).contains(
                ".requestMatchers(HttpMethod.GET, \"/api/v1/classes/**\", \"/api/v1/trainers/**\").permitAll()");
        assertThat(out).contains(".requestMatchers(HttpMethod.POST, \"/api/v1/enquiries\").permitAll()");
        // both precede the catch-all
        assertThat(out.indexOf("HttpMethod.POST"))
                .isLessThan(out.indexOf(".requestMatchers(\"/api/**\").authenticated()"));
    }

    // ── Plan-declared access, end to end (works for any vertical) ────────────

    @TempDir
    Path workspace;

    @Test
    void derivesFromPlanDeclarationForAnUnknownVertical() throws Exception {
        // A pet boarding business: "kennels" is in no catalog allowlist, but the plan says public.
        Path controller = workspace.resolve("backend/src/main/java/com/pets/controller/KennelController.java");
        Files.createDirectories(controller.getParent());
        Files.writeString(controller, """
                package com.pets.controller;

                @RestController
                @RequestMapping("/api/v1/kennels")
                public class KennelController {
                    @GetMapping
                    public ResponseEntity<List<KennelDto>> getKennels() { return null; }
                }
                """);

        ArchitectureSpec spec = new ArchitectureSpec();
        spec.setFiles(List.of(FileSpec.builder()
                .filePath("backend/src/main/java/com/pets/controller/KennelController.java")
                .fileType("BACKEND")
                .apiEndpoints(List.of(ApiEndpoint.builder()
                        .method("GET").path("/api/v1/kennels").access("public").build()))
                .build()));
        ArchitectureJsonUtil.write(workspace, spec);

        var derived = SecurityConfigPatcher.derivePublicPatterns(
                workspace.resolve("backend/src/main/java"), workspace);

        assertThat(derived).containsKey("GET");
        assertThat(derived.get("GET")).contains("/api/v1/kennels/**");
    }

    @Test
    void endpointKeyIsPathVariableNameAgnostic() {
        assertThat(SecurityConfigPatcher.endpointKey("get", "/api/v1/classes/{classId}"))
                .isEqualTo(SecurityConfigPatcher.endpointKey("GET", "/api/v1/classes/{id}"));
    }

    @Test
    void respectsAConfigTheModelAlreadyTiered() {
        String alreadyGood = BLANKET_LOCKDOWN.replaceAll(
                "(?m)^(\\s*)\\.requestMatchers\\(\"/api/v1/admin/\\*\\*\"\\)\\.hasRole\\(\"ADMIN\"\\)\\n",
                "$0$1.requestMatchers(HttpMethod.GET, \"/api/v1/menus/**\").permitAll()\n");
        assertThat(alreadyGood).contains("HttpMethod.GET"); // precondition: model already tiered
        String out = SecurityConfigPatcher.applyPatches(alreadyGood, PUBLIC);
        // no second public-GET line injected
        assertThat(out.split("HttpMethod.GET", -1)).hasSize(2);
    }
}
