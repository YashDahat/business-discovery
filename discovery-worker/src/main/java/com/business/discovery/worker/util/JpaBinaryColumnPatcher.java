package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Strips MySQL-style {@code columnDefinition = "BINARY(16)"} from JPA {@code @Column} annotations
 * before compile, deterministically and with no LLM cost. The LLM sometimes maps a UUID field with
 * the MySQL idiom for storing UUIDs as raw bytes:
 *
 * <pre>{@code @Column(name = "id", columnDefinition = "BINARY(16)")  private UUID id;}</pre>
 *
 * Postgres has no {@code BINARY} type, so Hibernate {@code ddl-auto} fails at boot with
 * {@code ERROR: type "binary" does not exist}; the table is never created and the app never becomes
 * healthy (MultiFit Aundh smoke-boot death, 2026-07-26). The bug compiles and only surfaces at
 * runtime, so ErrorFixAgent (compile/build-only) never sees it — hence a mechanical strip here.
 *
 * <p>Dropping the columnDefinition lets Hibernate map {@code UUID} to the native Postgres
 * {@code uuid} type, which is what every correctly-generated entity already does. A
 * {@code BINARY(n)} columnDefinition is always wrong on Postgres, so this is safe regardless of the
 * field's Java type. Idempotent — already-clean annotations are left untouched.
 */
@Slf4j
public final class JpaBinaryColumnPatcher {

    private JpaBinaryColumnPatcher() {}

    private static final String CD = "columnDefinition\\s*=\\s*\"BINARY\\(\\d+\\)\"";
    // columnDefinition followed by more attributes: drop it and its trailing comma.
    private static final Pattern CD_TRAILING = Pattern.compile(CD + "\\s*,\\s*");
    // columnDefinition as the last/only-with-leading attribute: drop it and its leading comma.
    private static final Pattern CD_LEADING = Pattern.compile("\\s*,\\s*" + CD);
    // columnDefinition as the sole attribute: drop just the token (leaves "@Column(  )").
    private static final Pattern CD_SOLE = Pattern.compile(CD);
    // @Column() left empty after the strip -> bare @Column.
    private static final Pattern EMPTY_COLUMN = Pattern.compile("@Column\\s*\\(\\s*\\)");

    /** @return true if any file was changed. */
    public static boolean fix(Path backendSrcJava) {
        if (backendSrcJava == null || !Files.isDirectory(backendSrcJava)) return false;

        boolean[] changed = {false};
        try (Stream<Path> files = Files.walk(backendSrcJava)) {
            files.filter(p -> p.toString().endsWith(".java"))
                 .forEach(p -> {
                     if (patchFile(p)) changed[0] = true;
                 });
        } catch (IOException e) {
            log.warn("[JpaBinaryColumnPatcher] walk failed: {}", e.getMessage());
        }
        return changed[0];
    }

    private static boolean patchFile(Path file) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            return false;
        }
        if (!content.contains("BINARY(")) return false;

        String original = content;
        content = CD_TRAILING.matcher(content).replaceAll("");
        content = CD_LEADING.matcher(content).replaceAll("");
        content = CD_SOLE.matcher(content).replaceAll("");
        content = EMPTY_COLUMN.matcher(content).replaceAll("@Column");

        if (content.equals(original)) return false;
        try {
            Files.writeString(file, content);
            log.info("[JpaBinaryColumnPatcher] Stripped BINARY(n) columnDefinition in {}", file.getFileName());
            return true;
        } catch (IOException e) {
            log.warn("[JpaBinaryColumnPatcher] write failed for {}: {}", file, e.getMessage());
            return false;
        }
    }
}
