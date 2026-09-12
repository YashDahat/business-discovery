package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FeatureSpec;
import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.FoundationFeatureRef;
import com.business.discovery.worker.service.llm.PublicVariable;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 2 — deterministic post-plan reconciler (the load-bearing lever). Runs on the whole
 * {@link ArchitectureSpec} after enrichment and before the file manifest is built, so the generators
 * never see a file the plan should not have authored. Same family/philosophy as the other spec/output
 * patchers ({@code RolePrefixPatcher}, {@code ApiInventory}) — zero LLM, idempotent.
 *
 * <p>Registry-driven: the fenced <i>shapes</i> come from {@link FoundationSymbolRegistry}. The fenced
 * <i>wiring</i> names (SecurityConfig / JwtAuthFilter / AuthController / … and the frontend auth spine)
 * carry no shape in the contract cards, so they are also held here explicitly — mirroring the
 * "Do not generate" list in {@code backend/FOUNDATION_CONTRACT.md} and the fenced frontend modules —
 * with a guard allow-list for the files the worker DOES generate against the foundation
 * ({@code ProtectedRoute} / {@code AdminLayout} / {@code siteConfig}).
 *
 * <p>Three operations in one pass:
 * <ol>
 *   <li><b>Strip</b> any planned file that re-declares a fenced symbol (both layers), keeping
 *       {@link FeatureSpec#getFilePaths()} in sync.</li>
 *   <li><b>Rewrite handles</b> — a domain field that points at the platform user (by name
 *       {@code userId}/{@code customerId}/… or by type {@code User}) becomes the foundation handle
 *       ({@code Integer userId} = {@code User.id}, set in controllers from {@code @CurrentUser}); a
 *       field typed {@code Payment} becomes {@code String referenceId}. The entity's own PK (a
 *       {@code UUID id}) is never touched.</li>
 *   <li><b>Repair imports</b> — drop {@code imports_from} entries that point at a stripped file or any
 *       fenced foundation location (a handle needs no import).</li>
 * </ol>
 */
@Slf4j
public final class FoundationRefReconciler {

    /** Outcome, returned for logging/tests. */
    public record Result(List<String> strippedFiles, int rewrittenFields, int repairedImports) {
        public boolean changedAnything() {
            return !strippedFiles.isEmpty() || rewrittenFields > 0 || repairedImports > 0;
        }
    }

    /**
     * Outcome of the planning-time cross-check (§6b): the invariant is <em>a file imports a fenced
     * symbol ⟺ that symbol's feature is declared in {@code foundation_features}</em>.
     *
     * @param danglingRefs         a planned file references a NON-CORE foundation feature the plan never
     *                             declared consuming — the meaningful defect (e.g. imports {@code
     *                             PaymentService} but {@code payment} is absent from {@code foundation_features}).
     * @param declaredUnreferenced non-core features declared in {@code foundation_features} that no planned
     *                             file references — likely over-declared (advisory only).
     */
    public record CrossCheckResult(List<Violation> danglingRefs, Set<String> declaredUnreferenced) {
        /** One dangling foundation reference. */
        public record Violation(String filePath, String reference, String featureId) {}
        /** True when every fenced reference is backed by a declaration (the invariant holds). */
        public boolean isClean() { return danglingRefs.isEmpty(); }
    }

    // Fenced backend/frontend names a domain plan must never re-declare (incl. the renamed variants
    // arch_outline already warns about). Shaped types also come from the registry; these NAME-level
    // fences hold the line even when a contract card is absent. Derived from the RUN-ACTIVE
    // FoundationManifest (§5) — read via FoundationManifest.active() so a foundation-shipped
    // foundation.manifest.json drives the fenced surface, not just the built-in default; a new fenced
    // symbol is onboarded by appending to the manifest, not by editing code. Pruning deferred → full
    // kept closure.
    private static Set<String> fencedBackendNames() {
        return FoundationManifest.active().fencedBackendNames();
    }

    private static Set<String> fencedFrontendNames() {
        return FoundationManifest.active().fencedFrontendNames();
    }

