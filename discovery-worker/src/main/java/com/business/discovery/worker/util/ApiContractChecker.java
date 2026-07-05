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
 * Reconciles the frontend's API calls with the backend's actual routes — a runtime
 * contract the compiler cannot check. Three failure modes seen on multifit-aundh, all
 * shipping green PRs while the running site 500s/404s:
 *   1. Prefix doubling: client baseURL '/api/v1' + service path '/api/v1/...'  → '/api/v1/api/v1/...'
 *   2. Wrong path: GET /api/v1/leads/trial (public POST) vs admin list /api/v1/admin/leads/trial
 *   3. Method mismatch: frontend GETs a path the backend only exposes as POST
 *
 * #1 is deterministically safe to auto-fix (empty the baseURL so the full service paths
 * become authoritative — the hand-fix that worked). #2/#3 are reported to
 * docs/API_CONTRACT_REPORT.md and logged, but NOT made fatal: frontend calls with
 * dynamically-built paths can't all be statically resolved, so blocking would risk
 * false-positive task failures on otherwise-working sites. The report gives the signal
 * for the behavioral gate / human review without that risk.
 */
@Slf4j
public final class ApiContractChecker {

    private static final Pattern BASE_URL =
            Pattern.compile("baseURL\\s*:\\s*['\"]([^'\"]*)['\"]");
    private static final Pattern FRONTEND_CALL = Pattern.compile(
            "(?:apiClient|client|api)\\.(get|post|put|delete|patch)(?:<[^>]*>)?\\(\\s*[`'\"]([^`'\"]+)[`'\"]");
    private static final Pattern CLASS_MAPPING =
            Pattern.compile("@RequestMapping\\(\\s*(?:value\\s*=\\s*)?[\"']([^\"']*)[\"']");
    private static final Pattern METHOD_MAPPING = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch)Mapping\\(\\s*(?:value\\s*=\\s*)?[\"']([^\"']*)[\"']");
    private static final Pattern METHOD_MAPPING_NOPATH = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch)Mapping(\\s*\\(\\s*\\)|\\s*(?=\\n|\\r|[^(]))");

    private record Call(String method, String path) {}

    private ApiContractChecker() {}

    /** Applies the safe prefix-doubling fix and writes a mismatch report. Never throws. */
    public static void fixAndReport(Path frontendSrc, Path backendSrcJava) {
        try {
            boolean fixed = fixPrefixDoubling(frontendSrc);
            List<String> mismatches = report(frontendSrc, backendSrcJava);
            if (fixed) log.info("[ApiContractChecker] Normalized API baseURL to prevent prefix doubling");
            if (!mismatches.isEmpty()) {
                log.warn("[ApiContractChecker] {} unresolved frontend→backend contract mismatch(es) — see docs/API_CONTRACT_REPORT.md",
                        mismatches.size());
            }
        } catch (Exception e) {
            log.warn("[ApiContractChecker] Contract check failed (non-fatal): {}", e.getMessage());
        }
    }

    // ── #1 prefix doubling — deterministic auto-fix ────────────────────────

    static boolean fixPrefixDoubling(Path frontendSrc) throws IOException {
        Path client = frontendSrc.resolve("api/client.ts");
        if (!Files.exists(client)) return false;
        String content = Files.readString(client);
        Matcher m = BASE_URL.matcher(content);
        if (!m.find()) return false;
        String baseUrl = m.group(1);
        if (baseUrl.isEmpty()) return false;

        List<Call> calls = frontendCalls(frontendSrc);
        if (calls.isEmpty()) return false;
        long repeating = calls.stream().filter(c -> c.path().startsWith(baseUrl)).count();

        // If most service paths already carry the baseURL prefix, the client would double it.
        // Empty the baseURL so the (full, authoritative) service paths are used verbatim.
        if (repeating * 2 >= calls.size()) {
            String patched = content.substring(0, m.start(1)) + content.substring(m.end(1));
            Files.writeString(client, patched);
            return true;
        }
        return false;
    }

    // ── #2/#3 report ───────────────────────────────────────────────────────

    static List<String> report(Path frontendSrc, Path backendSrcJava) throws IOException {
        String baseUrl = effectiveBaseUrl(frontendSrc);
        List<Call> calls = frontendCalls(frontendSrc);
        Set<String> backend = backendEndpoints(backendSrcJava); // "GET /api/v1/admin/trainers/*"

        List<String> mismatches = new ArrayList<>();
        for (Call c : calls) {
            String full = normalize(joinUrl(baseUrl, c.path()));
            String key = c.method().toUpperCase() + " " + full;
            if (matches(key, backend)) continue;
            boolean pathExistsOtherMethod = backend.stream()
                    .anyMatch(b -> segmentsMatch(b.substring(b.indexOf(' ') + 1), full));
            mismatches.add(key + (pathExistsOtherMethod
                    ? "  (path exists but not for " + c.method().toUpperCase() + " — method or path mismatch)"
                    : "  (no backend route matches this path)"));
        }

        if (backendSrcJava != null && Files.exists(backendSrcJava)) {
            writeReport(frontendSrc, baseUrl, calls, backend, mismatches);
        }
        return mismatches;
    }

    private static void writeReport(Path frontendSrc, String baseUrl, List<Call> calls,
                                    Set<String> backend, List<String> mismatches) {
        try {
            Path docs = frontendSrc.getParent().getParent().resolve("docs");
            Files.createDirectories(docs);
            StringBuilder sb = new StringBuilder("# API Contract Report\n\n");
            sb.append("Effective client baseURL: `").append(baseUrl.isEmpty() ? "(empty)" : baseUrl).append("`\n\n");
            sb.append("## Mismatches (").append(mismatches.size()).append(")\n");
            if (mismatches.isEmpty()) sb.append("_None — every resolvable frontend call maps to a backend route._\n");
            else mismatches.forEach(mm -> sb.append("- ").append(mm).append("\n"));
            sb.append("\n## Backend routes (").append(backend.size()).append(")\n");
            backend.stream().sorted().forEach(b -> sb.append("- ").append(b).append("\n"));
            sb.append("\n## Frontend calls (").append(calls.size()).append(")\n");
            calls.forEach(c -> sb.append("- ").append(c.method().toUpperCase()).append(" ")
                    .append(joinUrl(baseUrl, c.path())).append("\n"));
            Files.writeString(docs.resolve("API_CONTRACT_REPORT.md"), sb.toString());
        } catch (IOException e) {
            log.warn("[ApiContractChecker] Could not write report: {}", e.getMessage());
        }
    }

    // ── Parsing ──────────────────────────────────────────────────────────

    private static String effectiveBaseUrl(Path frontendSrc) throws IOException {
        Path client = frontendSrc.resolve("api/client.ts");
        if (!Files.exists(client)) return "";
        Matcher m = BASE_URL.matcher(Files.readString(client));
        return m.find() ? m.group(1) : "";
    }

    private static List<Call> frontendCalls(Path frontendSrc) throws IOException {
        List<Call> calls = new ArrayList<>();
        List<Path> roots = List.of(frontendSrc.resolve("services"), frontendSrc.resolve("hooks"), frontendSrc.resolve("api"));
        for (Path root : roots) {
            if (!Files.exists(root)) continue;
            try (Stream<Path> s = Files.walk(root)) {
                for (Path f : s.filter(p -> p.toString().endsWith(".ts")).toList()) {
                    Matcher m = FRONTEND_CALL.matcher(Files.readString(f));
                    while (m.find()) {
                        String path = m.group(2);
                        if (path.startsWith("/") || path.startsWith("http")) calls.add(new Call(m.group(1), path));
                    }
                }
            }
        }
        return calls;
    }

    private static Set<String> backendEndpoints(Path backendSrcJava) throws IOException {
        Set<String> endpoints = new LinkedHashSet<>();
        if (backendSrcJava == null || !Files.exists(backendSrcJava)) return endpoints;
        try (Stream<Path> s = Files.walk(backendSrcJava)) {
            for (Path f : s.filter(p -> p.toString().endsWith("Controller.java")).toList()) {
                String content = Files.readString(f);
                Matcher cm = CLASS_MAPPING.matcher(content);
                String base = cm.find() ? cm.group(1) : "";
                Matcher mm = METHOD_MAPPING.matcher(content);
                while (mm.find()) {
                    String method = mm.group(1).toUpperCase();
                    endpoints.add(method + " " + normalize(joinUrl(base, mm.group(2))));
                }
                Matcher mn = METHOD_MAPPING_NOPATH.matcher(content);
                while (mn.find()) {
                    endpoints.add(mn.group(1).toUpperCase() + " " + normalize(base));
                }
            }
        }
        return endpoints;
    }

    // ── Path helpers ──────────────────────────────────────────────────────

    static String joinUrl(String base, String path) {
        if (base == null) base = "";
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!path.startsWith("/") && !path.startsWith("http")) path = "/" + path;
        return base + path;
    }

    /** Strip query, drop protocol/host, turn {id}/${x}/:param into * wildcards. */
    static String normalize(String url) {
        int q = url.indexOf('?');
        if (q >= 0) url = url.substring(0, q);
        url = url.replaceFirst("^https?://[^/]+", "");
        url = url.replaceAll("\\$\\{[^}]*}", "*").replaceAll("\\{[^}]*}", "*").replaceAll(":[A-Za-z0-9_]+", "*");
        if (url.length() > 1 && url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private static boolean matches(String frontendKey, Set<String> backend) {
        String fm = frontendKey.substring(0, frontendKey.indexOf(' '));
        String fp = frontendKey.substring(frontendKey.indexOf(' ') + 1);
        for (String b : backend) {
            String bm = b.substring(0, b.indexOf(' '));
            String bp = b.substring(b.indexOf(' ') + 1);
            if (bm.equals(fm) && segmentsMatch(bp, fp)) return true;
        }
        return false;
    }

    /** Segment-wise equality with * matching any single segment. */
    static boolean segmentsMatch(String a, String b) {
        String[] as = a.split("/");
        String[] bs = b.split("/");
        if (as.length != bs.length) return false;
        for (int i = 0; i < as.length; i++) {
            if (as[i].equals("*") || bs[i].equals("*")) continue;
            if (!as[i].equals(bs[i])) return false;
        }
        return true;
    }
}
