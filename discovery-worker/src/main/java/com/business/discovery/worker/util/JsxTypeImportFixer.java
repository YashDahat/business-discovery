package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Deterministic fixer for the single most recurrent frontend build breaker:
 * `TS2503: Cannot find namespace 'JSX'`.
 *
 * The LLM habitually annotates component return types as `: JSX.Element`, but under
 * React 19's jsx-runtime the global JSX namespace no longer exists. React exports it
 * as a type — adding `import type { JSX } from 'react'` makes every such annotation
 * compile without touching the (otherwise correct) code.
 *
 * Prompt rules now forbid the annotation; this pass guarantees the class is extinct
 * even when the model slips. Zero LLM cost, additive-only edit.
 */
@Slf4j
public final class JsxTypeImportFixer {

    private static final Pattern USES_JSX_NAMESPACE = Pattern.compile("\\bJSX\\.");
    private static final Pattern ALREADY_IMPORTS_JSX =
            Pattern.compile("import\\s+type\\s*\\{[^}]*\\bJSX\\b[^}]*}\\s*from\\s*['\"]react['\"]");
    // Leading prologue the import must go AFTER, never displace: a module directive
    // ('use client'/'use strict') or a fence/banner comment (e.g. // GENERATED foundation scaffold,
    // whose position on line 1 FrontendGeneratorNode.isFenced() depends on).
    private static final Pattern USE_DIRECTIVE =
            Pattern.compile("['\"]use (client|strict)['\"];?");

    private JsxTypeImportFixer() {}

    /** Returns true if any file was modified. */
    public static boolean fix(Path frontendSrc) {
        if (!Files.exists(frontendSrc)) return false;
        boolean[] changed = {false};
        try (Stream<Path> files = Files.walk(frontendSrc)) {
            List<Path> targets = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".tsx") || p.toString().endsWith(".ts"))
                    .filter(p -> !p.toString().contains("node_modules"))
                    .toList();
            for (Path file : targets) {
                try {
                    String content = Files.readString(file);
                    if (!USES_JSX_NAMESPACE.matcher(content).find()) continue;
                    if (ALREADY_IMPORTS_JSX.matcher(content).find()) continue;
                    Files.writeString(file, withJsxImport(content));
                    changed[0] = true;
                    log.info("[JsxTypeImportFixer] Added JSX type import to {}", file.getFileName());
                } catch (IOException e) {
                    log.warn("[JsxTypeImportFixer] Could not process {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[JsxTypeImportFixer] Walk failed: {}", e.getMessage());
        }
        return changed[0];
    }

    /**
     * Inserts the JSX type import after any leading prologue — a {@code 'use client'}/{@code 'use strict'}
     * directive, fence/banner {@code //} comments, or blank lines — so it never displaces the first line
     * (which for a fenced file carries the marker {@code isFenced()} reads). Falls back to the top when
     * there is no prologue.
     */
    static String withJsxImport(String content) {
        List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));
        int insertAt = 0;
        while (insertAt < lines.size()) {
            String t = lines.get(insertAt).strip();
            boolean prologue = t.isEmpty() || t.startsWith("//") || USE_DIRECTIVE.matcher(t).matches();
            if (!prologue) break;
            insertAt++;
        }
        lines.add(insertAt, "import type { JSX } from 'react';");
        return String.join("\n", lines);
    }
}