    // Built-in BASELINE of fenced frontend module path fragments (lower-cased) — a planned file whose
    // path contains one re-declares a foundation module regardless of its base name. This is UNIONED with
    // the run-active manifest's frontend.modules in isFencedFrontendPath(), so a new foundation module
    // path is onboarded by a manifest edit, not by touching this list; the baseline just guarantees the
    // core auth/cart/shell fences hold even if a shipped manifest declares its modules incompletely.
    // NOTE: "/src/cart/" (the foundation headless cart SPINE — context + hooks), NOT a bare "/cart/",
    // which also matched the app's cart UI at "/src/components/cart/" and wrongly stripped
    // CartItemsTable/CartSummary from the plan → CartPage imported files that never generated (TS2307).
    private static final List<String> FENCED_FRONTEND_PATHS = List.of(
            "/api/client.", "/types/auth.", "/shell/", "/src/cart/",
            "/context/authcontext", "/context/cartcontext", "/context/checkoutcontext",
            "/hooks/useauth", "/services/authservice"
    );

    // Never strip — the worker legitimately generates these against the fenced foundation. Derived
    // from the RUN-ACTIVE FoundationManifest (§5): the union of every feature's frontend.guards.
    private static Set<String> guardNames() {
        return FoundationManifest.active().guardNames();
    }

    // Field names (normalized) that denote a reference to the platform user; type==User handled separately.
    private static final Set<String> USER_REF_NAMES = Set.of(
            "userid", "customerid", "customeruserid", "appuserid", "userref"
    );
    // Backend id/handle types plus the TS spellings a mirrored frontend field may carry.
    private static final Set<String> USER_HANDLE_TYPES = Set.of(
            "UUID", "Long", "Integer", "String", "string", "number", "Number");

    private FoundationRefReconciler() {}

    public static Result reconcile(ArchitectureSpec spec, FoundationSymbolRegistry registry) {
        if (spec == null || spec.getFiles() == null) return new Result(List.of(), 0, 0);

        // ── 1. Strip fenced re-declarations ──────────────────────────────────
        Set<String> strippedPaths = new LinkedHashSet<>();
        List<FileSpec> kept = new ArrayList<>();
        for (FileSpec f : spec.getFiles()) {
            if (isFencedRedeclaration(f, registry)) {
                if (f.getFilePath() != null) strippedPaths.add(f.getFilePath());
                log.info("[FoundationRefReconciler] Strip re-declared fenced file: {}", f.getFilePath());
            } else {
                kept.add(f);
            }
        }
        spec.setFiles(kept);

        if (spec.getFeatures() != null && !strippedPaths.isEmpty()) {
            for (FeatureSpec feat : spec.getFeatures()) {
                if (feat.getFilePaths() != null) feat.getFilePaths().removeIf(strippedPaths::contains);
            }
        }

        // ── 2. Rewrite handles + 3. Repair imports ───────────────────────────
        // The handle NAME and TYPE both come from the registry convention (User → Integer userId,
        // Payment → String referenceId). The backend type is used verbatim; the frontend mirror maps
        // it to its TS spelling (Integer → number, String → string).
        var userConv = registry.referenceConventionFor("User");
        String userHandle = userConv.map(FoundationSymbolRegistry.ReferenceConvention::handleField).orElse("userId");
        String userTypeRaw = userConv.map(FoundationSymbolRegistry.ReferenceConvention::handleType).orElse("Integer");
        var paymentConv = registry.referenceConventionFor("Payment");
        String paymentHandle = paymentConv.map(FoundationSymbolRegistry.ReferenceConvention::handleField).orElse("referenceId");
        String paymentTypeRaw = paymentConv.map(FoundationSymbolRegistry.ReferenceConvention::handleType).orElse("String");

        int rewritten = 0, repaired = 0;
        for (FileSpec f : kept) {
            boolean backend = "BACKEND".equalsIgnoreCase(f.getFileType());
            String userHandleType = backend ? userTypeRaw : toTsType(userTypeRaw);
            String paymentHandleType = backend ? paymentTypeRaw : toTsType(paymentTypeRaw);

            if (f.getPublicVariables() != null) {
                Set<String> seen = new LinkedHashSet<>();
                List<PublicVariable> out = new ArrayList<>();
                for (PublicVariable pv : f.getPublicVariables()) {
                    String typeSimple = simpleName(pv.getType());
                    if (isUserHandle(pv.getName(), typeSimple)
                            && !isAlready(pv, userHandle, userHandleType)) {
                        pv.setName(userHandle);
                        pv.setType(userHandleType);
                        pv.setDescription(handleNote(pv.getDescription(), "platform user"));
                        rewritten++;
                    } else if ("Payment".equals(typeSimple)) {
                        pv.setName(paymentHandle);
                        pv.setType(paymentHandleType);
                        pv.setDescription(handleNote(pv.getDescription(), "payment record"));
                        rewritten++;
                    } else if (isDomainIdField(pv.getName(), paymentHandle) && "UUID".equals(typeSimple)) {
                        // Rule: no UUID primary/foreign keys — domain ids are Long (→ number on the
                        // frontend). userId (Integer) + payment referenceId (String) are handled above.
                        pv.setType(backend ? "Long" : toTsType("Long"));
                        rewritten++;
                    }
                    if (pv.getName() == null || seen.add(pv.getName().toLowerCase(Locale.ROOT))) {
                        out.add(pv);   // collapse duplicate handle names onto the first
                    }
                }
                f.setPublicVariables(out);
            }

            if (f.getImportsFrom() != null) {
                int before = f.getImportsFrom().size();
                f.getImportsFrom().removeIf(imp -> strippedPaths.contains(imp) || isFencedLocationPath(imp));
                repaired += before - f.getImportsFrom().size();
            }
        }

        Result r = new Result(new ArrayList<>(strippedPaths), rewritten, repaired);
        if (r.changedAnything()) {
            log.info("[FoundationRefReconciler] Reconciled spec: stripped {} file(s), rewrote {} field(s), "
                    + "repaired {} import(s)", r.strippedFiles().size(), r.rewrittenFields(), r.repairedImports());
        }
        return r;
    }

