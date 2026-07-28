package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Ensures the PasswordEncoder bean is always safely, dependency-free-ly exposed — two
 * distinct defect classes, both living in the same host method, the one
 * {@link JwtCircularDependencyPatcher} structurally cannot see.
 *
 * Case 1 — the cycle (yeti attempt 4, then circuit-house — two businesses, same pattern):
 * SecurityConfig constructor-injects UserService AND declares the {@code PasswordEncoder}
 * {@code @Bean}; UserService constructor-injects PasswordEncoder. Building userService
 * needs securityConfig, which needs userService. Compiles clean; Spring refuses to boot
 * ("dependencies of some of the beans form a cycle"), and the run dies at the smoke gate
 * 120s later with nothing but a ConnectException to show for it. The @Lazy patcher only
 * annotates JwtAuthFilter's constructor — this cycle never passes through JwtAuthFilter.
 *
 * Case 2 — the missing bean entirely (Vikram's Fitness Studio, 2026-07-19): {@code
 * passwordEncoder()} exists but carries no {@code @Bean} at all — not a cycle, just never
 * registered. UserService (or anything else) constructor-injecting {@code PasswordEncoder}
 * then fails the exact same way ("No qualifying bean of type PasswordEncoder"), regardless
 * of whether the host class has dependencies of its own.
 *
 * Fix, at the root, for both: the encoder bean must not live in a class that has
 * dependencies, and it must actually be a bean.
 *   1. if {@code @Bean} is present, strip it off the encoder method where it stands — it
 *      stays behind as a plain helper, so internal calls like
 *      {@code authProvider.setPasswordEncoder(passwordEncoder())} keep working verbatim;
 *      if {@code @Bean} was never there, the host method is left untouched (nothing to strip);
 *   2. emit PasswordEncoderConfig.java in the same package — dependency-free, so it can
 *      never sit on a cycle, and always {@code @Bean}-annotated — with the method body
 *      copied verbatim (preserves whatever encoder the generator chose: BCrypt, delegating
 *      factory, ...).
 *
 * Runs unconditionally, like the other runtime-correctness patchers: extraction is a
 * no-op behaviorally when neither defect is present (an already-@Bean'd, dependency-free
 * encoder config is left alone).
 */
@Slf4j
public final class PasswordEncoderExtractor {

    /** @Bean (possibly among other annotations) on a no-arg method returning PasswordEncoder. */
    private static final Pattern ENCODER_BEAN = Pattern.compile(
            "@Bean\\s+((?:@\\w+(?:\\([^)]*\\))?\\s+)*public\\s+PasswordEncoder\\s+\\w+\\s*\\(\\s*\\)\\s*\\{)");

    /** The same shape with no @Bean at all — registers no bean regardless of host dependencies. */
    private static final Pattern PLAIN_ENCODER_METHOD = Pattern.compile(
            "(public\\s+PasswordEncoder\\s+\\w+\\s*\\(\\s*\\)\\s*\\{)");

    private static final Pattern PACKAGE_LINE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);

    private PasswordEncoderExtractor() {}

    /** Returns true if a file was modified or created. */
    public static boolean fix(Path backendSrcDir) {
        if (!Files.exists(backendSrcDir)) return false;

        List<Path> sources;
        try (Stream<Path> s = Files.walk(backendSrcDir)) {
            sources = s.filter(p -> p.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            log.warn("[PasswordEncoderExtractor] Walk failed: {}", e.getMessage());
            return false;
        }

        // Whether ANY class already declares the encoder @Bean. Critical for the plain-method
        // case: after a previous extraction the host keeps a demoted, un-annotated helper
        // method on purpose, and promoting that a second time would declare a DUPLICATE
        // PasswordEncoder bean (NoUniqueBeanDefinitionException — a boot failure caused by
        // the patcher itself). A per-package sibling check is not enough: the demoted host
        // and the extracted config are only siblings when extraction put them there.
        boolean encoderAlreadyProvided = MissingBeanPatcher.isProvidedSomewhere(sources, "PasswordEncoder");

        for (Path file : sources) {
            if (file.getFileName().toString().equals("PasswordEncoderConfig.java")) continue;
            String content = read(file);
            if (content == null) continue;
            try {
                if (ENCODER_BEAN.matcher(content).find()) {
                    // case 1 — the cycle: only a defect when the host injects dependencies
                    if (!hostHasParameterizedConstructor(content)) continue;
                    return extract(file, content, true);
                }
                // case 2 — no @Bean anywhere on this method: only a defect when nothing
                // else declares the bean either
                if (!encoderAlreadyProvided && PLAIN_ENCODER_METHOD.matcher(content).find()) {
                    return extract(file, content, false);
                }
            } catch (IOException e) {
                log.warn("[PasswordEncoderExtractor] Could not patch {}: {}", file.getFileName(), e.getMessage());
            }
        }
        return false;
    }

    private static boolean hostHasParameterizedConstructor(String content) {
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

    private static boolean extract(Path hostFile, String content, boolean hasBean) throws IOException {
        Path targetDir = hostFile.getParent();
        Path newConfig = targetDir.resolve("PasswordEncoderConfig.java");
        if (Files.exists(newConfig)) return false; // idempotent across attempts

        Matcher method = hasBean ? ENCODER_BEAN.matcher(content) : PLAIN_ENCODER_METHOD.matcher(content);
        if (!method.find()) return false;
        Matcher bean = method;

        // full method text: from the start of the signature (group 1) through its closing brace
        int bodyOpen = method.end(1) - 1; // the '{'
        int bodyClose = matchBrace(content, bodyOpen);
        if (bodyClose < 0) {
            log.warn("[PasswordEncoderExtractor] Unbalanced braces in {} — leaving untouched",
                    hostFile.getFileName());
            return false;
        }
        String methodText = content.substring(method.start(1), bodyClose + 1);

        Matcher pkg = PACKAGE_LINE.matcher(content);
        if (!pkg.find()) return false;

        if (hasBean) {
            // demote in place: drop ONLY the @Bean annotation, keep the helper method
            String demoted = content.substring(0, bean.start())
                    + content.substring(bean.start(1));
            Files.writeString(hostFile, demoted);
        }
        // else: @Bean was never there — nothing to strip, host stays exactly as it was

        // dependency-free config in the same package, body verbatim, crypto imports carried over
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
                 * dependencies (can never participate in a bean cycle) and is guaranteed
                 * to actually be registered (a plain, un-@Bean'd factory method compiles
                 * clean but registers nothing).
                 */
                @Configuration
                public class PasswordEncoderConfig {

                    @Bean
                    %s
                }
                """.formatted(pkg.group(1), imports, methodText));

        log.info("[PasswordEncoderExtractor] {} PasswordEncoder from {} into PasswordEncoderConfig",
                hasBean ? "Extracted @Bean" : "Promoted un-annotated method", hostFile.getFileName());
        return true;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            return null;
        }
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
