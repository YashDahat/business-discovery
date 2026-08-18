package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Deterministic patcher for non-platform framework navigation/router imports (Theme C / F2 in
 * docs/frontend-error-patterns-abs-fitness.md). The platform frontend is fixed — Vite +
 * {@code react-router-dom} — but the LLM may reach for whatever framework its priors suggest (Next.js,
 * Remix, bare {@code react-router}, …), whose router imports either don't resolve (TS2307) or point at
 * the wrong package. This normalizes them to the platform router.
 *
 * Two transform classes, driven by tables so a new framework is one entry, not new code:
 * <ol>
 *   <li><b>Drop-in module swaps</b> — frameworks whose router API is identical to react-router-dom
 *       (Remix is react-router under the hood; bare {@code react-router} re-exports the same hooks), so
 *       just swap the module path: {@code from '@remix-run/react'} / {@code from 'react-router'} →
 *       {@code from 'react-router-dom'}. Hook names and shapes are unchanged.</li>
 *   <li><b>Next.js</b> ({@code next/navigation}, {@code next/router}) — object-shaped router
 *       ({@code useRouter().push(x)}), so it needs a call-shape rewrite: {@code useRouter} →
 *       {@code useNavigate}; {@code r.push/replace(x)} → {@code r(x)}; {@code r.back/forward/refresh()}
 *       → {@code r(-1/1/0)}.</li>
 * </ol>
 *
 * Non-React frameworks (Vue/Svelte/Angular) and imports with no safe 1:1 mapping (e.g. Gatsby's
 * standalone {@code navigate}, {@code next/link}'s {@code href}) are deliberately NOT rewritten — a blind
 * swap would produce nonsense. Those are prevented at planning level (FORBIDDEN_NPM_PACKAGES + the F6
 * stack pin) and left to the ErrorFixAgent. Zero LLM; idempotent (rewritten output imports only the
 * platform router).
 */
@Slf4j
public final class FrameworkNavigationPatcher {

    /** The fixed platform router module — the replacement target for every framework nav import. */
    private static final String PLATFORM_ROUTER = "react-router-dom";

    // Frameworks whose router/navigation exports are drop-in compatible with react-router-dom
    // (same hook names + shapes). Add a framework here and the module swap covers it automatically.
    private static final List<String> REEXPORT_MODULES = List.of("@remix-run/react", "react-router");

    // ...from '<reexport-module>'  — module path captured for an exact (non-substring) swap. The trailing
    // quote group means `react-router` never matches inside `react-router-dom`.
    private static final Pattern REEXPORT_IMPORT = Pattern.compile(
            "(from\\s*['\"])(" + alternation(REEXPORT_MODULES) + ")(['\"])");

    // Next.js router (object-shaped API) — module + call-shape rewrite.
    private static final Pattern NEXT_NAV_IMPORT = Pattern.compile(
            "import\\s*\\{([^}]*)\\}\\s*from\\s*['\"]next/(?:navigation|router)['\"]\\s*;?");
    private static final Pattern ROUTER_DECL = Pattern.compile(
            "(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*useRouter\\s*\\(\\s*\\)");

    private FrameworkNavigationPatcher() {}

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
                         String rewritten = rewrite(content);
                         if (!rewritten.equals(content)) {
                             Files.writeString(p, rewritten);
                             changed[0] = true;
                             log.info("[FrameworkNavigationPatcher] Normalized framework nav imports → {} in {}",
                                     PLATFORM_ROUTER, p.getFileName());
                         }
                     } catch (IOException e) {
                         log.warn("[FrameworkNavigationPatcher] Could not process {}: {}", p, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.warn("[FrameworkNavigationPatcher] Walk failed for {}: {}", frontendSrc, e.getMessage());
        }
        return changed[0];
    }

    static String rewrite(String content) {
        // 1. Drop-in module swaps (Remix, bare react-router → react-router-dom): names/shapes unchanged.
        String out = REEXPORT_IMPORT.matcher(content).replaceAll(m ->
                Matcher.quoteReplacement(m.group(1) + PLATFORM_ROUTER + m.group(3)));
        // 2. Next.js — module + call-shape rewrite (only when the import names useRouter).
        out = rewriteNext(out);
        return out;
    }

    private static String rewriteNext(String content) {
        Matcher imp = NEXT_NAV_IMPORT.matcher(content);
        boolean importsUseRouter = false;
        while (imp.find()) {
            if (containsName(imp.group(1), "useRouter")) { importsUseRouter = true; break; }
        }
        if (!importsUseRouter) return content;

        // Collect router variable names bound from useRouter() before we rewrite the call.
        Set<String> routerVars = new LinkedHashSet<>();
        Matcher decl = ROUTER_DECL.matcher(content);
        while (decl.find()) routerVars.add(decl.group(1));

        // Replace the whole next/* import that includes useRouter with the platform import. Other next
        // hooks it named are dropped — they don't resolve here and would error anyway (ErrorFixAgent).
        String out = NEXT_NAV_IMPORT.matcher(content).replaceAll(m ->
                containsName(m.group(1), "useRouter")
                        ? Matcher.quoteReplacement("import { useNavigate } from '" + PLATFORM_ROUTER + "';")
                        : Matcher.quoteReplacement(m.group()));

        out = out.replaceAll("useRouter\\s*\\(\\s*\\)", "useNavigate()");

        for (String v : routerVars) {
            String q = Pattern.quote(v);
            out = out.replaceAll(q + "\\.push\\s*\\(",          Matcher.quoteReplacement(v + "("));
            out = out.replaceAll(q + "\\.replace\\s*\\(",       Matcher.quoteReplacement(v + "("));
            out = out.replaceAll(q + "\\.back\\s*\\(\\s*\\)",    Matcher.quoteReplacement(v + "(-1)"));
            out = out.replaceAll(q + "\\.forward\\s*\\(\\s*\\)", Matcher.quoteReplacement(v + "(1)"));
            out = out.replaceAll(q + "\\.refresh\\s*\\(\\s*\\)", Matcher.quoteReplacement(v + "(0)"));
        }
        return out;
    }

    /** Regex alternation of the modules, each quoted so `/`, `-`, `@` are literal. */
    private static String alternation(List<String> modules) {
        return modules.stream().map(Pattern::quote).reduce((a, b) -> a + "|" + b).orElse("(?!)");
    }

    // Is `name` one of the comma-separated import specifiers in `{ ... }` (ignoring `as` aliases)?
    private static boolean containsName(String braceBody, String name) {
        for (String part : braceBody.split(",")) {
            String n = part.trim();
            int as = n.indexOf(" as ");
            if (as >= 0) n = n.substring(0, as).trim();
            if (n.equals(name)) return true;
        }
        return false;
    }
}
