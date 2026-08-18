package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FeatureSpec;
import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.PublicVariable;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    // Fenced backend types + wiring a domain plan must never re-declare (incl. the renamed variants
    // arch_outline already warns about). Shaped types also come from the registry; duplicated here so
    // the reconciler holds the line even when a contract card is absent.
    private static final Set<String> FENCED_BACKEND_NAMES = Set.of(
            "User", "Role", "UserRepository", "UserService", "UserDetailsServiceImpl",
            "JwtUtil", "JwtService", "JwtTokenProvider", "JwtAuthFilter",
            "SecurityConfig", "SecurityConfiguration", "PasswordEncoderConfig", "PasswordEncoder",
            "AuthController", "AdminInitializer", "SpaController",
            "AuthRequest", "AuthResponse", "RegisterRequest",
            "Payment", "PaymentStatus", "PaymentRepository", "PaymentService",
            "PaymentController", "PaymentGateway", "RazorpayPaymentGateway", "DemoPaymentGateway",
            "PaymentGatewayConfig", "RazorpayConfiguration", "GatewayWebhookEvent", "PaymentCapturedEvent",
            "PaymentGatewayException", "ResourceNotFoundException",
            "CreatePaymentRequest", "PaymentOrderResponse", "VerifyPaymentRequest", "PaymentVerificationResponse"
    );

    private static final Set<String> FENCED_FRONTEND_NAMES = Set.of(
            "AuthContext", "useAuth", "authService",
            "Header", "Footer", "Layout", "SiteHeader", "SiteFooter", "SiteLayout",
            "CartContext", "CheckoutContext", "useCheckout"
    );

    // Path fragments (lower-cased) that mark a fenced frontend module regardless of file base name.
    private static final List<String> FENCED_FRONTEND_PATHS = List.of(
            "/api/client.", "/types/auth.", "/shell/", "/cart/",
            "/context/authcontext", "/context/cartcontext", "/context/checkoutcontext",
            "/hooks/useauth", "/services/authservice"
    );

    // Never strip — the worker legitimately generates these against the fenced foundation.
    private static final Set<String> GUARD_NAMES = Set.of("ProtectedRoute", "AdminLayout", "siteConfig");

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

    // ── Predicates ────────────────────────────────────────────────────────────

    private static boolean isFencedRedeclaration(FileSpec f, FoundationSymbolRegistry registry) {
        String base = baseName(f.getFileName());
        if (base.isEmpty() && f.getFilePath() != null) base = baseName(pathFileName(f.getFilePath()));
        if (GUARD_NAMES.contains(base)) return false;
        if (registry.isFenced(base)) return true;
        if (FENCED_BACKEND_NAMES.contains(base) || FENCED_FRONTEND_NAMES.contains(base)) return true;
        return isFencedFrontendPath(f.getFilePath());
    }

    private static boolean isFencedLocationPath(String path) {
        if (path == null) return false;
        String base = baseName(pathFileName(path));
        if (GUARD_NAMES.contains(base)) return false;
        if (FENCED_BACKEND_NAMES.contains(base) || FENCED_FRONTEND_NAMES.contains(base)) return true;
        return isFencedFrontendPath(path);
    }

    private static boolean isFencedFrontendPath(String path) {
        if (path == null) return false;
        String p = path.toLowerCase(Locale.ROOT);
        for (String frag : FENCED_FRONTEND_PATHS) if (p.contains(frag)) return true;
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
