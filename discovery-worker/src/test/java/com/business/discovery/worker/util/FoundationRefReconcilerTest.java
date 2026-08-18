package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FeatureSpec;
import com.business.discovery.worker.service.llm.FileSpec;
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
}
