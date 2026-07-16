package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Breaks the PasswordEncoder bean cycle — the OTHER standard security cycle, the one
 * {@link JwtCircularDependencyPatcher} structurally cannot see.
 *
 * Shape (yeti attempt 4, then circuit-house — two businesses, same generated pattern):
 * SecurityConfig constructor-injects UserService AND declares the {@code PasswordEncoder}
 * {@code @Bean}; UserService constructor-injects PasswordEncoder. Building userService
 * needs securityConfig, which needs userService. Compiles clean; Spring refuses to boot
 * ("dependencies of some of the beans form a cycle"), and the run dies at the smoke gate
 * 120s later with nothing but a ConnectException to show for it. The @Lazy patcher only
 * annotates JwtAuthFilter's constructor — this cycle never passes through JwtAuthFilter.
 *
 * Fix, at the root: the encoder bean must not live in a class that has dependencies.
 *   1. strip {@code @Bean} off the encoder method where it stands — it stays behind as a
 *      plain helper, so internal calls like
 *      {@code authProvider.setPasswordEncoder(passwordEncoder())} keep working verbatim;
 *   2. emit PasswordEncoderConfig.java in the same package — dependency-free, so it can
 *      never sit on a cycle — with the method body copied verbatim (preserves whatever
 *      encoder the generator chose: BCrypt, delegating factory, ...).
 *
 * Runs unconditionally, like the other runtime-correctness patchers: extraction is a
 * no-op behaviorally when no cycle would have formed, and only classes that themselves
 * inject dependencies are touched (a standalone encoder config is already safe).
 */
@Slf4j
public final class PasswordEncoderExtractor {

    /** @Bean (possibly among other annotations) on a no-arg method returning PasswordEncoder. */
    private static final Pattern ENCODER_BEAN = Pattern.compile(
            "@Bean\\s+((?:@\\w+(?:\\([^)]*\\))?\\s+)*public\\s+PasswordEncoder\\s+\\w+\\s*\\(\\s*\\)\\s*\\{)");

    private static final Pattern PACKAGE_LINE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);

    private PasswordEncoderExtractor() {}

    /** Returns true if a file was modified or created. */
    public static boolean fix(Path backendSrcDir) {
        if (!Files.exists(backendSrcDir)) return false;
        try (var s = Files.walk(backendSrcDir)) {
            for (Path file : s.filter(p -> p.getFileName().toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("PasswordEncoderConfig.java")) continue;
                String content = Files.readString(file);
                if (!hostNeedsExtraction(content)) continue;
                return extract(file, content);
            }
        } catch (IOException e) {
            log.warn("[PasswordEncoderExtractor] Walk failed: {}", e.getMessage());
        }
        return false;
    }

    /** An encoder @Bean inside a class that constructor-injects anything = cycle material. */
    static boolean hostNeedsExtraction(String content) {
        if (!ENCODER_BEAN.matcher(content).find()) return false;
        Matcher ctor = Pattern.compile("public\\s+\\w+\\s*\\(([^)]*)\\)\\s*\\{").matcher(content);
        while (ctor.find()) {
            // the class constructor, not a method: heuristic — name matches the class decl
            if (!ctor.group(1).isBlank() && isClassConstructor(content, ctor.start())) return true;
        }
        return false;
    }

    private static boolean isClassConstructor(String content, int matchStart) {
        Matcher cls = Pattern.compile("(?:class)\\s+(\\w+)").matcher(content);
        if (!cls.find()) return false;
        String className = cls.group(1);
        // re-match at this position to compare names
        Matcher m = Pattern.compile("public\\s+(\\w+)\\s*\\(").matcher(content);
        return m.find(matchStart) && m.group(1).equals(className);
    }

    private static boolean extract(Path hostFile, String content) throws IOException {
        Path targetDir = hostFile.getParent();
        Path newConfig = targetDir.resolve("PasswordEncoderConfig.java");
        if (Files.exists(newConfig)) return false; // idempotent across attempts

        Matcher bean = ENCODER_BEAN.matcher(content);
        if (!bean.find()) return false;

        // full method text: from the start of the signature (group 1) through its closing brace
        int bodyOpen = bean.end() - 1; // the '{'
        int bodyClose = matchBrace(content, bodyOpen);
        if (bodyClose < 0) {
            log.warn("[PasswordEncoderExtractor] Unbalanced braces in {} — leaving untouched",
                    hostFile.getFileName());
            return false;
        }
        String method = content.substring(bean.start(1), bodyClose + 1);

        Matcher pkg = PACKAGE_LINE.matcher(content);
        if (!pkg.find()) return false;

        // 1. demote in place: drop ONLY the @Bean annotation, keep the helper method
        String demoted = content.substring(0, bean.start())
                + content.substring(bean.start(1));
        Files.writeString(hostFile, demoted);

        // 2. dependency-free config in the same package, body verbatim, crypto imports carried over
        StringBuilder imports = new StringBuilder();
        content.lines()
               .filter(l -> l.startsWith("import ") && l.contains("security.crypto"))
               .forEach(l -> imports.append(l).append("\n"));

        Files.writeString(newConfig, """
                package %s;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                %s
                /**
                 * Extracted from the security configuration so the encoder bean has no
                 * dependencies and can never participate in a bean cycle
                 * (userService -> securityConfig -> userService).
                 */
                @Configuration
                public class PasswordEncoderConfig {

                    @Bean
                    %s
                }
                """.formatted(pkg.group(1), imports, method));

        log.info("[PasswordEncoderExtractor] Extracted PasswordEncoder @Bean from {} into "
                + "PasswordEncoderConfig — bean cycle broken at the root", hostFile.getFileName());
        return true;
    }

    /** Index of the brace matching the one at {@code open}, or -1. */
    static int matchBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }
}
