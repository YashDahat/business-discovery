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
import java.util.Locale;
import java.util.Optional;
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
 * foundation-shipped {@code foundation.manifest.json}: {@code webapp-foundation} now ships one at its
 * root (a faithful mirror of {@link #DEFAULT}), so onboarding a new foundation feature is an edit THERE,
 * not a pipeline code change. {@link #DEFAULT} remains the fallback for a foundation clone that predates
 * or omits the file.
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
        public List<String> modulesOrEmpty() { return modules == null ? List.of() : modules; }
        public List<String> guardsOrEmpty()  { return guards == null ? List.of() : guards; }
        public List<String> fencedOrEmpty()  { return fenced == null ? List.of() : fenced; }
        public List<Page>    pagesOrEmpty()   { return pages  == null ? List.of() : pages; }
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

    /**
     * The manifest the seams project from for the current run. Defaults to {@link #DEFAULT}. A worker
     * generates exactly one project per process (stateless, short-lived), so {@code ProjectPlanningNode}
     * {@link #activate(FoundationManifest) activates} the workspace-loaded manifest once — right after the
     * foundation clone — and every seam ({@link ApiInventory}, {@link FoundationRefReconciler},
     * {@link RouteManifest}) reads it through {@link #active()} thereafter. Until activation (and in unit
     * tests that never activate) this stays the built-in default, so behaviour is unchanged. {@code
     * volatile} because planning and later nodes may run on different threads within the process.
     */
    private static volatile FoundationManifest active = DEFAULT;

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
     * Install {@code manifest} as this run's active declaration — the one every seam projects from.
     * Called once by {@code ProjectPlanningNode} right after the foundation clone, with {@link #load(Path)}'s
     * result, so a foundation-shipped {@code foundation.manifest.json} actually drives the seams instead of
     * only the built-in default. {@code null} resets to {@link #DEFAULT}. Idempotent.
     */
    public static void activate(FoundationManifest manifest) {
        active = (manifest == null) ? DEFAULT : manifest;
    }

    /** The manifest the seams project from for this run (the activated one, or the built-in default). */
    public static FoundationManifest active() { return active; }

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

    /** Whether {@code featureId} is a core (structural, never-prunable) feature — auth, shell, exceptions. */
    public boolean isCore(String featureId) {
        if (featureId == null) return false;
        for (Feature f : features) {
            if (featureId.equalsIgnoreCase(f.id())) return f.core();
        }
        return false;
    }

    /**
     * The id of the feature whose fenced surface {@code reference} touches, if any — used by the
     * {@link FoundationRefReconciler} cross-check to attribute a fenced import/dependency string to its
     * declaring feature. Matches by fenced symbol simple-name (backend + frontend fenced, guards, and
     * controller base names) first, then by a fenced frontend module path fragment. Best-effort: a
     * reference that maps to no feature (a domain symbol, or a bare alias like {@code @/cart} carrying no
     * fenced name) simply returns empty and is ignored by the gate.
     */
    public Optional<String> owningFeatureId(String reference) {
        if (reference == null || reference.isBlank()) return Optional.empty();
        Set<String> names = candidateNames(reference);
        String lower = reference.toLowerCase(Locale.ROOT);
        for (Feature f : features) {
            BackendPart b = f.backend();
            if (b != null) {
                if (containsAnyIgnoreCase(b.fencedOrEmpty(), names)) return Optional.of(f.id());
                for (String c : b.controllersOrEmpty()) {
                    if (namesContain(names, stripExt(c))) return Optional.of(f.id());
                }
            }
            FrontendPart fe = f.frontend();
            if (fe != null) {
                if (containsAnyIgnoreCase(fe.fencedOrEmpty(), names)) return Optional.of(f.id());
                if (containsAnyIgnoreCase(fe.guardsOrEmpty(), names)) return Optional.of(f.id());
                for (String m : fe.modulesOrEmpty()) {
                    if (m != null && !m.isBlank() && lower.contains(m.toLowerCase(Locale.ROOT))) {
                        return Optional.of(f.id());
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Candidate simple symbol names from an import/dependency string: strips the {@code @/} alias, path,
     *  extension, call parens, and generics, and — for a dotted token — offers both the FQN tail and the
     *  member head, so {@code com.x.PaymentService}, {@code PaymentService.createOrder}, {@code @/hooks/useAuth},
     *  {@code useAuth()} all yield the fenced name. */
    private static Set<String> candidateNames(String reference) {
        String t = reference.trim();
        int paren = t.indexOf('(');
        if (paren >= 0) t = t.substring(0, paren);
        int lt = t.indexOf('<');
        if (lt >= 0) t = t.substring(0, lt);
        int slash = Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\'));
        if (slash >= 0) t = t.substring(slash + 1);
        t = stripExt(t).trim();
        Set<String> out = new LinkedHashSet<>();
        if (!t.isBlank()) out.add(t);
        if (t.contains(".")) {
            out.add(t.substring(t.lastIndexOf('.') + 1)); // FQN tail: com.x.PaymentService → PaymentService
            out.add(t.substring(0, t.indexOf('.')));      // member head: PaymentService.createOrder → PaymentService
        }
        out.remove("");
        return out;
    }

    private static String stripExt(String s) {
        if (s == null) return "";
        for (String ext : new String[]{".tsx", ".ts", ".java"}) {
            if (s.endsWith(ext)) return s.substring(0, s.length() - ext.length());
        }
        return s;
    }

    private static boolean namesContain(Set<String> names, String target) {
        for (String n : names) if (n.equalsIgnoreCase(target)) return true;
        return false;
    }

    private static boolean containsAnyIgnoreCase(List<String> pool, Set<String> names) {
        for (String p : pool) {
            if (p != null && namesContain(names, p)) return true;
        }
        return false;
    }

    // ── Seam projections (kept closure = all features today) ────────────────────

    /** Foundation-spine controller file names whose endpoints must NOT become a generated SDK. */
    public Set<String> foundationControllers() {
        return union(features, f -> f.backend() == null ? List.of() : f.backend().controllersOrEmpty());
    }

    /**
     * Infra services each feature's config declares for docker-compose (⋃ every feature's
     * {@code config.compose}, e.g. gallery → {@code minio}). The compose generator emits a service per
     * entry so a foundation feature that needs a sidecar (object storage, a broker) boots with it —
     * without this, the app's eager connection to that host dies at startup (the MinIO boot-death).
     */
    public Set<String> composeServices() {
        return union(features, f -> f.config() == null || f.config().compose() == null
                ? List.of() : f.config().compose());
    }

    /** Worker-generated-against-the-foundation names the reconciler must NEVER strip. */
    public Set<String> guardNames() {
        return union(features, f -> f.frontend() == null ? List.of() : f.frontend().guardsOrEmpty());
    }

    /** Fenced frontend module path fragments (lower-cased) — a planned file whose path contains one of
     *  these re-declares a foundation-owned module regardless of its base name (e.g. {@code /src/cart/},
     *  {@code /components/gallery/}). ⋃ every feature's {@code frontend.modules}; the reconciler unions
     *  this with its built-in baseline so onboarding a new module path is a manifest edit, not a code edit. */
    public Set<String> fencedFrontendModulePaths() {
        Set<String> out = new LinkedHashSet<>();
        for (Feature f : features) {
            if (f.frontend() == null) continue;
            for (String m : f.frontend().modulesOrEmpty()) {
                if (m != null && !m.isBlank()) out.add(m.toLowerCase(Locale.ROOT));
            }
        }
        return out;
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
