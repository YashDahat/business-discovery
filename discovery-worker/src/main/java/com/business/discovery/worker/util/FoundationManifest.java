package com.business.discovery.worker.util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The single declaration of the foundation's features and the seams each contributes — the OCP
 * onboarding half of {@code docs/foundation-feature-manifest-plan.md} (§1, §5). Every hardcoded
 * "what to skip / never strip / how to gate" set that used to live scattered across
 * {@link ApiInventory}, {@link FoundationRefReconciler}, and {@link RouteManifest} is now a
 * <em>projection</em> of this one declaration:
 *
 * <pre>
 *   FOUNDATION_CONTROLLERS (SDK skip)      = ⋃ features.backend.controllers   → {@link #foundationControllers()}
 *   GUARD_NAMES (never-strip)              = ⋃ features.frontend.guards        → {@link #guardNames()}
 *   FENCED_BACKEND_NAMES (strip re-decl)   = ⋃ features.backend.fenced         → {@link #fencedBackendNames()}
 *   FENCED_FRONTEND_NAMES (strip re-decl)  = ⋃ features.frontend.fenced        → {@link #fencedFrontendNames()}
 *   AUTH_KEYS / NON_NAV_KEYS (route gates) = features.frontend.pages[].gate/.nav → {@link #authPageKeys()} / {@link #nonNavPageKeys()}
 * </pre>
 *
 * <p>Onboarding a new foundation feature is now a single edit — append a {@link Feature} to
 * {@link #DEFAULT} (or ship a {@code foundation.manifest.json}) — instead of touching three files.
 *
 * <p><b>Pruning is DEFERRED</b> (see the plan's scope decision): the kept closure is <em>all</em>
 * features, so the projections reproduce exactly the sets they replaced. {@link #load(Path)} honours a
 * foundation-shipped {@code foundation.manifest.json} for forward-compatibility; when it is absent
 * (the case today — {@code webapp-foundation} ships none) the built-in {@link #DEFAULT} is used, which
 * is the authoritative declaration for the current foundation.
 */
@Slf4j
public final class FoundationManifest {

    public static final String MANIFEST_REL = "foundation.manifest.json";

    // ── Declaration shape (mirrors the plan §1 JSON; forgiving on missing blocks) ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Page(String key, String gate, Boolean nav) {
        public boolean navOrDefault() { return nav == null || nav; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BackendPart(List<String> controllers, List<String> packages, List<String> fenced) {
        public List<String> controllersOrEmpty() { return controllers == null ? List.of() : controllers; }
        public List<String> fencedOrEmpty()      { return fenced == null ? List.of() : fenced; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FrontendPart(List<String> modules, List<String> guards,
                               List<String> fenced, List<Page> pages) {
        public List<String> guardsOrEmpty() { return guards == null ? List.of() : guards; }
        public List<String> fencedOrEmpty() { return fenced == null ? List.of() : fenced; }
        public List<Page>    pagesOrEmpty()  { return pages  == null ? List.of() : pages; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfigPart(List<String> env, List<String> compose) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Feature(String id, boolean core, List<String> requires,
                          BackendPart backend, FrontendPart frontend, ConfigPart config) {}

    // ── The built-in declaration (kept closure = ALL features; pruning deferred) ──

    /**
     * The current foundation, declared once. Each seam below is projected from this. The values
     * reproduce, exactly, the constants that used to be hardcoded in the three consumer classes —
     * {@code FoundationManifestTest} asserts the equivalence so this stays a pure refactor.
     */
    private static final List<Feature> DEFAULT_FEATURES = List.of(
            new Feature("auth", true, List.of(),
                    new BackendPart(
                            List.of("AuthController.java"),
                            List.of("security", "config/SecurityConfig"),
                            List.of("User", "Role", "UserRepository", "UserService", "UserDetailsServiceImpl",
                                    "JwtUtil", "JwtService", "JwtTokenProvider", "JwtAuthFilter",
                                    "SecurityConfig", "SecurityConfiguration", "PasswordEncoderConfig", "PasswordEncoder",
                                    "AuthController", "AdminInitializer",
                                    "AuthRequest", "AuthResponse", "RegisterRequest")),
                    new FrontendPart(
                            List.of("/context/authcontext", "/hooks/useauth", "/services/authservice", "/types/auth."),
                            List.of("ProtectedRoute", "AdminLayout"),
                            List.of("AuthContext", "useAuth", "authService"),
                            List.of(new Page("ACCOUNT", "AUTH", true))),
                    new ConfigPart(List.of("JWT_SECRET"), List.of())),

            new Feature("shell", true, List.of(),
                    new BackendPart(
                            List.of("SpaController.java"),
                            List.of(),
                            List.of("SpaController")),
                    new FrontendPart(
                            List.of("/shell/"),
                            List.of("siteConfig"),
                            List.of("Header", "Footer", "Layout", "SiteHeader", "SiteFooter", "SiteLayout"),
                            List.of()),
                    new ConfigPart(List.of(), List.of())),

            new Feature("exceptions", true, List.of(),
                    new BackendPart(
                            List.of(),
                            List.of("exception"),
                            List.of("ResourceNotFoundException")),
                    new FrontendPart(List.of(), List.of(), List.of(), List.of()),
                    new ConfigPart(List.of(), List.of())),

            new Feature("payment", false, List.of("auth"),
                    new BackendPart(
                            List.of("PaymentController.java"),
                            List.of("gateway", "event"),
                            List.of("Payment", "PaymentStatus", "PaymentRepository", "PaymentService",
                                    "PaymentController", "PaymentGateway", "RazorpayPaymentGateway", "DemoPaymentGateway",
                                    "PaymentGatewayConfig", "RazorpayConfiguration", "GatewayWebhookEvent",
                                    "PaymentCapturedEvent", "PaymentGatewayException",
                                    "CreatePaymentRequest", "PaymentOrderResponse", "VerifyPaymentRequest",
                                    "PaymentVerificationResponse")),
                    new FrontendPart(
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(new Page("CHECKOUT", "AUTH", false))),
                    new ConfigPart(List.of("RAZORPAY_KEY_ID", "RAZORPAY_KEY_SECRET"), List.of())),

            new Feature("cart", false, List.of(),
                    new BackendPart(List.of(), List.of(), List.of()),
                    new FrontendPart(
                            List.of("/src/cart/", "/context/cartcontext", "/context/checkoutcontext"),
                            List.of(),
                            List.of("CartContext", "CheckoutContext", "useCheckout"),
                            List.of(new Page("CART", "PUBLIC", false))),
                    new ConfigPart(List.of(), List.of())),

            new Feature("gallery", false, List.of("auth"),
                    new BackendPart(
                            List.of("GalleryController.java", "MediaController.java", "AdminMediaController.java"),
                            List.of("storage"),
                            List.of()),
                    new FrontendPart(
                            List.of("/components/gallery/", "/hooks/usemedia"),
                            List.of(),
                            List.of(),
                            List.of(new Page("GALLERY", "PUBLIC", true))),
                    new ConfigPart(List.of("S3_ENDPOINT", "S3_BUCKET", "S3_PATH_STYLE"), List.of("minio")))
    );

    private static final FoundationManifest DEFAULT = new FoundationManifest(DEFAULT_FEATURES);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final List<Feature> features;

    private FoundationManifest(List<Feature> features) {
        this.features = features;
    }

    // ── Load ────────────────────────────────────────────────────────────────

    /** The built-in declaration for the current foundation (kept closure = all features). */
    public static FoundationManifest defaultManifest() { return DEFAULT; }

    /**
     * Honours a foundation-shipped {@code foundation.manifest.json} at the workspace root when present;
     * otherwise returns the built-in {@link #DEFAULT}. Graceful degradation (plan §1): a malformed or
     * absent manifest never fails the run — it falls back to the default so a foundation clone predating
     * this feature still works. Pruning being deferred, the loaded manifest is the FULL feature set.
     */
    public static FoundationManifest load(Path workspace) {
        if (workspace == null) return DEFAULT;
        Path file = workspace.resolve(MANIFEST_REL);
        if (!Files.isRegularFile(file)) return DEFAULT;
        try {
            Root root = MAPPER.readValue(file.toFile(), Root.class);
            if (root == null || root.features == null || root.features.isEmpty()) {
                log.warn("[FoundationManifest] {} present but declared no features — using built-in default", file);
                return DEFAULT;
            }
            log.info("[FoundationManifest] Loaded {} feature(s) from {}", root.features.size(), file);
            return new FoundationManifest(root.features);
        } catch (IOException e) {
            log.warn("[FoundationManifest] Could not parse {} ({}) — using built-in default", file, e.getMessage());
            return DEFAULT;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class Root {
        public List<Feature> features;
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    public List<Feature> features() { return features; }

    // ── Seam projections (kept closure = all features today) ────────────────────

    /** Foundation-spine controller file names whose endpoints must NOT become a generated SDK. */
    public Set<String> foundationControllers() {
        return union(features, f -> f.backend() == null ? List.of() : f.backend().controllersOrEmpty());
    }

    /** Worker-generated-against-the-foundation names the reconciler must NEVER strip. */
    public Set<String> guardNames() {
        return union(features, f -> f.frontend() == null ? List.of() : f.frontend().guardsOrEmpty());
    }

    /** Fenced backend class/enum names a domain plan may never re-declare. */
    public Set<String> fencedBackendNames() {
        return union(features, f -> f.backend() == null ? List.of() : f.backend().fencedOrEmpty());
    }

    /** Fenced frontend symbol names a domain plan may never re-declare. */
    public Set<String> fencedFrontendNames() {
        return union(features, f -> f.frontend() == null ? List.of() : f.frontend().fencedOrEmpty());
    }

    /** Route keys that require login but are not admin (gate == AUTH). */
    public Set<String> authPageKeys() {
        return pageKeys(p -> "AUTH".equalsIgnoreCase(p.gate()));
    }

    /** Route keys kept out of primary nav (nav == false) — reached via dedicated UI. */
    public Set<String> nonNavPageKeys() {
        return pageKeys(p -> !p.navOrDefault());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Set<String> pageKeys(java.util.function.Predicate<Page> keep) {
        Set<String> out = new LinkedHashSet<>();
        for (Feature f : features) {
            if (f.frontend() == null) continue;
            for (Page p : f.frontend().pagesOrEmpty()) {
                if (p != null && p.key() != null && keep.test(p)) out.add(p.key());
            }
        }
        return out;
    }

    private static Set<String> union(List<Feature> features,
                                     java.util.function.Function<Feature, List<String>> extract) {
        Set<String> out = new LinkedHashSet<>();
        for (Feature f : features) {
            for (String s : extract.apply(f)) {
                if (s != null && !s.isBlank()) out.add(s);
            }
        }
        return out;
    }
}