    /**
     * Planning-time dangling-reference gate (§6b, "Reconciler target"). Cross-checks the planner's
     * {@code foundation_features} declaration against what the planned files actually import: a file that
     * references a fenced foundation symbol whose (non-core) feature is not declared is a coupling the plan
     * never recorded — enrichment context for it is missing and the artifact's closed-world invariant is
     * broken. Surfaces this as a LOUD warning at planning time rather than as a post-generation compile
     * failure.
     *
     * <p><b>Advisory, never destructive:</b> warns and returns a report; it does not strip files, rewrite
     * the spec, or fail the run — mirroring {@link FoundationCardIntegrity}. Must run <em>before</em>
     * {@link #reconcile} because reconcile's import-repair drops fenced imports, after which no fenced
     * reference remains to check. Reads the run-active {@link FoundationManifest} for feature attribution.
     */
    public static CrossCheckResult crossCheckFoundationRefs(ArchitectureSpec spec) {
        if (spec == null || spec.getFiles() == null) {
            return new CrossCheckResult(List.of(), Set.of());
        }
        // Null on specs written before foundation_features existed → nothing to validate against.
        if (spec.getFoundationFeatures() == null) {
            log.info("[FoundationRefReconciler] No foundation_features declared — skipping foundation-ref cross-check.");
            return new CrossCheckResult(List.of(), Set.of());
        }

        FoundationManifest manifest = FoundationManifest.active();

        Set<String> declared = new LinkedHashSet<>();
        for (FoundationFeatureRef ref : spec.getFoundationFeatures()) {
            if (ref != null && ref.getId() != null && !ref.getId().isBlank()) {
                declared.add(ref.getId().toLowerCase(Locale.ROOT));
            }
        }

        List<CrossCheckResult.Violation> dangling = new ArrayList<>();
        Set<String> referenced = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>(); // de-dupe by file+feature

        for (FileSpec f : spec.getFiles()) {
            for (String ref : refsOf(f)) {
                Optional<String> owner = manifest.owningFeatureId(ref);
                if (owner.isEmpty()) continue;
                String fid = owner.get();
                String fidLower = fid.toLowerCase(Locale.ROOT);
                referenced.add(fidLower);
                // Core features are structurally always present, so a missing declaration for them is a
                // formality, not a dangling reference — only NON-CORE gaps are real defects.
                if (!declared.contains(fidLower) && !manifest.isCore(fid)) {
                    if (seen.add(f.getFilePath() + " " + fidLower)) {
                        dangling.add(new CrossCheckResult.Violation(f.getFilePath(), ref, fid));
                    }
                }
            }
        }

        Set<String> declaredUnreferenced = new LinkedHashSet<>(declared);
        declaredUnreferenced.removeAll(referenced);
        declaredUnreferenced.removeIf(manifest::isCore);

        for (CrossCheckResult.Violation v : dangling) {
            log.warn("[FoundationRefReconciler] Dangling foundation reference: '{}' references foundation feature "
                    + "'{}' (via '{}') but '{}' is NOT declared in foundation_features. The planner used a foundation "
                    + "capability it never declared consuming — its enrichment context is missing and the coupling is "
                    + "unrecorded. Declare it in foundation_features (or drop the reference).",
                    v.filePath(), v.featureId(), v.reference(), v.featureId());
        }
        if (!declaredUnreferenced.isEmpty()) {
            log.info("[FoundationRefReconciler] foundation_features declares {} but no planned file references them "
                    + "(non-core) — likely over-declared.", declaredUnreferenced);
        }
        return new CrossCheckResult(dangling, declaredUnreferenced);
    }

