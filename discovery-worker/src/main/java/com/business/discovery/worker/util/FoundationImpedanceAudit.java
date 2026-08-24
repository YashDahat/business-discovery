package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Phase 4 — reconciliation safety net (DETECTION half).
 *
 * <p>Last-line, deterministic scan of GENERATED code for residual foundation impedance that slipped
 * Phases 0–2: the identity hacks that only appear in generated controllers, a leftover surrogate user
 * key on an entity/DTO, a UUID primary/foreign-key id (rule: domain ids are Long IDENTITY, never UUID),
 * a stale {@code Role.CUSTOMER}, and frontend ad-hoc token parsing / re-declared user shapes. It writes
 * {@code docs/FOUNDATION_AUDIT.md} and logs a warning; it is advisory — it does NOT fail the build and
 * does NOT rewrite anything (a PK type migration spans entity + DTO + controller + service, too broad
 * to auto-rewrite safely; the generation prompt is the primary enforcer, this net gives visibility).
 *
 * <p><b>Why detection-only for now.</b> The rewrite <i>target</i> now exists — the foundation
 * {@code @CurrentUser} resolver injects the current user's id, and the domain handle is
 * {@code Integer userId} (= {@code User.id}; email/phone are login/lookup keys only, never the link).
 * Detection stays advisory for this pass; the auto-rewrite (identity hack → {@code @CurrentUser
 * Integer userId}; {@code UUID userId} → {@code Integer userId}) can now be added here since the
 * target is no longer missing.
 */
@Slf4j
public final class FoundationImpedanceAudit {

    public enum Layer { BACKEND, FRONTEND }

    public record Finding(String file, int line, String category, String snippet) {}

    public record Result(Layer layer, List<Finding> findings) {
        public boolean clean() { return findings.isEmpty(); }
    }

    // ── Backend signatures ──
    private static final Pattern NAME_UUID   = Pattern.compile("nameUUIDFromBytes\\s*\\(");
    private static final Pattern HARDCODED_UUID = Pattern.compile(
            "UUID\\.fromString\\(\\s*\"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\"");
    private static final Pattern PRINCIPAL_AS_UUID = Pattern.compile(
            "UUID\\.fromString\\(\\s*[^\"]*(getName|getUsername|principal)");
    // The user link must be exactly `Integer userId`. Flag the wrong TYPE (UUID/Long userId) or the
    // wrong NAME (customerId/userUuid/… — should be userId). Correct `Integer userId` is NOT flagged.
    private static final Pattern USER_SURROGATE_KEY = Pattern.compile(
            "\\b(?:private|protected|public)\\s+(?:(?:UUID|Long)\\s+(?:userId|customerId|userUuid|customerUserId)"
          + "|Integer\\s+(?:customerId|userUuid|customerUserId))\\b");
    private static final Pattern WRONG_ROLE_VALUE = Pattern.compile("\\bRole\\.CUSTOMER\\b");
    // Rule: domain ids are Long IDENTITY, never UUID. Flag a UUID primary/foreign-key field
    // (`private UUID id` / `UUID <entity>Id`) or a UUID/AUTO generation strategy. `Integer userId`
    // and the fenced foundation spine (User/Payment) are unaffected. Correct `Long id` is NOT flagged.
    private static final Pattern UUID_ID_FIELD = Pattern.compile(
            "\\b(?:private|protected|public)\\s+UUID\\s+(?:id|\\w+Id)\\b");
    private static final Pattern UUID_ID_STRATEGY = Pattern.compile(
            "GenerationType\\.(?:UUID|AUTO)");

    // ── Frontend signatures ──
    private static final Pattern TOKEN_PARSING = Pattern.compile(
            "localStorage\\.(?:get|set|remove)Item\\(\\s*['\"]token['\"]|\\bjwt[_-]?[dD]ecode\\b");
    private static final Pattern REDECLARED_USER_SHAPE = Pattern.compile(
            "\\b(?:interface|type)\\s+(?:AuthUser|User)\\b");

    // Fenced frontend locations that legitimately touch the token / user shape — never flag these.
    private static final List<String> FENCED_FRONTEND_PATHS = List.of(
            "/api/client.", "/types/auth.", "/shell/", "/cart/",
            "/context/authcontext", "/context/cartcontext", "/context/checkoutcontext",
            "/hooks/useauth", "/services/authservice");

    private FoundationImpedanceAudit() {}

