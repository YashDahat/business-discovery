package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.ApiEndpoint;
import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FileSpec;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
 *  6. Tiered /api authorization — a blanket .requestMatchers("/api/**").authenticated()
 *     403s the public catalog to anonymous visitors (circuit-house 2026-07-17: every menu,
 *     events and testimonials GET returned 403, the storefront was unreachable). We insert a
 *     public-GET permit derived from the real controllers, classified by {@link ApiAccessPolicy}.
 *
 * Runs before ErrorFixAgent in BackendValidationNode so these structural gaps don't
 * consume agent rounds that should be spent on real business logic errors.
 */
@Slf4j
public final class SecurityConfigPatcher {

    private SecurityConfigPatcher() {}

    /** Heuristic-only overload — no plan available to read access declarations from. */
    public static boolean patch(Path backendSrcDir) {
        return patch(backendSrcDir, null);
    }

    /**
     * Finds SecurityConfig.java under backendSrcDir and patches it.
     *
     * @param workspace project root, used to read the plan's per-endpoint {@code access}
     *                  declarations from ARCHITECTURE.json; null falls back to the heuristic
     * @return true if any changes were made
     */
    public static boolean patch(Path backendSrcDir, Path workspace) {
        Optional<Path> configFile = findSecurityConfig(backendSrcDir);
        if (configFile.isEmpty()) {
            log.warn("[SecurityConfigPatcher] SecurityConfig.java not found under {} — skipping", backendSrcDir);
            return false;
        }

        Path file = configFile.get();
        try {
            String original = Files.readString(file);
            String patched = applyPatches(original, derivePublicPatterns(backendSrcDir, workspace));
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

    /** Backward-compatible overload — no derived public paths (structural patches only). */
    static String applyPatches(String content) {
        return applyPatches(content, Map.of());
    }

    /** Convenience overload for GET-only public patterns. */
    static String applyPatches(String content, List<String> publicGetPatterns) {
        return applyPatches(content,
                publicGetPatterns.isEmpty() ? Map.of() : Map.of("GET", publicGetPatterns));
    }

    static String applyPatches(String content, Map<String, List<String>> publicByMethod) {
        content = fixImports(content);
        content = fixDaoProviderConstructor(content);
        content = fixPasswordEncoderBean(content);
        content = fixUserDetailsServiceField(content);
        content = fixAuthenticationProviderBean(content);
        content = fixAuthenticationManagerBean(content);
        content = fixFilterChainPermitRules(content);
        content = fixApiAuthorizationTiers(content, publicByMethod);
        return content;
    }

    /**
     * Public matchers per HTTP method, derived from the controllers on disk and classified by
     * {@link ApiAccessPolicy} — using the plan's own {@code access} declaration where present,
     * which is what makes this work for a gym or a clinic and not just a restaurant.
     *
     * Reads controller SOURCE (not compiled classes), so it works at pre-compile patch time.
     * Degrades to empty on any parse failure — the patcher then leaves authorization untouched.
     *
     * GET uses the domain glob (a public catalogue is public in whole); every other method
     * uses the exact path, so a public contact-form POST never opens the rest of its domain.
     */
    static Map<String, List<String>> derivePublicPatterns(Path backendSrcDir, Path workspace) {
        try {
            Map<String, String> declared = readDeclaredAccess(workspace);
            Map<String, TreeSet<String>> byMethod = new TreeMap<>();

            for (ApiInventory.Endpoint e : ApiInventory.extract(backendSrcDir).endpoints()) {
                String access = declared.get(endpointKey(e.httpMethod(), e.path()));
                if (ApiAccessPolicy.classify(e.httpMethod(), e.path(), access) != ApiAccessPolicy.Tier.PUBLIC) {
                    continue;
                }
                boolean isGet = "GET".equalsIgnoreCase(e.httpMethod());
                String pattern = isGet
                        ? ApiAccessPolicy.publicPathPattern(e.path())
                        : ApiAccessPolicy.exactMatcherPattern(e.path());
                if (pattern == null || pattern.contains("/auth/")) continue; // already permitted
                byMethod.computeIfAbsent(e.httpMethod().toUpperCase(), k -> new TreeSet<>()).add(pattern);
            }

            // GET first — the catalogue line is the one a human reads for.
            Map<String, List<String>> out = new LinkedHashMap<>();
            if (byMethod.containsKey("GET")) out.put("GET", List.copyOf(byMethod.remove("GET")));
            byMethod.forEach((m, p) -> out.put(m, List.copyOf(p)));
            return out;
        } catch (Exception e) {
            log.warn("[SecurityConfigPatcher] Could not derive public endpoints: {}", e.getMessage());
            return Map.of();
        }
    }

    /** "METHOD /normalised/path" → the plan's declared access, for every planned endpoint. */
    static Map<String, String> readDeclaredAccess(Path workspace) {
        Map<String, String> out = new HashMap<>();
        if (workspace == null || !ArchitectureJsonUtil.exists(workspace)) return out;
        try {
            ArchitectureSpec spec = ArchitectureJsonUtil.read(workspace);
            if (spec.getFiles() == null) return out;
            for (FileSpec f : spec.getFiles()) {
                if (f.getApiEndpoints() == null) continue;
                for (ApiEndpoint ep : f.getApiEndpoints()) {
                    if (ep.getAccess() == null || ep.getPath() == null) continue;
                    out.put(endpointKey(ep.getMethod(), ep.getPath()), ep.getAccess());
                }
            }
        } catch (IOException e) {
            log.warn("[SecurityConfigPatcher] Could not read access declarations: {}", e.getMessage());
        }
        return out;
    }

    /** Path-variable-name agnostic key, so {id} and {orderId} match between plan and controller. */
    static String endpointKey(String method, String path) {
        String m = method == null ? "" : method.trim().toUpperCase();
        String p = path == null ? "" : path.trim();
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (!p.startsWith("/")) p = "/" + p;
        p = p.replaceAll("\\{[^}]*}", "{}");
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return m + " " + p;
    }

    // ── 1. Imports ────────────────────────────────────────────────────────────

    private static String fixImports(String content) {
        String[][] needed = {
            {"BCryptPasswordEncoder",       "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder"},
            {"PasswordEncoder",             "org.springframework.security.crypto.password.PasswordEncoder"},
            {"DaoAuthenticationProvider",   "org.springframework.security.authentication.dao.DaoAuthenticationProvider"},
            {"AuthenticationManager",       "org.springframework.security.authentication.AuthenticationManager"},
            {"AuthenticationConfiguration", "org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration"},
            {"HttpMethod",                  "org.springframework.http.HttpMethod"},
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

    // ── 8. Tiered /api authorization — open the public catalog to anonymous visitors ─

    // The blanket lockdown we insert the public-GET permit in front of. MULTILINE so the
    // leading indent is captured and reused. Tolerates an optional version segment.
    private static final Pattern API_CATCHALL_AUTHENTICATED = Pattern.compile(
            "(?m)^([ \\t]*)\\.requestMatchers\\(\\s*\"/api/(?:v\\d+/)?\\*\\*\"\\s*\\)\\s*\\.authenticated\\(\\)");

    private static String fixApiAuthorizationTiers(String content, Map<String, List<String>> publicByMethod) {
        if (publicByMethod.isEmpty()) return content;
        // Idempotent, and respects a config the model already tiered correctly.
        if (content.contains(".requestMatchers(HttpMethod.")) return content;

        Matcher m = API_CATCHALL_AUTHENTICATED.matcher(content);
        if (!m.find()) {
            log.info("[SecurityConfigPatcher] No blanket /api/** authenticated matcher — public tiering skipped");
            return content;
        }

        String indent = m.group(1);
        StringBuilder permitLines = new StringBuilder();
        publicByMethod.forEach((method, patterns) -> {
            if (patterns.isEmpty()) return;
            String quoted = patterns.stream().map(p -> "\"" + p + "\"").collect(Collectors.joining(", "));
            permitLines.append(indent).append(".requestMatchers(HttpMethod.").append(method)
                       .append(", ").append(quoted).append(").permitAll()\n");
        });
        if (permitLines.isEmpty()) return content;

        // Insert immediately BEFORE the catch-all so the public rules are evaluated first
        // (Spring authorizes top-down, first match wins).
        content = content.substring(0, m.start()) + permitLines + content.substring(m.start());

        if (!content.contains("import org.springframework.http.HttpMethod;")) {
            content = insertImport(content, "org.springframework.http.HttpMethod");
        }
        log.info("[SecurityConfigPatcher] Tiered API authorization — public matchers by method: {}",
                publicByMethod);
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
