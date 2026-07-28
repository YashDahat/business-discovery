package com.business.discovery.worker.scaffold;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentScaffoldModuleTest {

    private final PaymentScaffoldModule module = new PaymentScaffoldModule();

    @Test
    void write_emitsAllPaymentFiles_underBasePackage(@TempDir Path tempDir) throws IOException {
        Path javaRoot = tempDir.resolve("backend/src/main/java");
        module.write(javaRoot, "com.circuithouse");

        Path pkg = javaRoot.resolve("com/circuithouse");
        List<String> expected = List.of(
                "model/PaymentStatus.java", "model/Payment.java", "repository/PaymentRepository.java",
                "dto/CreatePaymentRequest.java", "dto/PaymentOrderResponse.java",
                "dto/VerifyPaymentRequest.java", "dto/PaymentVerificationResponse.java",
                "exception/PaymentGatewayException.java", "event/PaymentCapturedEvent.java",
                "gateway/PaymentGateway.java", "gateway/GatewayWebhookEvent.java",
                "gateway/RazorpayPaymentGateway.java", "gateway/DemoPaymentGateway.java",
                "config/PaymentGatewayConfig.java",
                "service/PaymentService.java", "controller/PaymentController.java");
        for (String rel : expected) {
            assertThat(pkg.resolve(rel)).as(rel).exists();
        }
    }

    @Test
    void paymentService_isClosedForModification_dependsOnlyOnTheGatewayAbstraction(@TempDir Path tempDir) throws IOException {
        Path javaRoot = tempDir.resolve("backend/src/main/java");
        module.write(javaRoot, "com.circuithouse");
        Path pkg = javaRoot.resolve("com/circuithouse");

        String paymentService = Files.readString(pkg.resolve("service/PaymentService.java"));
        // OCP: the orchestrator talks only to the PaymentGateway interface — swapping a provider
        // must never require editing it, so it must not know about any concrete provider or SDK.
        assertThat(paymentService)
                .contains("PaymentGateway gateway")
                .doesNotContain("com.razorpay")
                .doesNotContain("RazorpayClient")
                .doesNotContain("RazorpayPaymentGateway")
                .doesNotContain("DemoPaymentGateway");

        // All Razorpay SDK coupling is confined to the one adapter.
        String razorpayGateway = Files.readString(pkg.resolve("gateway/RazorpayPaymentGateway.java"));
        assertThat(razorpayGateway).contains("com.razorpay").contains("implements PaymentGateway");

        // The composition root is the only place that selects an implementation.
        String config = Files.readString(pkg.resolve("config/PaymentGatewayConfig.java"));
        assertThat(config)
                .contains("RazorpayPaymentGateway")
                .contains("DemoPaymentGateway")
                .contains("ConditionalOnMissingBean");
    }

    @Test
    void webhookReconciliation_capturesIdempotently_andKeepsRazorpayJsonInTheAdapter(@TempDir Path tempDir) throws IOException {
        Path javaRoot = tempDir.resolve("backend/src/main/java");
        module.write(javaRoot, "com.circuithouse");
        Path pkg = javaRoot.resolve("com/circuithouse");

        // The webhook path actually reconciles: verify signature, parse, capture, publish — via a shared
        // idempotent helper so a webhook that arrives after the sync verify does not double-publish.
        String paymentService = Files.readString(pkg.resolve("service/PaymentService.java"));
        assertThat(paymentService)
                .contains("parseWebhook")
                .contains("markCaptured")
                .contains("findByGatewayOrderId")
                .contains("PaymentStatus.CAPTURED");

        // The Razorpay webhook JSON shape lives only in the adapter, behind the neutral event.
        String razorpayGateway = Files.readString(pkg.resolve("gateway/RazorpayPaymentGateway.java"));
        assertThat(razorpayGateway).contains("payment.captured").contains("order_id").contains("GatewayWebhookEvent");
        assertThat(Files.readString(pkg.resolve("service/PaymentService.java")))
                .as("PaymentService must not parse gateway webhook JSON itself")
                .doesNotContain("order_id").doesNotContain("payment.captured");
    }

    @Test
    void write_leavesNoUnsubstitutedToken_andHoldsCycleSafetyInvariants(@TempDir Path tempDir) throws IOException {
        Path javaRoot = tempDir.resolve("backend/src/main/java");
        module.write(javaRoot, "com.circuithouse");
        Path pkg = javaRoot.resolve("com/circuithouse");

        try (var walk = Files.walk(pkg)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String src = Files.readString(p);
                assertThat(src).as("no __BP__ token left in " + p).doesNotContain("__BP__");
                assertThat(src).as("package substituted in " + p).contains("package com.circuithouse");
            }
        }

        // PaymentService publishes an event rather than calling back into a consumer.
        assertThat(Files.readString(pkg.resolve("service/PaymentService.java")))
                .as("PaymentService must not import a business service")
                .doesNotContain("import com.circuithouse.service.OrderService")
                .doesNotContain("import com.circuithouse.service.ReservationService");
        assertThat(Files.readString(pkg.resolve("service/PaymentService.java")))
                .contains("ApplicationEventPublisher").contains("PaymentCapturedEvent");

        // The payment entity carries an opaque reference, never a JPA relationship to a business entity.
        assertThat(Files.readString(pkg.resolve("model/Payment.java")))
                .contains("referenceId")
                .doesNotContain("@ManyToOne").doesNotContain("@OneToOne").doesNotContain("@JoinColumn");
    }

    @Test
    void ownedFilePatterns_matchCanonicalRenamesAndFutureProviders_butNotBusinessOrFrontend() {
        List<Pattern> owned = module.ownedFilePatterns();

        for (String name : List.of("Payment.java", "PaymentStatus.java", "PaymentRepository.java",
                "PaymentService.java", "PaymentGatewayService.java", "PaymentController.java",
                "PaymentGateway.java", "RazorpayPaymentGateway.java", "DemoPaymentGateway.java",
                "StripePaymentGateway.java", "GatewayWebhookEvent.java",
                "PaymentGatewayConfig.java", "RazorpayConfig.java",
                "CreatePaymentRequest.java", "PaymentOrderResponse.java", "VerifyPaymentRequest.java",
                "PaymentVerificationResponse.java", "PaymentGatewayException.java",
                "PaymentCapturedEvent.java")) {
            assertThat(matchesAny(owned, name)).as("should own " + name).isTrue();
        }

        for (String name : List.of("MenuItem.java", "OrderService.java", "OrderController.java",
                "Reservation.java", "paymentService.ts", "PaymentCheckout.tsx")) {
            assertThat(matchesAny(owned, name)).as("should NOT own " + name).isFalse();
        }
    }

    private static boolean matchesAny(List<Pattern> owned, String name) {
        return owned.stream().anyMatch(p -> p.matcher(name).find());
    }
}
