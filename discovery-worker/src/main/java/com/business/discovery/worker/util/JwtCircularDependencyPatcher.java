package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Breaks the standard JWT bean cycle — a RUNTIME bug the compiler cannot see, so this
 * runs unconditionally (not only on compile failure).
 *
 * SecurityConfig builds JwtAuthFilter as a bean; JwtAuthFilter's constructor takes the
 * user-loading service (UserService/UserDetailsService); that service (or the
 * AuthenticationManager wiring around it) ends up depending back on SecurityConfig.
 * Spring refuses to start with: "The dependencies of some of the beans in the
 * application context form a cycle" (observed on multifit-aundh — compiled clean,
 * crashed at boot, cost a full smoke-gate cycle to discover).
 *
 * Fix: mark JwtAuthFilter's non-JwtUtil constructor dependency @Lazy. Harmless when the
 * cycle wouldn't have occurred anyway — @Lazy on an already-acyclic dependency changes
 * nothing observable.
 */
@Slf4j
public final class JwtCircularDependencyPatcher {

    private static final Pattern CONSTRUCTOR_PARAM = Pattern.compile(
            "public\\s+JwtAuthFilter\\s*\\(([^)]*)\\)");
    private static final Pattern LAZY_IMPORT =
            Pattern.compile("^import\\s+org\\.springframework\\.context\\.annotation\\.Lazy;\\s*$", Pattern.MULTILINE);
    private static final Pattern PACKAGE_LINE = Pattern.compile("^package\\s+[\\w.]+;\\s*$", Pattern.MULTILINE);

    private JwtCircularDependencyPatcher() {}

    /** Returns true if the file was modified. */
    public static boolean fix(Path backendSrcDir) {
        if (!Files.exists(backendSrcDir)) return false;

        Path jwtAuthFilter;
        try (var s = Files.walk(backendSrcDir)) {
            jwtAuthFilter = s.filter(p -> p.getFileName().toString().equals("JwtAuthFilter.java"))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            log.warn("[JwtCircularDependencyPatcher] Walk failed: {}", e.getMessage());
            return false;
        }
        if (jwtAuthFilter == null) return false;

        try {
            String content = Files.readString(jwtAuthFilter);
            String patched = patch(content);
            if (patched.equals(content)) return false;
            Files.writeString(jwtAuthFilter, patched);
            log.info("[JwtCircularDependencyPatcher] Marked JwtAuthFilter's user-loading dependency @Lazy in {}",
                    jwtAuthFilter.getFileName());
            return true;
        } catch (IOException e) {
            log.warn("[JwtCircularDependencyPatcher] Could not process {}: {}", jwtAuthFilter, e.getMessage());
            return false;
        }
    }

    static String patch(String content) {
        Matcher ctor = CONSTRUCTOR_PARAM.matcher(content);
        if (!ctor.find()) return content;

        String params = ctor.group(1);
        String[] parts = params.split(",");
        StringBuilder rebuilt = new StringBuilder();
        boolean changed = false;

        for (int i = 0; i < parts.length; i++) {
            String param = parts[i].trim();
            if (i > 0) rebuilt.append(", ");
            // JwtUtil never participates in the cycle; every other param is a candidate.
            // Already-annotated params are left untouched (idempotent).
            if (!param.isEmpty() && !param.startsWith("JwtUtil ") && !param.contains("@Lazy")) {
                rebuilt.append("@Lazy ").append(param);
                changed = true;
            } else {
                rebuilt.append(param);
            }
        }
        if (!changed) return content;

        String newParams = rebuilt.toString();
        String result = content.substring(0, ctor.start(1)) + newParams + content.substring(ctor.end(1));

        if (!LAZY_IMPORT.matcher(result).find()) {
            Matcher pkg = PACKAGE_LINE.matcher(result);
            if (pkg.find()) {
                result = result.substring(0, pkg.end()) + "\n\nimport org.springframework.context.annotation.Lazy;"
                        + result.substring(pkg.end());
            }
        }
        return result;
    }
}
