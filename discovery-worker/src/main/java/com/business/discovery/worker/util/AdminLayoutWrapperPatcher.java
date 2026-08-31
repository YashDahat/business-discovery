package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Deterministic backstop for the admin-page self-wrap defect (issue #2 in
 * docs/frontend-issue-solution-plan-9312afa6.md). The foundation {@code AdminLayout} is an Outlet
 * layout-route component (no {@code children}); {@code RouteManifestGenerator} now mounts the admin
 * pages as children of a single {@code <AdminLayout>} layout route. But the LLM-authored page bodies
 * still wrap their own content in {@code <AdminLayout>…</AdminLayout>} — which fails to type-check
 * (TS2559, no {@code children} prop) and would double-render the chrome even if it did.
 *
 * <p>This strips the wrapper from every admin page: the paired {@code <AdminLayout …>} … {@code
 * </AdminLayout>} becomes a fragment {@code <>} … {@code </>} (a valid single route element whether
 * the wrapper held one child or several), and the now-unused default import
 * {@code import AdminLayout from '@/components/AdminLayout'} is removed. Self-closing
 * {@code <AdminLayout />} is deliberately left alone — it is not the wrapper defect. Pages wrap in
 * several return branches (loading / error / main), so every occurrence is rewritten.
 *
 * <p>Scoped to {@code src/pages/} so it never touches the foundation {@code AdminLayout.tsx} or the
 * generated {@code AppRoutes.tsx} (which legitimately renders {@code <AdminLayout />} in the route
 * element). Zero LLM; idempotent — a page with no {@code <AdminLayout>} wrapper is untouched.
 */
@Slf4j
public final class AdminLayoutWrapperPatcher {

    // Opening <AdminLayout> or <AdminLayout ...attrs...>, but NOT self-closing <AdminLayout ... />.
    private static final Pattern OPEN = Pattern.compile("<AdminLayout\\b(?![^>]*/>)[^>]*>");
    private static final Pattern CLOSE = Pattern.compile("</AdminLayout>");
    // The default import line, tolerant of quote style and trailing semicolon/whitespace.
    private static final Pattern IMPORT = Pattern.compile(
            "(?m)^[ \\t]*import\\s+AdminLayout\\s+from\\s+['\"]@/components/AdminLayout['\"];?[ \\t]*\\r?\\n");

    private AdminLayoutWrapperPatcher() {}

    /** Returns true if any admin page was unwrapped. */
    public static boolean fix(Path frontendSrc) {
        Path pages = frontendSrc.resolve("pages");
        if (!Files.exists(pages)) return false;
        boolean[] changed = {false};
        try (Stream<Path> files = Files.walk(pages)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".tsx"))
                 .forEach(p -> {
                     try {
                         String content = Files.readString(p);
                         String rewritten = rewrite(content);
                         if (!rewritten.equals(content)) {
                             Files.writeString(p, rewritten);
                             changed[0] = true;
                             log.info("[AdminLayoutWrapperPatcher] Unwrapped <AdminLayout> in {}", p.getFileName());
                         }
                     } catch (IOException e) {
                         log.warn("[AdminLayoutWrapperPatcher] Could not process {}: {}", p, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.warn("[AdminLayoutWrapperPatcher] Walk failed for {}: {}", pages, e.getMessage());
        }
        return changed[0];
    }

    static String rewrite(String content) {
        if (!OPEN.matcher(content).find()) return content;   // no wrapper → leave the file (and its import) untouched
        String out = OPEN.matcher(content).replaceAll("<>");
        out = CLOSE.matcher(out).replaceAll("</>");
        out = IMPORT.matcher(out).replaceAll("");
        return out;
    }
}
