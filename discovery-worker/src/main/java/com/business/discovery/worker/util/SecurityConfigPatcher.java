package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Patches LLM-generated SecurityConfig.java with the canonical beans and rules
 * that every Spring Boot 4 / Spring Security 6 project requires but the LLM
 * consistently gets wrong:
 *
 *  1. PasswordEncoder @Bean (BCryptPasswordEncoder) — AdminInitializer needs it
 *  2. DaoAuthenticationProvider using constructor API (not deprecated setter)
 *  3. AuthenticationManager @Bean via AuthenticationConfiguration — AuthController needs it
 *  4. Static assets + SPA routes permitted — React SPA must load without a session
 *  5. anyRequest().permitAll() — server-side auth is on /api/** only; React handles UI auth
 *
 * Runs before ErrorFixAgent in BackendValidationNode so these structural gaps don't
 * consume agent rounds that should be spent on real business logic errors.
 */
@Slf4j
public final class SecurityConfigPatcher {

    private SecurityConfigPatcher() {}

    /**
     * Finds SecurityConfig.java under backendSrcDir and patches it.
     * Returns true if any changes were made.
     */
    public static boolean patch(Path backendSrcDir) {
        Optional<Path> configFile = findSecurityConfig(backendSrcDir);
        if (configFile.isEmpty()) {
            log.warn("[SecurityConfigPatcher] SecurityConfig.java not found under {} — skipping", backendSrcDir);
            return false;
        }

        Path file = configFile.get();
        try {
            String original = Files.readString(file);
            String patched = applyPatches(original);
            if (!patched.equals(original)) {
                Files.writeString(file, patched);
                log.info("[SecurityConfigPatcher] Patched SecurityConfig.java at {}", file);
                return true;
            }
            log.info("[SecurityConfigPatcher] SecurityConfig.java already correct — no changes needed");
            return false;
        } catch (IOException e) {
            log.warn("[SecurityConfigPatcher] Could not patch SecurityConfig.java: {}", e.getMessage());
            return false;
        }
    }

    static String applyPatches(String content) {
        content = fixImports(content);
        content = fixDaoProviderConstructor(content);
        content = fixPasswordEncoderBean(content);
        content = fixUserDetailsServiceField(content);
        content = fixAuthenticationProviderBean(content);
        content = fixAuthenticationManagerBean(content);
        content = fixFilterChainPermitRules(content);
        return content;
    }

    // ── 1. Imports ────────────────────────────────────────────────────────────

    private static String fixImports(String content) {
        String[][] needed = {
            {"BCryptPasswordEncoder",       "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder"},
            {"PasswordEncoder",             "org.springframework.security.crypto.password.PasswordEncoder"},
            {"DaoAuthenticationProvider",   "org.springframework.security.authentication.dao.DaoAuthenticationProvider"},
            {"AuthenticationManager",       "org.springframework.security.authentication.AuthenticationManager"},
            {"AuthenticationConfiguration", "org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration"},
        };

        for (String[] pair : needed) {
            String simpleName = pair[0];
            String fqn        = pair[1];
            if (!content.contains(simpleName) || content.contains("import " + fqn)) continue;
            content = insertImport(content, fqn);
        }
        return content;
    }

    private static String insertImport(String content, String fqn) {
        // Insert after the last existing import line
        int lastImport = content.lastIndexOf("\nimport ");
        if (lastImport == -1) return content;
        int end = content.indexOf('\n', lastImport + 1);
        if (end == -1) return content;
        return content.substring(0, end + 1) + "import " + fqn + ";\n" + content.substring(end + 1);
    }

    // ── 2. DaoAuthenticationProvider — constructor API (Spring Security 6.4+) ─

    private static String fixDaoProviderConstructor(String content) {
        // Old: new DaoAuthenticationProvider(); provider.setUserDetailsService(x);
        // New: new DaoAuthenticationProvider(x);
        if (!content.contains("setUserDetailsService(")) return content;

        // Extract the argument passed to setUserDetailsService
        int setIdx = content.indexOf("setUserDetailsService(");
        int argStart = setIdx + "setUserDetailsService(".length();
        int argEnd = content.indexOf(")", argStart);
        if (argStart == -1 || argEnd == -1) return content;
        String arg = content.substring(argStart, argEnd).trim();

        // Replace: new DaoAuthenticationProvider() → new DaoAuthenticationProvider(arg)
        content = content.replace("new DaoAuthenticationProvider()", "new DaoAuthenticationProvider(" + arg + ")");

        // Remove the now-redundant setUserDetailsService call (whole statement line)
        int lineStart = content.lastIndexOf('\n', setIdx) + 1;
        int lineEnd = content.indexOf('\n', setIdx);
        if (lineEnd != -1) {
            content = content.substring(0, lineStart) + content.substring(lineEnd + 1);
            log.info("[SecurityConfigPatcher] Migrated DaoAuthenticationProvider to constructor API");
        }
        return content;
    }

