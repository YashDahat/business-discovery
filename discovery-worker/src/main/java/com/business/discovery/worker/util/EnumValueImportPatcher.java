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
 * Deterministic backstop for issue #4 (docs/frontend-issue-solution-plan-9312afa6.md). Backend enums
 * are now generated as const objects usable as BOTH a value and a type ({@code export const X = {…}
 * as const; export type X = …; export const XValues = […] as const}). A const object is a VALUE, so a
 * consumer that reads it — {@code X.MEMBER}, {@code Object.values(X)}, {@code z.enum(XValues)} — must
 * import it with a plain {@code import { X }}, not {@code import type { X }}. Under
 * {@code isolatedModules}/{@code verbatimModuleSyntax} a type-only import used as a value is a hard
 * error ("'X' cannot be used as a value because it was imported using 'import type'").
 *
 * <p>The LLM naturally writes {@code import type { X }} for anything from {@code @/types}. This pass
 * upgrades exactly those: for every enum used as a value (or any {@code XValues} tuple, which is always
 * a value), it moves the name out of an {@code import type {…}} into a value {@code import {…}} from the
 * same path, leaving any genuinely type-only names behind. Type-only usages ({@code field: X},
 * {@code 'x' as X}) are untouched. Generated/fenced files (first line carries a GENERATED marker) are
 * skipped. Zero LLM; idempotent. Same family as {@link SiteConfigAccessPatcher} / {@code TanStackImportFixer}.
 */
@Slf4j
public final class EnumValueImportPatcher {

    // The derived-type line uniquely marks one of our const-object enums.
    private static final Pattern ENUM_DECL =
            Pattern.compile("export type (\\w+) = typeof \\1\\[keyof typeof \\1]");
    // A type-only import (single- or multi-line — [^}] spans newlines).
    private static final Pattern TYPE_IMPORT =
            Pattern.compile("import\\s+type\\s*\\{([^}]*)}\\s*from\\s*['\"]([^'\"]+)['\"];?");

    private EnumValueImportPatcher() {}

    /** Returns true if any file's enum import was upgraded to a value import. */
    public static boolean fix(Path frontendSrc) {
        if (!Files.exists(frontendSrc)) return false;
        Set<String> enums = discoverEnums(frontendSrc.resolve("types"));
        if (enums.isEmpty()) return false;

        boolean[] changed = {false};
        try (Stream<Path> files = Files.walk(frontendSrc)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".ts") || p.toString().endsWith(".tsx"))
                 .filter(p -> !p.toString().contains("node_modules"))
                 .forEach(p -> {
                     try {
                         String content = Files.readString(p);
                         if (content.stripLeading().startsWith("// GENERATED")) return;  // fenced/derived
                         String rewritten = rewrite(content, enums);
                         if (!rewritten.equals(content)) {
                             Files.writeString(p, rewritten);
                             changed[0] = true;
                             log.info("[EnumValueImportPatcher] Upgraded enum import to a value import in {}", p.getFileName());
                         }
                     } catch (IOException e) {
                         log.warn("[EnumValueImportPatcher] Could not process {}: {}", p, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.warn("[EnumValueImportPatcher] Walk failed for {}: {}", frontendSrc, e.getMessage());
        }
        return changed[0];
    }

    static Set<String> discoverEnums(Path typesDir) {
        Set<String> out = new LinkedHashSet<>();
        if (!Files.exists(typesDir)) return out;
        try (Stream<Path> files = Files.walk(typesDir)) {
            files.filter(p -> p.toString().endsWith(".ts")).forEach(p -> {
                try {
                    Matcher m = ENUM_DECL.matcher(Files.readString(p));
                    while (m.find()) out.add(m.group(1));
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
        return out;
    }

    static String rewrite(String content, Set<String> enums) {
        Matcher m = TYPE_IMPORT.matcher(content);
        StringBuilder out = new StringBuilder();
        boolean any = false;
        while (m.find()) {
            String path = m.group(2);
            List<String> valueNames = new ArrayList<>();
            List<String> typeNames = new ArrayList<>();
            for (String raw : m.group(1).split(",")) {
                String n = raw.trim();
                if (n.isEmpty()) continue;
                if (mustBeValue(n, enums, content)) valueNames.add(n); else typeNames.add(n);
            }
            if (valueNames.isEmpty()) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                continue;
            }
            any = true;
            StringBuilder repl = new StringBuilder("import { ")
                    .append(String.join(", ", valueNames)).append(" } from '").append(path).append("';");
            if (!typeNames.isEmpty()) {
                repl.append("\nimport type { ").append(String.join(", ", typeNames))
                    .append(" } from '").append(path).append("';");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(repl.toString()));
        }
        m.appendTail(out);
        return any ? out.toString() : content;
    }

    private static boolean mustBeValue(String name, Set<String> enums, String content) {
        // A generated values tuple (XValues where X is an enum) is always a runtime value.
        if (name.endsWith("Values") && enums.contains(name.substring(0, name.length() - "Values".length()))) {
            return true;
        }
        // An enum X used in a value position: member/index access, or passed to Object.values / z.enum(...).
        if (enums.contains(name)) {
            String q = Pattern.quote(name);
            Pattern use = Pattern.compile(
                    "\\b" + q + "\\s*[.\\[]"
                  + "|(?:Object\\.values|z\\.nativeEnum|z\\.enum)\\s*\\(\\s*" + q + "\\s*\\)");
            return use.matcher(content).find();
        }
        return false;
    }
}