    /** Scans {@code srcRoot}, writes/updates the report, logs, and returns the findings. Never throws. */
    public static Result audit(Path srcRoot, Layer layer, Path workspace, FoundationSymbolRegistry registry) {
        List<Finding> findings = new ArrayList<>();
        if (srcRoot != null && Files.isDirectory(srcRoot)) {
            try (Stream<Path> walk = Files.walk(srcRoot)) {
                for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    if (!matchesLayer(p, layer) || isExcluded(p)) continue;
                    if (layer == Layer.FRONTEND && isFencedFrontend(p)) continue;
                    scanFile(p, layer, workspace, findings);
                }
            } catch (IOException e) {
                log.warn("[FoundationImpedanceAudit] Walk failed at {}: {}", srcRoot, e.getMessage());
            }
        }
        Result result = new Result(layer, findings);
        writeReport(workspace, result, registry);
        if (findings.isEmpty()) {
            log.info("[FoundationImpedanceAudit] {} generated code clean — no residual foundation impedance", layer);
        } else {
            log.warn("[FoundationImpedanceAudit] {} residual foundation-impedance finding(s) in {} generated code "
                    + "(advisory — see docs/FOUNDATION_AUDIT.md): {}",
                    findings.size(), layer, countByCategory(findings));
        }
        return result;
    }

    // ── Scanning ──────────────────────────────────────────────────────────────

    private static void scanFile(Path file, Layer layer, Path workspace, List<Finding> out) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            return;   // binary/unreadable — skip
        }
        String rel = relativize(workspace, file);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (layer == Layer.BACKEND) {
                if (NAME_UUID.matcher(line).find() || HARDCODED_UUID.matcher(line).find()
                        || PRINCIPAL_AS_UUID.matcher(line).find()) {
                    out.add(new Finding(rel, i + 1, "IDENTITY_HACK", line.trim()));
                } else if (USER_SURROGATE_KEY.matcher(line).find()) {
                    out.add(new Finding(rel, i + 1, "USER_SURROGATE_KEY", line.trim()));
                } else if (UUID_ID_FIELD.matcher(line).find() || UUID_ID_STRATEGY.matcher(line).find()) {
                    out.add(new Finding(rel, i + 1, "UUID_ID", line.trim()));
                } else if (WRONG_ROLE_VALUE.matcher(line).find()) {
                    out.add(new Finding(rel, i + 1, "WRONG_ROLE_VALUE", line.trim()));
                }
            } else {
                if (TOKEN_PARSING.matcher(line).find()) {
                    out.add(new Finding(rel, i + 1, "TOKEN_PARSING", line.trim()));
                } else if (REDECLARED_USER_SHAPE.matcher(line).find()) {
                    out.add(new Finding(rel, i + 1, "REDECLARED_USER_SHAPE", line.trim()));
                }
            }
        }
    }

    private static boolean matchesLayer(Path p, Layer layer) {
        String n = p.getFileName().toString();
        return layer == Layer.BACKEND ? n.endsWith(".java") : (n.endsWith(".ts") || n.endsWith(".tsx"));
    }

    private static boolean isExcluded(Path p) {
        String s = p.toString().replace('\\', '/');
        return s.contains("/node_modules/") || s.contains("/dist/") || s.contains("/target/")
                || s.endsWith(".d.ts");
    }

    private static boolean isFencedFrontend(Path p) {
        String s = p.toString().toLowerCase(Locale.ROOT).replace('\\', '/');
        for (String frag : FENCED_FRONTEND_PATHS) if (s.contains(frag)) return true;
        return false;
    }

    // ── Report ──────────────────────────────────────────────────────────────

    private static void writeReport(Path workspace, Result r, FoundationSymbolRegistry registry) {
        if (workspace == null) return;
        try {
            Path docs = workspace.resolve("docs");
            Files.createDirectories(docs);
            Path report = docs.resolve("FOUNDATION_AUDIT.md");
            String start = "<!-- AUDIT:" + r.layer() + ":START -->";
            String end   = "<!-- AUDIT:" + r.layer() + ":END -->";
            String existing = Files.exists(report)
                    ? Files.readString(report)
                    : "# Foundation Impedance Audit\n\nAdvisory — residual foundation impedance in generated "
                    + "code (Phase 4 detection net). Auto-rewrite arrives with the Phase 3 identity primitive.\n";
            Files.writeString(report, upsertSection(existing, start, end, renderSection(r, registry, start, end)));
        } catch (IOException e) {
            log.warn("[FoundationImpedanceAudit] Could not write audit report: {}", e.getMessage());
        }
    }

    private static String renderSection(Result r, FoundationSymbolRegistry registry, String start, String end) {
        String handle = registry == null ? "userId"
                : registry.referenceConventionFor("User")
                        .map(FoundationSymbolRegistry.ReferenceConvention::handleField).orElse("userId");
        StringBuilder sb = new StringBuilder();
        sb.append(start).append("\n## ").append(r.layer()).append('\n');
        if (r.findings().isEmpty()) {
            sb.append("\nClean — no residual foundation impedance.\n");
        } else {
            Map<String, List<Finding>> byCat = r.findings().stream()
                    .collect(Collectors.groupingBy(Finding::category, TreeMap::new, Collectors.toList()));
            sb.append("\n").append(r.findings().size()).append(" finding(s). The domain reference to the platform "
                    + "user should be the foundation handle `").append(handle).append("`.\n");
            byCat.forEach((cat, fs) -> {
                sb.append("\n### ").append(cat).append(" (").append(fs.size()).append(")\n");
                for (Finding f : fs) {
                    sb.append("- `").append(f.file()).append(':').append(f.line()).append("` — ")
                      .append(f.snippet()).append('\n');
                }
            });
        }
        sb.append(end).append('\n');
        return sb.toString();
    }

    private static String upsertSection(String doc, String start, String end, String section) {
        int s = doc.indexOf(start);
        int e = doc.indexOf(end);
        if (s >= 0 && e > s) {
            return doc.substring(0, s) + section.stripTrailing() + doc.substring(e + end.length());
        }
        return doc.stripTrailing() + "\n\n" + section;
    }

    private static Map<String, Long> countByCategory(List<Finding> findings) {
        return findings.stream().collect(Collectors.groupingBy(Finding::category, TreeMap::new, Collectors.counting()));
    }

    private static String relativize(Path workspace, Path file) {
        try {
            return workspace != null ? workspace.relativize(file).toString() : file.getFileName().toString();
        } catch (IllegalArgumentException e) {
            return file.getFileName().toString();
        }
    }
}
