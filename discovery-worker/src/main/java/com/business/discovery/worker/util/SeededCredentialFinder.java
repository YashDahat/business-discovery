package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Recovers the admin credentials the generated seeder actually planted, so the smoke
 * flows can log in and exercise the authenticated surface.
 *
 * They cannot be assumed. The generator picks them per project, ignores the
 * ADMIN_EMAIL/ADMIN_PASSWORD properties it wrote itself, and circuit-house shipped TWO
 * seeders racing to create the same admin with DIFFERENT passwords (DataSeeder: "adminpass";
 * AdminInitializer: "adminpassword" — whichever ran first won). So we read what the code
 * plants rather than what the config claims, and hand back every candidate for the gate
 * to try in turn.
 *
 * Two planting shapes are recognised, because circuit-house 2026-07-17 used the second and
 * this finder saw nothing:
 *   (a) encoder-at-the-call-site — {@code adminUser.setPassword(encoder.encode("adminpass"))}
 *       or {@code new User(email, encoder.encode("pw"), roles)}; the plaintext sits in .encode().
 *   (b) raw-constructor — {@code new User("admin", "adminpass", roles)} then
 *       {@code createUser(u)} where encoding happens INSIDE the service; the plaintext is a
 *       bare constructor argument, and the identifier is a username, not an email.
 */
@Slf4j
public final class SeededCredentialFinder {

    /** Whatever is handed to the encoder is a plaintext password the seeder is planting. */
    private static final Pattern ENCODED_PASSWORD =
            Pattern.compile("\\.encode\\(\\s*\"([^\"]{3,})\"\\s*\\)");
    private static final Pattern EMAIL_LITERAL =
            Pattern.compile("\"([\\w.+-]+@[\\w.-]+\\.\\w+)\"");
    /** First string arg of a User(...) constructor — the login identifier (username or email). */
    private static final Pattern USER_FIRST_ARG =
            Pattern.compile("new\\s+\\w*User\\s*\\(\\s*\"([^\"]+)\"");
    /** Second string arg of a User(...) constructor — a raw (unencoded) password literal. */
    private static final Pattern USER_SECOND_ARG =
            Pattern.compile("new\\s+\\w*User\\s*\\(\\s*\"[^\"]+\"\\s*,\\s*\"([^\"]{3,})\"");
    private static final Pattern PROPERTY_DEFAULT =
            Pattern.compile("^admin\\.(email|password)\\s*=\\s*\\$\\{[^:}]+:([^}]*)}", Pattern.MULTILINE);

    /** identifier is a username OR an email — whatever the login DTO's field wants. */
    public record Credential(String identifier, String password, String source) {}

    private SeededCredentialFinder() {}

    /**
     * Candidates in confidence order: pairs mined from seeder source first (that is what
     * is actually in the database), then the application.properties defaults.
     */
    public static List<Credential> find(Path backendSrcDir, Path applicationProperties) {
        List<Credential> out = new ArrayList<>();

        List<Path> seeders = seederSources(backendSrcDir);
        for (Path seeder : seeders) {
            String content = read(seeder);
            if (content == null) continue;

            // Identifiers: email literals, plus usernames from User(...) constructor first args.
            Set<String> identifiers = matches(EMAIL_LITERAL, content);
            Set<String> usernames = matches(USER_FIRST_ARG, content);
            usernames.removeIf(u -> u.contains("@")); // emails already captured above
            identifiers.addAll(usernames);

            // Passwords: encoder-wrapped literals, plus raw User(...) constructor second args.
            Set<String> passwords = matches(ENCODED_PASSWORD, content);
            passwords.addAll(matches(USER_SECOND_ARG, content));

            String name = seeder.getFileName().toString();
            // small cross product — a seeder plants a handful of users at most
            for (String identifier : identifiers) {
                for (String password : passwords) {
                    out.add(new Credential(identifier, password, name));
                }
            }
        }

        String propsContent = read(applicationProperties);
        if (propsContent != null) {
            String identifier = null;
            String password = null;
            Matcher m = PROPERTY_DEFAULT.matcher(propsContent);
            while (m.find()) {
                if ("email".equals(m.group(1))) identifier = m.group(2);
                else password = m.group(2);
            }
            if (identifier != null && password != null && !identifier.isBlank() && !password.isBlank()) {
                out.add(new Credential(identifier, password, "application.properties"));
            }
        }

        log.info("[SeededCredentialFinder] {} candidate admin credential(s) from {} seeder file(s)",
                out.size(), seeders.size());
        return out;
    }

    /**
     * Files that plant users: either encode a literal password, or construct a User with a
     * literal identifier (the raw-constructor shape, where encoding happens in the service).
     */
    static List<Path> seederSources(Path backendSrcDir) {
        if (!Files.exists(backendSrcDir)) return List.of();
        try (Stream<Path> s = Files.walk(backendSrcDir)) {
            return s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        String c = read(p);
                        return c != null
                                && (ENCODED_PASSWORD.matcher(c).find() || USER_FIRST_ARG.matcher(c).find());
                    })
                    .toList();
        } catch (IOException e) {
            log.warn("[SeededCredentialFinder] Walk failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static Set<String> matches(Pattern pattern, String content) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = pattern.matcher(content);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static String read(Path p) {
        if (p == null || !Files.exists(p)) return null;
        try {
            return Files.readString(p);
        } catch (IOException e) {
            return null;
        }
    }
}