    private static List<String> refsOf(FileSpec f) {
        List<String> refs = new ArrayList<>();
        if (f.getImportsFrom() != null) refs.addAll(f.getImportsFrom());
        if (f.getDependsOn() != null) refs.addAll(f.getDependsOn());
        return refs;
    }

    // ── Predicates ────────────────────────────────────────────────────────────

    private static boolean isFencedRedeclaration(FileSpec f, FoundationSymbolRegistry registry) {
        String base = baseName(f.getFileName());
        if (base.isEmpty() && f.getFilePath() != null) base = baseName(pathFileName(f.getFilePath()));
        if (guardNames().contains(base)) return false;
        if (registry.isFenced(base)) return true;
        if (fencedBackendNames().contains(base) || fencedFrontendNames().contains(base)) return true;
        return isFencedFrontendPath(f.getFilePath());
    }

    private static boolean isFencedLocationPath(String path) {
        if (path == null) return false;
        String base = baseName(pathFileName(path));
        if (guardNames().contains(base)) return false;
        if (fencedBackendNames().contains(base) || fencedFrontendNames().contains(base)) return true;
        return isFencedFrontendPath(path);
    }

    private static boolean isFencedFrontendPath(String path) {
        if (path == null) return false;
        String p = path.toLowerCase(Locale.ROOT);
        for (String frag : FENCED_FRONTEND_PATHS) if (p.contains(frag)) return true;
        // Union the run-active manifest's declared module paths — closes the OCP hole where a new
        // foundation feature's module path (e.g. gallery's /components/gallery/, /hooks/usemedia) was
        // absent from the built-in baseline and slipped through unstripped.
        for (String frag : FoundationManifest.active().fencedFrontendModulePaths()) {
            if (p.contains(frag)) return true;   // manifest paths are already lower-cased
        }
        return false;
    }

    private static boolean isUserHandle(String name, String typeSimple) {
        if ("User".equals(typeSimple)) return true;
        return USER_REF_NAMES.contains(normalize(name)) && USER_HANDLE_TYPES.contains(typeSimple);
    }

    /** A primary/foreign-key id field ({@code id} or camelCase {@code <entity>Id}) — normalized to Long.
     *  Excludes the payment handle ({@code referenceId}), which is a String link, not a numeric key. */
    private static boolean isDomainIdField(String name, String paymentHandle) {
        if (name == null || name.equals(paymentHandle)) return false;
        return name.equals("id") || name.endsWith("Id");
    }

    private static boolean isAlready(PublicVariable pv, String handleField, String handleType) {
        return handleField.equalsIgnoreCase(pv.getName()) && handleType.equals(pv.getType());
    }

    // ── Small helpers ───────────────────────────────────────────────────────

    private static String handleNote(String existing, String what) {
        String note = "Reference to the " + what + " (foundation convention; no DB foreign key).";
        return (existing == null || existing.isBlank()) ? note : existing + " " + note;
    }

    /** Maps a backend handle type to its frontend (TS) spelling. Integer userId → number; String → string. */
    private static String toTsType(String javaType) {
        return switch (javaType) {
            case "Integer", "int", "Long", "long", "Short", "short",
                 "Double", "double", "Float", "float", "BigDecimal", "BigInteger" -> "number";
            case "Boolean", "boolean" -> "boolean";
            default -> "string"; // String, UUID, char, etc.
        };
    }

    /** Simple name of a (possibly fully-qualified / generic / array) type. {@code java.util.UUID} → {@code UUID}. */
    static String simpleName(String type) {
        if (type == null) return "";
        String t = type.trim();
        int lt = t.indexOf('<');
        if (lt >= 0) t = t.substring(0, lt);
        t = t.replace("[]", "").trim();
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }

    private static String baseName(String fileName) {
        if (fileName == null) return "";
        for (String ext : new String[]{".java", ".tsx", ".ts"}) {
            if (fileName.endsWith(ext)) return fileName.substring(0, fileName.length() - ext.length());
        }
        return fileName;
    }

    private static String pathFileName(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
