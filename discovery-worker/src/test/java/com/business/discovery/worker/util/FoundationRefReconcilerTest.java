package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FeatureSpec;
import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.FoundationFeatureRef;
import com.business.discovery.worker.service.llm.PublicVariable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FoundationRefReconcilerTest {

    @TempDir
    Path ws;
    private FoundationSymbolRegistry registry;

    private static final String BACKEND_CONTRACT = """
            # Backend contract
            ```java
            class User { Integer id; String email; Role role; }
            enum Role { ADMIN, USER }
            class Payment { Long id; String referenceId; }
            ```
            """;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(ws.resolve("backend"));
        Files.writeString(ws.resolve("backend/FOUNDATION_CONTRACT.md"), BACKEND_CONTRACT);
        registry = FoundationSymbolRegistry.buildFromWorkspace(ws);
    }

    // ── fixture builders ──

    private static PublicVariable pv(String name, String type) {
        return PublicVariable.builder().name(name).type(type).build();
    }

    private static FileSpec file(String name, String path, String type,
                                 List<PublicVariable> vars, List<String> imports, String feature) {
        return FileSpec.builder()
                .fileName(name).filePath(path).fileType(type).layer("MODEL").status("PLANNED")
                .publicVariables(vars == null ? null : new ArrayList<>(vars))
                .importsFrom(imports == null ? null : new ArrayList<>(imports))
                .featureName(feature)
                .build();
    }

    private static final String ORDER   = "backend/src/main/java/com/absfitness/model/Order.java";
    private static final String USER    = "backend/src/main/java/com/absfitness/model/User.java";
    private static final String ROLE    = "backend/src/main/java/com/absfitness/model/Role.java";
    private static final String SECCFG  = "backend/src/main/java/com/absfitness/config/SecurityConfig.java";
    private static final String AUTHCTX = "frontend/src/context/AuthContext.tsx";
    private static final String GUARD_PR = "frontend/src/components/ProtectedRoute.tsx";
    private static final String GUARD_SC = "frontend/src/config/siteConfig.ts";

    /** abs-fitness-shaped spec: a domain Order + the re-declared fenced spine + guard files. */
    private ArchitectureSpec absFitnessLikeSpec() {
        FileSpec order = file("Order.java", ORDER, "BACKEND",
                List.of(pv("id", "UUID"),
                        pv("userId", "UUID"),
                        pv("membershipPlan", "MembershipPlan"),
                        pv("amount", "BigDecimal")),
                List.of("backend/src/main/java/com/absfitness/model/OrderStatus.java", USER),
                "orders");
        FileSpec user = file("User.java", USER, "BACKEND", List.of(pv("id", "UUID")), null, "orders");
        FileSpec role = file("Role.java", ROLE, "BACKEND", null, null, "orders");
        FileSpec sec  = file("SecurityConfig.java", SECCFG, "BACKEND", null, null, "orders");
        FileSpec auth = file("AuthContext.tsx", AUTHCTX, "FRONTEND",
                List.of(pv("AuthContext", "React.Context<AuthContextType>")),
                List.of("frontend/src/types/auth.ts"), "shell");
        FileSpec pr   = file("ProtectedRoute.tsx", GUARD_PR, "FRONTEND", null, null, "shell");
        FileSpec sc   = file("siteConfig.ts", GUARD_SC, "FRONTEND", null, null, "shell");

        FeatureSpec orders = FeatureSpec.builder().featureName("orders")
                .filePaths(new ArrayList<>(List.of(ORDER, USER, ROLE, SECCFG))).build();
        FeatureSpec shell = FeatureSpec.builder().featureName("shell")
                .filePaths(new ArrayList<>(List.of(AUTHCTX, GUARD_PR, GUARD_SC))).build();

        return ArchitectureSpec.builder()
                .files(new ArrayList<>(List.of(order, user, role, sec, auth, pr, sc)))
                .features(new ArrayList<>(List.of(orders, shell)))
                .build();
    }

    private static List<String> names(ArchitectureSpec spec) {
        return spec.getFiles().stream().map(FileSpec::getFileName).toList();
    }

    private static Optional<PublicVariable> field(FileSpec f, String name) {
        return f.getPublicVariables().stream().filter(v -> v.getName().equals(name)).findFirst();
    }

    private static FileSpec byName(ArchitectureSpec spec, String fileName) {
        return spec.getFiles().stream().filter(f -> f.getFileName().equals(fileName)).findFirst().orElseThrow();
    }

    // ── tests ──

    @Test
    void stripsFencedRedeclarationsKeepsGeneratedAndDomain() {
        ArchitectureSpec spec = absFitnessLikeSpec();
        FoundationRefReconciler.reconcile(spec, registry);
        assertThat(names(spec)).contains("Order.java", "ProtectedRoute.tsx", "siteConfig.ts");
        assertThat(names(spec)).doesNotContain("User.java", "Role.java", "SecurityConfig.java", "AuthContext.tsx");
    }

    @Test
    void keepsAppCartUiComponents_stripsOnlyFoundationCartSpine() {
        // Foundation cart SPINE (frontend/src/cart/** — context + hooks) is fenced → stripped.
        // App cart UI (frontend/src/components/cart/**) is NOT fenced → must survive. The old bare
        // "/cart/" fragment matched BOTH, wrongly stripping CartItemsTable/CartSummary → CartPage then
        // imported files that never generated (TS2307, prakash Theme G). Fence is now "/src/cart/".
        FileSpec spine = file("useCartStore.ts", "frontend/src/cart/useCartStore.ts", "FRONTEND", null, null, "cart");
        FileSpec ui    = file("CartItemsTable.tsx", "frontend/src/components/cart/CartItemsTable.tsx", "FRONTEND", null, null, "cart");
        ArchitectureSpec spec = ArchitectureSpec.builder()
                .files(new ArrayList<>(List.of(spine, ui)))
                .features(new ArrayList<>(List.of(
                        FeatureSpec.builder().featureName("cart")
                                .filePaths(new ArrayList<>(List.of(
                                        "frontend/src/cart/useCartStore.ts",
                                        "frontend/src/components/cart/CartItemsTable.tsx"))).build())))
                .build();

        FoundationRefReconciler.reconcile(spec, registry);

        assertThat(names(spec)).contains("CartItemsTable.tsx");     // app cart UI survives
        assertThat(names(spec)).doesNotContain("useCartStore.ts");  // foundation cart spine stripped
    }

    @Test
    void rewritesUserRefToIntegerUserIdLeavingPkAndDomainFields() {
        ArchitectureSpec spec = absFitnessLikeSpec();
        FoundationRefReconciler.reconcile(spec, registry);
        FileSpec order = byName(spec, "Order.java");
        // userId (was UUID) is realigned to the Integer handle — same name, corrected type
        PublicVariable handle = field(order, "userId").orElseThrow();
        assertThat(handle.getType()).isEqualTo("Integer");
        // the entity's own PK is normalized UUID -> Long (no UUID ids); non-id domain fields untouched
        assertThat(field(order, "id").orElseThrow().getType()).isEqualTo("Long");
        assertThat(field(order, "membershipPlan").orElseThrow().getType()).isEqualTo("MembershipPlan");
        assertThat(field(order, "amount").orElseThrow().getType()).isEqualTo("BigDecimal");
    }

    @Test
    void keepsFeatureFilePathsInSync() {
        ArchitectureSpec spec = absFitnessLikeSpec();
        FoundationRefReconciler.reconcile(spec, registry);
        FeatureSpec orders = spec.getFeatures().stream()
                .filter(x -> x.getFeatureName().equals("orders")).findFirst().orElseThrow();
        assertThat(orders.getFilePaths()).containsExactly(ORDER);   // USER/ROLE/SECCFG dropped
    }

    @Test
    void dropsDanglingFoundationImports() {
        ArchitectureSpec spec = absFitnessLikeSpec();
        FoundationRefReconciler.reconcile(spec, registry);
        FileSpec order = byName(spec, "Order.java");
        assertThat(order.getImportsFrom())
                .containsExactly("backend/src/main/java/com/absfitness/model/OrderStatus.java");
    }

    @Test
    void rewritesFullyQualifiedAndTypeUserAndPaymentFk() {
        FileSpec booking = file("Booking.java",
                "backend/src/main/java/com/absfitness/model/Booking.java", "BACKEND",
                List.of(pv("id", "java.util.UUID"),
                        pv("userId", "java.util.UUID"),
                        pv("owner", "User"),
                        pv("payment", "Payment")),
                null, "bookings");
        ArchitectureSpec spec = ArchitectureSpec.builder()
                .files(new ArrayList<>(List.of(booking))).features(new ArrayList<>()).build();

        FoundationRefReconciler.reconcile(spec, registry);
        FileSpec b = byName(spec, "Booking.java");
        assertThat(field(b, "id").orElseThrow().getType()).isEqualTo("Long"); // PK normalized UUID -> Long
        // userId and owner:User both collapse onto the single Integer userId handle
        assertThat(field(b, "userId").orElseThrow().getType()).isEqualTo("Integer");
        assertThat(b.getPublicVariables().stream().filter(v -> v.getName().equals("userId")).count())
                .isEqualTo(1);
        assertThat(field(b, "referenceId").orElseThrow().getType()).isEqualTo("String");
    }

    @Test
    void normalizesUuidIdFieldsToLong() {
        FileSpec entity = file("Booking.java",
                "backend/src/main/java/com/absfitness/model/Booking.java", "BACKEND",
                List.of(pv("id", "UUID"),          // PK -> Long
                        pv("planId", "UUID"),      // domain FK -> Long
                        pv("referenceId", "UUID"), // payment handle -> left alone (String link, not a key)
                        pv("notes", "String")),    // non-id -> untouched
                null, "bookings");
        ArchitectureSpec spec = ArchitectureSpec.builder()
                .files(new ArrayList<>(List.of(entity))).features(new ArrayList<>()).build();

        FoundationRefReconciler.reconcile(spec, registry);
        FileSpec b = byName(spec, "Booking.java");
        assertThat(field(b, "id").orElseThrow().getType()).isEqualTo("Long");
        assertThat(field(b, "planId").orElseThrow().getType()).isEqualTo("Long");
        assertThat(field(b, "referenceId").orElseThrow().getType()).isEqualTo("UUID"); // not a numeric key
        assertThat(field(b, "notes").orElseThrow().getType()).isEqualTo("String");
    }

    @Test
    void normalizesFrontendUuidIdToNumber() {
        FileSpec dto = file("booking.ts", "frontend/src/types/booking.ts", "FRONTEND",
                List.of(pv("id", "UUID"), pv("planId", "UUID")), null, "bookings");
        ArchitectureSpec spec = ArchitectureSpec.builder()
                .files(new ArrayList<>(List.of(dto))).features(new ArrayList<>()).build();

        FoundationRefReconciler.reconcile(spec, registry);
        FileSpec d = byName(spec, "booking.ts");
        assertThat(field(d, "id").orElseThrow().getType()).isEqualTo("number");
        assertThat(field(d, "planId").orElseThrow().getType()).isEqualTo("number");
    }

    @Test
    void frontendUserFieldBecomesNumberHandle() {
        FileSpec dto = file("order.ts", "frontend/src/types/order.ts", "FRONTEND",
                List.of(pv("userId", "string"), pv("total", "number")), null, "orders");
        ArchitectureSpec spec = ArchitectureSpec.builder()
                .files(new ArrayList<>(List.of(dto))).features(new ArrayList<>()).build();

        FoundationRefReconciler.reconcile(spec, registry);
        FileSpec d = byName(spec, "order.ts");
        // Integer userId mirrors to `number` on the frontend
        assertThat(field(d, "userId").orElseThrow().getType()).isEqualTo("number");
        assertThat(field(d, "total").orElseThrow().getType()).isEqualTo("number");
    }

    @Test
    void isIdempotent() {
        ArchitectureSpec spec = absFitnessLikeSpec();
        FoundationRefReconciler.reconcile(spec, registry);
        FoundationRefReconciler.Result second = FoundationRefReconciler.reconcile(spec, registry);
        assertThat(second.changedAnything()).isFalse();
    }

    @Test
    void stripsFoundationOwnedGalleryModule_viaManifestPathFence() {
        // gallery.frontend.modules = ["/components/gallery/", "/hooks/usemedia"] in the manifest, but
        // these were NOT in the hardcoded FENCED_FRONTEND_PATHS → a re-declared gallery module used to
        // slip through unstripped. isFencedFrontendPath now unions the run-active manifest's module paths.
        FileSpec galleryGrid = file("GalleryGrid.tsx", "frontend/src/components/gallery/GalleryGrid.tsx",
                "FRONTEND", null, null, "gallery");            // caught only by PATH (base name not fenced)
        FileSpec mediaHook = file("useMedia.ts", "frontend/src/hooks/useMedia.ts",
                "FRONTEND", null, null, "gallery");            // /hooks/usemedia
        FileSpec domainPage = file("HomePage.tsx", "frontend/src/pages/HomePage.tsx",
                "FRONTEND", null, null, "home");               // domain — must survive

        ArchitectureSpec spec = ArchitectureSpec.builder()
                .files(new ArrayList<>(List.of(galleryGrid, mediaHook, domainPage)))
                .features(new ArrayList<>(List.of(
                        FeatureSpec.builder().featureName("gallery")
                                .filePaths(new ArrayList<>(List.of(
                                        "frontend/src/components/gallery/GalleryGrid.tsx",
                                        "frontend/src/hooks/useMedia.ts"))).build())))
                .build();

        FoundationRefReconciler.reconcile(spec, registry);

        assertThat(names(spec)).doesNotContain("GalleryGrid.tsx", "useMedia.ts"); // foundation-owned → stripped
        assertThat(names(spec)).containsExactly("HomePage.tsx");                  // domain survives
    }

    @Test
    void baselinePathFence_stillHolds_forApiClient() {
        // /api/client. is in the built-in baseline but NOT in any manifest module — the union must not
        // regress it (guards against a naive "replace baseline with manifest" change).
        FileSpec apiClient = file("client.ts", "frontend/src/api/client.ts", "FRONTEND", null, null, "core");
        ArchitectureSpec spec = ArchitectureSpec.builder()
                .files(new ArrayList<>(List.of(apiClient)))
                .build();

        FoundationRefReconciler.reconcile(spec, registry);

        assertThat(names(spec)).doesNotContain("client.ts");
    }

    // ── cross-check (§6b dangling-reference gate) ──
    // Attribution uses the run-active FoundationManifest (default here): payment.backend.fenced has
    // "PaymentService", auth.frontend.fenced has "useAuth". No activation → the built-in default.

    private static ArchitectureSpec specWith(FileSpec file, String... declaredFeatureIds) {
        List<FoundationFeatureRef> declared = new ArrayList<>();
        for (String id : declaredFeatureIds) declared.add(FoundationFeatureRef.builder().id(id).build());
        return ArchitectureSpec.builder()
                .files(new ArrayList<>(List.of(file)))
                .foundationFeatures(declared)   // non-null (possibly empty) → cross-check runs
                .build();
    }

    @Test
    void crossCheck_flagsNonCoreDanglingReference() {
        // Imports PaymentService (→ payment) but only auth is declared → payment is a dangling reference.
        FileSpec order = file("OrderService.java", ORDER, "BACKEND", null,
                List.of("com.absfitness.service.PaymentService"), "orders");
        FoundationRefReconciler.CrossCheckResult r =
                FoundationRefReconciler.crossCheckFoundationRefs(specWith(order, "auth"));

        assertThat(r.isClean()).isFalse();
        assertThat(r.danglingRefs()).singleElement()
                .satisfies(v -> {
                    assertThat(v.featureId()).isEqualTo("payment");
                    assertThat(v.filePath()).isEqualTo(ORDER);
                });
    }

    @Test
    void crossCheck_cleanWhenReferencedFeatureDeclared() {
        FileSpec order = file("OrderService.java", ORDER, "BACKEND", null,
                List.of("com.absfitness.service.PaymentService"), "orders");
        FoundationRefReconciler.CrossCheckResult r =
                FoundationRefReconciler.crossCheckFoundationRefs(specWith(order, "auth", "payment"));

        assertThat(r.isClean()).isTrue();
    }

    @Test
    void crossCheck_coreReferenceNeverFlagged() {
        // useAuth → auth (core). Even with nothing declared, a core reference is not a dangling defect.
        FileSpec page = file("ProfilePage.tsx", "frontend/src/pages/ProfilePage.tsx", "FRONTEND", null,
                List.of("@/hooks/useAuth"), "profile");
        FoundationRefReconciler.CrossCheckResult r =
                FoundationRefReconciler.crossCheckFoundationRefs(specWith(page /* nothing declared */));

        assertThat(r.isClean()).isTrue();
    }

    @Test
    void crossCheck_ignoresNonFoundationImports() {
        FileSpec order = file("OrderService.java", ORDER, "BACKEND", null,
                List.of("com.absfitness.service.MembershipService"), "orders");
        FoundationRefReconciler.CrossCheckResult r =
                FoundationRefReconciler.crossCheckFoundationRefs(specWith(order, "auth"));

        assertThat(r.isClean()).isTrue();
    }

    @Test
    void crossCheck_nullFoundationFeatures_skipsGracefully() {
        FileSpec order = file("OrderService.java", ORDER, "BACKEND", null,
                List.of("com.absfitness.service.PaymentService"), "orders");
        ArchitectureSpec spec = ArchitectureSpec.builder()
                .files(new ArrayList<>(List.of(order)))
                .build();   // foundationFeatures == null (pre-§6b spec)

        FoundationRefReconciler.CrossCheckResult r =
                FoundationRefReconciler.crossCheckFoundationRefs(spec);

        assertThat(r.isClean()).isTrue();
        assertThat(r.declaredUnreferenced()).isEmpty();
    }

    @Test
    void crossCheck_reportsNonCoreDeclaredButUnreferenced() {
        // gallery declared, referenced by nothing → advisory over-declaration (auth core is excluded).
        FileSpec order = file("OrderService.java", ORDER, "BACKEND", null,
                List.of("com.absfitness.service.PaymentService"), "orders");
        FoundationRefReconciler.CrossCheckResult r =
                FoundationRefReconciler.crossCheckFoundationRefs(specWith(order, "auth", "payment", "gallery"));

        assertThat(r.isClean()).isTrue();                        // payment is declared → no dangling
        assertThat(r.declaredUnreferenced()).containsExactly("gallery");
    }
}
