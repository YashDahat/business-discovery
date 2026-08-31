package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    // Matches ${ENV_VAR:default} placeholders in application.properties — captures env var name + default.
    // Env var names are upper-snake by convention; the default runs up to the closing brace and may
    // itself contain ':' (e.g. ${S3_ENDPOINT:http://minio:9000}).
    private static final Pattern ENV_PLACEHOLDER =
            Pattern.compile("\\$\\{([A-Z0-9_]+):([^}]*)}");

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
                if (existing.contains(key + "=") || existing.contains(key + " =")) continue;

                // Check if a near-match exists with dots vs hyphens swapped (e.g. jwt.expiration-ms vs jwt.expiration.ms).
                // If so, copy the concrete value as an alias instead of creating an env-var reference
                // that would fail at startup if the env var is never set.
                String fuzzyValue = findFuzzyValue(key, existing);
                if (fuzzyValue != null) {
                    additions.append(key).append("=").append(fuzzyValue).append("\n");
                    log.info("[EnvVarScanner] Added format alias: {}={} (matched existing key with different separator)", key, fuzzyValue);
                } else {
                    String envVar = toEnvVar(key);
                    additions.append(key).append("=${").append(envVar).append("}\n");
                    log.info("[EnvVarScanner] Added missing @Value key: {} → ${{}}", key, envVar);
                }
            }
            if (!additions.isEmpty()) {
                Files.writeString(propsFile, existing + "\n# Auto-added missing @Value bindings\n" + additions);
            }
        } catch (IOException e) {
            log.warn("[EnvVarScanner] Could not augment application.properties: {}", e.getMessage());
        }
    }

    /**
     * Finds the value of an existing property whose key differs only in dot/hyphen separators.
     * e.g. looking up "jwt.expiration.ms" finds "jwt.expiration-ms=86400000" and returns "86400000".
     * Returns null if no such near-match exists.
     */
    private static String findFuzzyValue(String key, String propertiesContent) {
        String normalizedKey = key.replace('-', '.');
        for (String line : propertiesContent.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || !trimmed.contains("=")) continue;
            int eqIdx = trimmed.indexOf('=');
            String existingKey = trimmed.substring(0, eqIdx).trim();
            if (existingKey.equals(key)) continue; // exact match — caller already handled this
            if (existingKey.replace('-', '.').equals(normalizedKey)) {
                return trimmed.substring(eqIdx + 1).trim();
            }
        }
        return null;
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

    /**
     * Extracts the developer-provided defaults declared in application.properties
     * (the {@code default} in every {@code ${ENV_VAR:default}} placeholder). Only non-empty
     * defaults are returned — an empty default (e.g. {@code ${RAZORPAY_KEY_SECRET:}}) signals a
     * real secret with no usable stand-in. First occurrence wins on duplicates.
     *
     * These defaults are the single source of truth for typed config: {@code ${S3_PATH_STYLE:true}}
     * means "true" is a valid boolean value, so it is the correct thing to seed into .env — far
     * safer than a generic placeholder string that a boolean/int @Value cannot parse.
     */
    public static Map<String, String> envDefaultsFromProperties(Path propsFile) {
        Map<String, String> defaults = new LinkedHashMap<>();
        if (propsFile == null || !Files.exists(propsFile)) return defaults;
        try {
            Matcher m = ENV_PLACEHOLDER.matcher(Files.readString(propsFile));
            while (m.find()) {
                String name = m.group(1);
                String def = m.group(2);
                if (!def.isEmpty()) defaults.putIfAbsent(name, def);
            }
        } catch (IOException e) {
            log.warn("[EnvVarScanner] Could not read defaults from {}: {}", propsFile, e.getMessage());
        }
        return defaults;
    }

    /**
     * Backfills blank values in .env.example using the typed defaults declared in
     * application.properties. Prevents a runtime boot-death class: the smoke harness fills any
     * blank env var with the string {@code demo-placeholder}, which a typed @Value binding cannot
     * parse — e.g. {@code S3_PATH_STYLE=} → {@code demo-placeholder} → "Invalid boolean value".
     * Seeding the real default ({@code S3_PATH_STYLE=true}) keeps the blank branch from ever firing.
     *
     * Secrets stay blank: they have an empty default in application.properties, so they are absent
     * from the defaults map and the smoke harness still supplies a placeholder for them.
     */
    public static void backfillEnvExampleDefaults(Path workspace) {
        Path envExample = workspace.resolve(".env.example");
        Path propsFile = workspace.resolve("backend/src/main/resources/application.properties");
        if (!Files.exists(envExample)) return;

        Map<String, String> defaults = envDefaultsFromProperties(propsFile);
        if (defaults.isEmpty()) return;

        try {
            List<String> lines = Files.readAllLines(envExample);
            int filled = 0;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) continue;
                int eq = trimmed.indexOf('=');
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                if (value.isEmpty() && defaults.containsKey(key)) {
                    lines.set(i, key + "=" + defaults.get(key));
                    filled++;
                }
            }
            if (filled > 0) {
                Files.writeString(envExample, String.join("\n", lines) + "\n");
                log.info("[EnvVarScanner] Backfilled {} typed default(s) into .env.example from application.properties", filled);
            }
        } catch (IOException e) {
            log.warn("[EnvVarScanner] Could not backfill .env.example defaults: {}", e.getMessage());
        }
    }

    // jwt.secret → JWT_SECRET, jwt.expiration-ms → JWT_EXPIRATION_MS
    static String toEnvVar(String propertyKey) {
        return propertyKey.toUpperCase().replace('.', '_').replace('-', '_');
    }
}
