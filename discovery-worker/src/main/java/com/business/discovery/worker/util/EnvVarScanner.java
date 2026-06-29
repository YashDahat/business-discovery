package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Scans generated Java source for @Value("${key}") annotations and ensures every
 * referenced property key exists in application.properties.
 *
 * Prevents Spring Boot startup failures caused by missing @Value bindings — a very
 * common failure mode when the LLM generates new config keys that weren't in the
 * scaffold's application.properties.
 */
@Slf4j
public final class EnvVarScanner {

    // Matches @Value("${key}") and @Value("${key:default}") — captures the property key
    private static final Pattern VALUE_ANNOTATION =
            Pattern.compile("@Value\\(\"?\\$\\{([^}:]+)(?::[^}]*)?}\"?\\)");

    private EnvVarScanner() {}

    public static Set<String> scanJavaFiles(Path backendSrc) {
        Set<String> keys = new LinkedHashSet<>();
        if (!Files.exists(backendSrc)) return keys;
        try (Stream<Path> walk = Files.walk(backendSrc)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            Matcher m = VALUE_ANNOTATION.matcher(Files.readString(p));
                            while (m.find()) keys.add(m.group(1).trim());
                        } catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            log.warn("[EnvVarScanner] Could not scan Java files: {}", e.getMessage());
        }
        log.info("[EnvVarScanner] Found {} @Value property keys in backend source", keys.size());
        return keys;
    }

    public static void augmentApplicationProperties(Path propsFile, Set<String> requiredKeys) {
        if (!Files.exists(propsFile) || requiredKeys.isEmpty()) return;
        try {
            String existing = Files.readString(propsFile);
            StringBuilder additions = new StringBuilder();
            for (String key : requiredKeys) {
                // Skip if the key is already bound (exact key= match)
                if (existing.contains(key + "=") || existing.contains(key + " =")) continue;
                String envVar = toEnvVar(key);
                additions.append(key).append("=${").append(envVar).append("}\n");
                log.info("[EnvVarScanner] Added missing @Value key: {} → ${{}}", key, envVar);
            }
            if (!additions.isEmpty()) {
                Files.writeString(propsFile, existing + "\n# Auto-added missing @Value bindings\n" + additions);
            }
        } catch (IOException e) {
            log.warn("[EnvVarScanner] Could not augment application.properties: {}", e.getMessage());
        }
    }

    public static void augmentDotEnvExample(Path workspace, Set<String> requiredKeys) {
        if (requiredKeys.isEmpty()) return;
        Path envExample = workspace.resolve(".env.example");
        try {
            String existing = Files.exists(envExample) ? Files.readString(envExample) : "";
            StringBuilder additions = new StringBuilder();
            for (String key : requiredKeys) {
                String envVar = toEnvVar(key);
                if (!existing.contains(envVar + "=")) {
                    additions.append(envVar).append("=\n");
                }
            }
            if (!additions.isEmpty()) {
                Files.writeString(envExample,
                        existing + "\n# Auto-added by EnvVarScanner\n" + additions);
                log.info("[EnvVarScanner] Added {} keys to .env.example", additions.toString().lines().count() - 2);
            }
        } catch (IOException e) {
            log.warn("[EnvVarScanner] Could not augment .env.example: {}", e.getMessage());
        }
    }

    // jwt.secret → JWT_SECRET, jwt.expiration-ms → JWT_EXPIRATION_MS
    static String toEnvVar(String propertyKey) {
        return propertyKey.toUpperCase().replace('.', '_').replace('-', '_');
    }
}