    // ── 3. PasswordEncoder @Bean ──────────────────────────────────────────────

    private static String fixPasswordEncoderBean(String content) {
        if (content.contains("PasswordEncoder") && content.contains("BCryptPasswordEncoder")) return content;

        String bean = """

                    @Bean
                    public PasswordEncoder passwordEncoder() {
                        return new BCryptPasswordEncoder();
                    }
                """;
        return insertBeforeFilterChain(content, bean);
    }

    // ── 4. UserDetailsService field injection ─────────────────────────────────

    private static String fixUserDetailsServiceField(String content) {
        // Only inject if DaoAuthenticationProvider is present but UserDetailsService field is missing
        if (!content.contains("DaoAuthenticationProvider")) return content;
        if (content.contains("UserDetailsService") || content.contains("userDetailsService")) return content;

        // Find package name to derive a reasonable import
        String pkg = extractPackage(content);
        String serviceImport = pkg != null ? "import " + pkg.replace(".config", ".service") + ".UserService;\n" : "";

        // Add @Autowired UserService field after class opening brace
        String field = "\n    @Autowired\n    private UserService userDetailsService;\n";
        int classBody = content.indexOf('{', content.indexOf("class "));
        if (classBody == -1) return content;

        if (!serviceImport.isEmpty() && !content.contains("UserService")) {
            content = insertImport(content, serviceImport.replace("import ", "").replace(";\n", ""));
        }
        return content.substring(0, classBody + 1) + field + content.substring(classBody + 1);
    }

    // ── 5. DaoAuthenticationProvider @Bean ───────────────────────────────────

    private static String fixAuthenticationProviderBean(String content) {
        if (content.contains("DaoAuthenticationProvider") && content.contains("authenticationProvider()")) return content;
        if (!content.contains("PasswordEncoder")) return content; // don't add without PasswordEncoder

        String bean = """

                    @Bean
                    public DaoAuthenticationProvider authenticationProvider() {
                        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
                        provider.setPasswordEncoder(passwordEncoder());
                        return provider;
                    }
                """;
        return insertBeforeFilterChain(content, bean);
    }

    // ── 6. AuthenticationManager @Bean ───────────────────────────────────────

    private static String fixAuthenticationManagerBean(String content) {
        if (content.contains("AuthenticationManager")) return content;

        String bean = """

                    @Bean
                    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
                        return config.getAuthenticationManager();
                    }
                """;
        return insertBeforeFilterChain(content, bean);
    }

    // ── 7. Filter chain permit rules ──────────────────────────────────────────

    private static String fixFilterChainPermitRules(String content) {
        // Fix anyRequest().authenticated() → anyRequest().permitAll()
        // API security is enforced per-endpoint; React handles UI-layer auth
        if (content.contains("anyRequest().authenticated()")) {
            content = content.replace("anyRequest().authenticated()", "anyRequest().permitAll()");
            log.info("[SecurityConfigPatcher] Replaced anyRequest().authenticated() with anyRequest().permitAll()");
        }

        // Ensure static assets and SPA routes are in the permit list
        if (!content.contains("/index.html") && !content.contains("assets/**")) {
            String staticPermit = """
                                .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico",
                                                 "/*.js", "/*.css", "/*.svg", "/*.png", "/*.ico").permitAll()
                    """;
            // Insert before the first .requestMatchers block inside authorizeHttpRequests
            int authIdx = content.indexOf("authorizeHttpRequests");
            if (authIdx != -1) {
                int firstMatcher = content.indexOf(".requestMatchers(", authIdx);
                if (firstMatcher != -1) {
                    content = content.substring(0, firstMatcher) + staticPermit + content.substring(firstMatcher);
                    log.info("[SecurityConfigPatcher] Injected static asset permit rules");
                }
            }
        }
        return content;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String insertBeforeFilterChain(String content, String bean) {
        int idx = content.indexOf("SecurityFilterChain");
        if (idx == -1) idx = content.indexOf("filterChain(");
        if (idx == -1) return content + bean; // fallback: append
        // Find the @Bean annotation before SecurityFilterChain
        int beanAnnotation = content.lastIndexOf("@Bean", idx);
        if (beanAnnotation == -1) return content;
        // Find start of the line with @Bean
        int lineStart = content.lastIndexOf('\n', beanAnnotation) + 1;
        return content.substring(0, lineStart) + bean + content.substring(lineStart);
    }

    private static Optional<Path> findSecurityConfig(Path srcDir) {
        try (Stream<Path> stream = Files.walk(srcDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().equals("SecurityConfig.java"))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static String extractPackage(String content) {
        int pkg = content.indexOf("package ");
        if (pkg == -1) return null;
        int semi = content.indexOf(';', pkg);
        if (semi == -1) return null;
        return content.substring(pkg + "package ".length(), semi).trim();
    }
}
