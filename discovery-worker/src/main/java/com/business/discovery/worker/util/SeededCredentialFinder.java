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
 */
@Slf4j
public final class SeededCredentialFinder {

    /** Whatever is handed to the encoder is a plaintext password the seeder is planting. */
    private static final Pattern ENCODED_PASSWORD =
            Pattern.compile("\\.encode\\(\\s*\"([^\"]{3,})\"\\s*\\)");
    private static final Pattern EMAIL_LITERAL =
            Pattern.compile("\"([\\w.+-]+@[\\w.-]+\\.\\w+)\"");
    private static final Pattern PROPERTY_DEFAULT =
            Pattern.compile("^admin\\.(email|password)\\s*=\\s*\\$\\{[^:}]+:([^}]*)}", Pattern.MULTILINE);

    public record Credential(String email, String password, String source) {}

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

            Set<String> emails = matches(EMAIL_LITERAL, content);
            Set<String> passwords = matches(ENCODED_PASSWORD, content);
            String name = seeder.getFileName().toString();

            // small cross product — a seeder plants a handful of users at most
            for (String email : emails) {
                for (String password : passwords) {
                    out.add(new Credential(email, password, name));
                }
            }
        }

        String propsContent = read(applicationProperties);
        if (propsContent != null) {
            String email = null;
            String password = null;
            Matcher m = PROPERTY_DEFAULT.matcher(propsContent);
            while (m.find()) {
                if ("email".equals(m.group(1))) email = m.group(2);
                else password = m.group(2);
            }
            if (email != null && password != null && !email.isBlank() && !password.isBlank()) {
                out.add(new Credential(email, password, "application.properties"));
            }
        }

        log.info("[SeededCredentialFinder] {} candidate admin credential(s) from {} seeder file(s)",
                out.size(), seeders.size());
        return out;
    }

    /** Files that plant users: anything invoking a password encoder on a literal. */
    static List<Path> seederSources(Path backendSrcDir) {
        if (!Files.exists(backendSrcDir)) return List.of();
        try (Stream<Path> s = Files.walk(backendSrcDir)) {
            return s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        String c = read(p);
                        return c != null && ENCODED_PASSWORD.matcher(c).find();
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
