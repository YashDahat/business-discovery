package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §5 regression guard: the manifest projections MUST reproduce, exactly, the hardcoded sets they
 * replaced in {@link ApiInventory}, {@link FoundationRefReconciler}, and {@link RouteManifest}.
 * Pruning is deferred (kept closure = all features), so any drift here is a real behavior change.
 */
class FoundationManifestTest {

    private final FoundationManifest manifest = FoundationManifest.defaultManifest();

    @Test
    void foundationControllers_matchOldApiInventorySet() {
        assertThat(manifest.foundationControllers()).containsExactlyInAnyOrder(
                "AuthController.java",
                "PaymentController.java",
                "SpaController.java",
                "GalleryController.java",
                "MediaController.java",
                "AdminMediaController.java");
    }

    @Test
    void guardNames_matchOldReconcilerSet() {
        assertThat(manifest.guardNames())
                .containsExactlyInAnyOrder("ProtectedRoute", "AdminLayout", "siteConfig");
    }

    @Test
    void fencedBackendNames_matchOldReconcilerSet() {
        assertThat(manifest.fencedBackendNames()).containsExactlyInAnyOrder(
                "User", "Role", "UserRepository", "UserService", "UserDetailsServiceImpl",
                "JwtUtil", "JwtService", "JwtTokenProvider", "JwtAuthFilter",
                "SecurityConfig", "SecurityConfiguration", "PasswordEncoderConfig", "PasswordEncoder",
                "AuthController", "AdminInitializer", "SpaController",
                "AuthRequest", "AuthResponse", "RegisterRequest",
                "Payment", "PaymentStatus", "PaymentRepository", "PaymentService",
                "PaymentController", "PaymentGateway", "RazorpayPaymentGateway", "DemoPaymentGateway",
                "PaymentGatewayConfig", "RazorpayConfiguration", "GatewayWebhookEvent", "PaymentCapturedEvent",
                "PaymentGatewayException", "ResourceNotFoundException",
                "CreatePaymentRequest", "PaymentOrderResponse", "VerifyPaymentRequest", "PaymentVerificationResponse");
    }

    @Test
    void fencedFrontendNames_matchOldReconcilerSet() {
        assertThat(manifest.fencedFrontendNames()).containsExactlyInAnyOrder(
                "AuthContext", "useAuth", "authService",
                "Header", "Footer", "Layout", "SiteHeader", "SiteFooter", "SiteLayout",
                "CartContext", "CheckoutContext", "useCheckout");
    }

    @Test
    void routeGateKeys_matchOldRouteManifestSets() {
        assertThat(manifest.authPageKeys()).containsExactlyInAnyOrder("CHECKOUT", "ACCOUNT");
        assertThat(manifest.nonNavPageKeys()).containsExactlyInAnyOrder("CHECKOUT", "CART");
    }

    @Test
    void fencedFrontendModulePaths_unionOfAllFeatureModules() {
        // The path fence the reconciler unions with its built-in baseline. Includes gallery's paths,
        // which were absent from the old hardcoded FENCED_FRONTEND_PATHS (the closed OCP hole).
        assertThat(manifest.fencedFrontendModulePaths()).containsExactlyInAnyOrder(
                "/context/authcontext", "/hooks/useauth", "/services/authservice", "/types/auth.",
                "/shell/",
                "/src/cart/", "/context/cartcontext", "/context/checkoutcontext",
                "/components/gallery/", "/hooks/usemedia");
    }

    @Test
    void load_absentManifest_fallsBackToDefault() {
        FoundationManifest loaded = FoundationManifest.load(Path.of("/tmp/does-not-exist-" + System.nanoTime()));
        assertThat(loaded.features()).isEqualTo(manifest.features());
    }

    @Test
    void load_malformedManifest_fallsBackToDefault(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve(FoundationManifest.MANIFEST_REL), "{ not valid json ");
        FoundationManifest loaded = FoundationManifest.load(workspace);
        assertThat(loaded.features()).isEqualTo(manifest.features());
    }

    @Test
    void load_validManifest_overridesDefault(@TempDir Path workspace) throws Exception {
        String json = """
                {
                  "features": [
                    {
                      "id": "auth",
                      "core": true,
                      "backend":  { "controllers": ["AuthController.java"], "fenced": ["User"] },
                      "frontend": { "guards": ["ProtectedRoute"], "fenced": ["useAuth"],
                                    "pages": [{ "key": "ACCOUNT", "gate": "AUTH", "nav": true }] }
                    },
                    {
                      "id": "reviews",
                      "core": false,
                      "backend":  { "controllers": ["ReviewController.java"], "fenced": ["Review"] },
                      "frontend": { "guards": [], "fenced": [],
                                    "pages": [{ "key": "MYREVIEWS", "gate": "AUTH", "nav": false }] }
                    }
                  ]
                }
                """;
        Files.writeString(workspace.resolve(FoundationManifest.MANIFEST_REL), json);

        FoundationManifest loaded = FoundationManifest.load(workspace);

        // A newly-onboarded foundation controller flows straight into the skip-SDK projection —
        // the OCP payoff: one manifest edit, every seam updates.
        assertThat(loaded.foundationControllers())
                .containsExactlyInAnyOrder("AuthController.java", "ReviewController.java");
        assertThat(loaded.fencedBackendNames()).containsExactlyInAnyOrder("User", "Review");
        assertThat(loaded.authPageKeys()).containsExactlyInAnyOrder("ACCOUNT", "MYREVIEWS");
        assertThat(loaded.nonNavPageKeys()).containsExactly("MYREVIEWS");
    }
}
