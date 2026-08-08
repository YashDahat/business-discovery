package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Deterministic fixer for `process.env.X` in Vite frontend code (Pattern G in
 * docs/frontend-error-pattern-analysis.md). `process` is undefined in the browser under Vite, so the
 * LLM's Next.js/CRA habit (`process.env.NEXT_PUBLIC_*` / `process.env.REACT_APP_*`) fails with
 * `TS2591: Cannot find name 'process'` and would crash at runtime.
 *
 * Rewrites `process.env.NAME` → `import.meta.env.VITE_NAME`, normalising the name to Vite's required
 * VITE_ prefix (stripping a leading NEXT_PUBLIC_/REACT_APP_/VITE_). `import.meta.env.VITE_*` compiles
 * against the `vite/client` types the foundation already references. Zero LLM; additive, idempotent
 * (a file with no `process.env` is untouched, and rewritten output no longer matches the pattern).
 */
@Slf4j
public final class ProcessEnvPatcher {

    // process.env.SOME_NAME  (dot access; the only form generated code uses)
    private static final Pattern PROCESS_ENV =
            Pattern.compile("process\\.env\\.([A-Za-z_$][\\w$]*)");
    private static final Pattern STRIP_PREFIX =
            Pattern.compile("^(NEXT_PUBLIC_|REACT_APP_|VITE_)");

    private ProcessEnvPatcher() {}

    /** Returns true if any file was modified. */
    public static boolean fix(Path frontendSrc) {
        if (!Files.exists(frontendSrc)) return false;
        boolean[] changed = {false};
        try (Stream<Path> files = Files.walk(frontendSrc)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".tsx") || p.toString().endsWith(".ts"))
                 .filter(p -> !p.toString().contains("node_modules"))
                 .filter(p -> !p.toString().contains("/components/ui/"))
                 .forEach(p -> {
                     try {
                         String content = Files.readString(p);
                         if (!content.contains("process.env.")) return;
                         String rewritten = rewrite(content);
                         if (!rewritten.equals(content)) {
                             Files.writeString(p, rewritten);
                             changed[0] = true;
                             log.info("[ProcessEnvPatcher] Rewrote process.env → import.meta.env in {}",
                                     p.getFileName());
                         }
                     } catch (IOException e) {
                         log.warn("[ProcessEnvPatcher] Could not process {}: {}", p, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.warn("[ProcessEnvPatcher] Walk failed for {}: {}", frontendSrc, e.getMessage());
        }
        return changed[0];
    }

    static String rewrite(String content) {
        Matcher m = PROCESS_ENV.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String viteName = "VITE_" + STRIP_PREFIX.matcher(m.group(1)).replaceFirst("");
            m.appendReplacement(sb, Matcher.quoteReplacement("import.meta.env." + viteName));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
