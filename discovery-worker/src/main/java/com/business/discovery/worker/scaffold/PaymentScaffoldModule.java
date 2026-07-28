package com.business.discovery.worker.scaffold;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Scaffolds the universal payment spine — a self-contained payment gateway that any business
 * service can inject directly. It is written to disk deterministically instead of being generated
 * as a feature, which structurally eliminates a whole class of failure: the LLM used to plan a
 * {@code payment-processing} feature that other features (order-management) depend on, then wired
 * it BACK into order-management to mark orders paid — a feature dependency cycle that the cycle
 * gate rejects and, if it escaped, a Spring bean cycle that crashes the app at boot.
 *
 * <p>The design that breaks the cycle: {@code PaymentService} depends only on its own repository,
 * a {@code PaymentGateway} abstraction, and an {@code ApplicationEventPublisher} — NEVER on a
 * business service. A consumer (order-management) injects {@code PaymentService}, calls
 * {@code createOrder}/{@code verify}, and updates its OWN entity with the returned result — payment
 * never calls back. Async webhooks reach consumers via {@code PaymentCapturedEvent} (an
 * {@code @EventListener}), which is still a payment→consumer edge, never the reverse.
 *
 * <p>The gateway follows the Open/Closed Principle: {@code PaymentService} talks only to the
 * {@code PaymentGateway} interface. Adding or swapping a provider (Stripe, Cashfree,
 * a UPI PSP) is a NEW {@code PaymentGateway} implementation plus one conditional bean — zero edits
 * to {@code PaymentService}, the controller, or any consumer. {@code DemoPaymentGateway} is the
 * no-credentials fallback, so the generated app boots and the checkout flow works end to end
 * without real keys.
 *
 * <p>Templates are business-agnostic; the base package is injected by replacing the {@code __BP__}
 * token.
 */
@Component
@Order(20)
@Slf4j
public class PaymentScaffoldModule implements ScaffoldModule {

    private static final String TOKEN = "__BP__";

    @Override
    public String name() {
        return "payment";
    }

    @Override
    public void write(Path backendJavaRoot, String basePackage) throws IOException {
        Path root = backendJavaRoot.resolve(basePackage.replace('.', '/'));

        writeFile(root.resolve("model/PaymentStatus.java"),                render(PAYMENT_STATUS, basePackage));
        writeFile(root.resolve("model/Payment.java"),                     render(PAYMENT, basePackage));
        writeFile(root.resolve("repository/PaymentRepository.java"),      render(PAYMENT_REPOSITORY, basePackage));
        writeFile(root.resolve("dto/CreatePaymentRequest.java"),          render(CREATE_PAYMENT_REQUEST, basePackage));
        writeFile(root.resolve("dto/PaymentOrderResponse.java"),          render(PAYMENT_ORDER_RESPONSE, basePackage));
        writeFile(root.resolve("dto/VerifyPaymentRequest.java"),          render(VERIFY_PAYMENT_REQUEST, basePackage));
        writeFile(root.resolve("dto/PaymentVerificationResponse.java"),   render(PAYMENT_VERIFICATION_RESPONSE, basePackage));
        writeFile(root.resolve("exception/PaymentGatewayException.java"), render(PAYMENT_GATEWAY_EXCEPTION, basePackage));
        writeFile(root.resolve("event/PaymentCapturedEvent.java"),        render(PAYMENT_CAPTURED_EVENT, basePackage));
        writeFile(root.resolve("gateway/PaymentGateway.java"),            render(PAYMENT_GATEWAY, basePackage));
        writeFile(root.resolve("gateway/GatewayWebhookEvent.java"),       render(GATEWAY_WEBHOOK_EVENT, basePackage));
        writeFile(root.resolve("gateway/RazorpayPaymentGateway.java"),    render(RAZORPAY_PAYMENT_GATEWAY, basePackage));
        writeFile(root.resolve("gateway/DemoPaymentGateway.java"),        render(DEMO_PAYMENT_GATEWAY, basePackage));
        writeFile(root.resolve("config/PaymentGatewayConfig.java"),       render(PAYMENT_GATEWAY_CONFIG, basePackage));
        writeFile(root.resolve("service/PaymentService.java"),            render(PAYMENT_SERVICE, basePackage));
        writeFile(root.resolve("controller/PaymentController.java"),      render(PAYMENT_CONTROLLER, basePackage));

        log.info("[PaymentScaffoldModule] Wrote 16 payment-spine files under {}", basePackage);
    }

    @Override
    public List<Pattern> ownedFilePatterns() {
        // Anchored to .java so the frontend payment-checkout UI (paymentService.ts, etc.) is untouched.
        // Broad on the gateway names to catch LLM renames and future providers.
        return List.of(
                Pattern.compile("^PaymentStatus\\.java$"),
                Pattern.compile("^Payment\\.java$"),
                Pattern.compile("^PaymentRepository\\.java$"),
                Pattern.compile("^Payment(Gateway)?Service\\.java$"),  // PaymentService + PaymentGatewayService rename
                Pattern.compile("^PaymentGateway\\.java$"),
                Pattern.compile("^[A-Za-z]+PaymentGateway\\.java$"),   // RazorpayPaymentGateway, DemoPaymentGateway, StripePaymentGateway, ...
                Pattern.compile("^GatewayWebhookEvent\\.java$"),
                Pattern.compile("^PaymentGatewayConfig\\.java$"),
                Pattern.compile("^PaymentController\\.java$"),
                Pattern.compile("^(CreatePaymentRequest|PaymentOrderResponse|VerifyPaymentRequest|PaymentVerificationResponse)\\.java$"),
                Pattern.compile("^PaymentGatewayException\\.java$"),
                Pattern.compile("^PaymentCapturedEvent\\.java$"),
                Pattern.compile("^Razorpay(Config|Configuration)\\.java$")   // defensive: legacy config name
        );
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String render(String template, String basePackage) {
        return template.replace(TOKEN, basePackage);
    }

    private static void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    // ── templates (base package injected via __BP__) ─────────────────────────

    private static final String PAYMENT_STATUS = """
            package __BP__.model;

            public enum PaymentStatus {
                CREATED,
                CAPTURED,
                FAILED
            }
            """;

    private static final String PAYMENT = """
            package __BP__.model;

            import jakarta.persistence.*;
            import lombok.AllArgsConstructor;
            import lombok.Builder;
            import lombok.Data;
            import lombok.NoArgsConstructor;

            import java.math.BigDecimal;
            import java.time.Instant;

            /**
             * A payment record owned entirely by the payment spine. {@code referenceId} is an opaque
             * link to whatever domain entity triggered the payment (an order id, a booking id, ...)
             * stored as a plain string — the payment layer intentionally has NO foreign key to any
             * business entity, which is what keeps it dependency-free and cycle-proof. The gateway
             * ids are provider-neutral: they hold whatever the active PaymentGateway returned.
             */
            @Data
            @Builder
            @NoArgsConstructor
            @AllArgsConstructor
            @Entity
            @Table(name = "payment")
            public class Payment {

                @Id
                @GeneratedValue(strategy = GenerationType.IDENTITY)
                private Long id;

                /** Opaque reference to the domain entity being paid for (e.g. "order_42"). */
                private String referenceId;

                private String gatewayOrderId;
                private String gatewayPaymentId;

                private BigDecimal amount;
                private String currency;

                @Enumerated(EnumType.STRING)
                private PaymentStatus status;

                private Instant createdAt;
            }
            """;

    private static final String PAYMENT_REPOSITORY = """
            package __BP__.repository;

            import __BP__.model.Payment;
            import org.springframework.data.jpa.repository.JpaRepository;
            import org.springframework.stereotype.Repository;

            import java.util.Optional;

            @Repository
            public interface PaymentRepository extends JpaRepository<Payment, Long> {
                Optional<Payment> findByGatewayOrderId(String gatewayOrderId);
                Optional<Payment> findByReferenceId(String referenceId);
            }
            """;

    private static final String CREATE_PAYMENT_REQUEST = """
            package __BP__.dto;

            import lombok.AllArgsConstructor;
            import lombok.Builder;
            import lombok.Data;
            import lombok.NoArgsConstructor;

            import java.math.BigDecimal;

            @Data
            @Builder
            @NoArgsConstructor
            @AllArgsConstructor
            public class CreatePaymentRequest {
                /** Amount in the major currency unit (e.g. rupees), not paise. */
                private BigDecimal amount;
                /** ISO currency code; defaults to INR when null. */
                private String currency;
                /** Opaque reference to the domain entity being paid for (e.g. "order_42"). */
                private String referenceId;
            }
            """;

    private static final String PAYMENT_ORDER_RESPONSE = """
            package __BP__.dto;

            import lombok.AllArgsConstructor;
            import lombok.Builder;
            import lombok.Data;
            import lombok.NoArgsConstructor;

            /**
             * Provider-neutral order response. For a Razorpay checkout widget the frontend maps
             * gatewayOrderId -> order_id and gatewayKeyId -> key.
             */
            @Data
            @Builder
            @NoArgsConstructor
            @AllArgsConstructor
            public class PaymentOrderResponse {
                /** Gateway order id to hand to the checkout widget. */
                private String gatewayOrderId;
                /** Gateway public key id the frontend needs to open checkout. */
                private String gatewayKeyId;
                /** Amount in the minor unit (paise) — what the checkout widget expects. */
                private int amount;
                private String currency;
                /** The local Payment row id, for correlating the later verify call. */
                private Long paymentRecordId;
            }
            """;

    private static final String VERIFY_PAYMENT_REQUEST = """
            package __BP__.dto;

            import lombok.AllArgsConstructor;
            import lombok.Builder;
            import lombok.Data;
            import lombok.NoArgsConstructor;

            /**
             * Provider-neutral verification request. For a Razorpay checkout the frontend maps
             * razorpay_order_id -> gatewayOrderId, razorpay_payment_id -> gatewayPaymentId,
             * razorpay_signature -> signature.
             */
            @Data
            @Builder
            @NoArgsConstructor
            @AllArgsConstructor
            public class VerifyPaymentRequest {
                private String gatewayOrderId;
                private String gatewayPaymentId;
                private String signature;
            }
            """;

    private static final String PAYMENT_VERIFICATION_RESPONSE = """
            package __BP__.dto;

            import lombok.AllArgsConstructor;
            import lombok.Builder;
            import lombok.Data;
            import lombok.NoArgsConstructor;

            @Data
            @Builder
            @NoArgsConstructor
            @AllArgsConstructor
            public class PaymentVerificationResponse {
                private boolean verified;
                private String status;
                private String referenceId;
            }
            """;

    private static final String PAYMENT_GATEWAY_EXCEPTION = """
            package __BP__.exception;

            /**
             * Thrown when the payment gateway cannot fulfil a request (order creation failed, signature
             * verification errored, ...). Unchecked so callers are not forced to wrap every gateway call.
             */
            public class PaymentGatewayException extends RuntimeException {
                public PaymentGatewayException(String message) {
                    super(message);
                }

                public PaymentGatewayException(String message, Throwable cause) {
                    super(message, cause);
                }
            }
            """;

    private static final String PAYMENT_CAPTURED_EVENT = """
            package __BP__.event;

            import lombok.Getter;
            import org.springframework.context.ApplicationEvent;

            import java.math.BigDecimal;

            /**
             * Published by PaymentService when a payment is verified/captured. A business service that
             * needs to react asynchronously (e.g. mark an order paid on a webhook) listens with an
             * {@code @EventListener} — this keeps the dependency direction payment → consumer and never
             * the reverse, so no bean cycle can form.
             */
            @Getter
            public class PaymentCapturedEvent extends ApplicationEvent {

                private final String referenceId;
                private final String gatewayPaymentId;
                private final BigDecimal amount;

                public PaymentCapturedEvent(Object source, String referenceId, String gatewayPaymentId, BigDecimal amount) {
                    super(source);
                    this.referenceId = referenceId;
                    this.gatewayPaymentId = gatewayPaymentId;
                    this.amount = amount;
                }
            }
            """;

    private static final String PAYMENT_GATEWAY = """
            package __BP__.gateway;

            import java.util.Optional;

            /**
             * The payment provider abstraction. PaymentService depends ONLY on this interface, so a new
             * provider (Stripe, Cashfree, a UPI PSP) is a new implementation plus one conditional bean in
             * PaymentGatewayConfig — never an edit to PaymentService, the controller, or any consumer.
             */
            public interface PaymentGateway {

                /** Creates a gateway order for the given amount in minor units (e.g. paise); returns its id. */
                String createOrder(int amountMinor, String currency, String receipt);

                /** The public key the frontend checkout widget needs (may be a placeholder for gateways that need none). */
                String publicKey();

                /** Verifies the client-side payment signature returned by the checkout widget. */
                boolean verifySignature(String orderId, String paymentId, String signature);

                /** Verifies a server-to-server webhook signature. Returns true when there is nothing to verify against. */
                boolean verifyWebhook(String payload, String signature);

                /**
                 * Parses a (signature-verified) webhook body into a provider-neutral event. Returns empty
                 * when the payload is not a capture this gateway handles — all provider-specific JSON shape
                 * lives in the adapter, never in PaymentService.
                 */
                Optional<GatewayWebhookEvent> parseWebhook(String payload);
            }
            """;

    private static final String GATEWAY_WEBHOOK_EVENT = """
            package __BP__.gateway;

            /**
             * Provider-neutral view of a payment webhook, produced by a {@link PaymentGateway} adapter and
             * consumed by PaymentService to reconcile a Payment. Keeps PaymentService free of any gateway's
             * webhook JSON shape.
             */
            public record GatewayWebhookEvent(String gatewayOrderId, String gatewayPaymentId, boolean captured) {
            }
            """;

    private static final String RAZORPAY_PAYMENT_GATEWAY = """
            package __BP__.gateway;

            import com.razorpay.Order;
            import com.razorpay.RazorpayClient;
            import com.razorpay.RazorpayException;
            import com.razorpay.Utils;
            import __BP__.exception.PaymentGatewayException;
            import org.json.JSONObject;

            import java.util.Optional;

            /**
             * Razorpay implementation of {@link PaymentGateway}. All Razorpay SDK coupling — and the
             * Razorpay webhook JSON shape — lives here and nowhere else; this is the only class that
             * imports com.razorpay.
             */
            public class RazorpayPaymentGateway implements PaymentGateway {

                private final RazorpayClient client;
                private final String keyId;
                private final String keySecret;
                private final String webhookSecret;

                public RazorpayPaymentGateway(String keyId, String keySecret, String webhookSecret) {
                    this.keyId = keyId;
                    this.keySecret = keySecret;
                    this.webhookSecret = webhookSecret;
                    try {
                        this.client = new RazorpayClient(keyId, keySecret);
                    } catch (RazorpayException e) {
                        throw new PaymentGatewayException("Failed to initialize Razorpay client", e);
                    }
                }

                @Override
                public String createOrder(int amountMinor, String currency, String receipt) {
                    try {
                        JSONObject orderRequest = new JSONObject();
                        orderRequest.put("amount", amountMinor);
                        orderRequest.put("currency", currency);
                        orderRequest.put("receipt", receipt);
                        Order order = client.orders.create(orderRequest);
                        return order.get("id");
                    } catch (RazorpayException e) {
                        throw new PaymentGatewayException("Failed to create Razorpay order", e);
                    }
                }

                @Override
                public String publicKey() {
                    return keyId;
                }

                @Override
                public boolean verifySignature(String orderId, String paymentId, String signature) {
                    try {
                        JSONObject options = new JSONObject();
                        options.put("razorpay_order_id", orderId);
                        options.put("razorpay_payment_id", paymentId);
                        options.put("razorpay_signature", signature);
                        return Utils.verifyPaymentSignature(options, keySecret);
                    } catch (RazorpayException e) {
                        return false;
                    }
                }

                @Override
                public boolean verifyWebhook(String payload, String signature) {
                    if (webhookSecret == null || webhookSecret.isBlank()) {
                        return true; // no secret configured — nothing to verify against
                    }
                    try {
                        return Utils.verifyWebhookSignature(payload, signature, webhookSecret);
                    } catch (RazorpayException e) {
                        throw new PaymentGatewayException("Webhook verification failed", e);
                    }
                }

                @Override
                public Optional<GatewayWebhookEvent> parseWebhook(String payload) {
                    try {
                        JSONObject root = new JSONObject(payload);
                        if (!"payment.captured".equals(root.optString("event"))) {
                            return Optional.empty(); // only capture events reconcile a payment
                        }
                        JSONObject entity = root.getJSONObject("payload")
                                .getJSONObject("payment")
                                .getJSONObject("entity");
                        String orderId = entity.optString("order_id", null);
                        if (orderId == null) {
                            return Optional.empty();
                        }
                        return Optional.of(new GatewayWebhookEvent(orderId, entity.optString("id", null), true));
                    } catch (RuntimeException e) {
                        return Optional.empty(); // malformed / unexpected payload — ignore rather than fail the callback
                    }
                }
            }
            """;

    private static final String DEMO_PAYMENT_GATEWAY = """
            package __BP__.gateway;

            import java.util.Optional;
            import java.util.UUID;

            /**
             * No-credentials fallback used when no Razorpay keys are configured. It synthesizes order ids
             * and accepts verification so the generated app boots and the checkout flow works end to end
             * in a demo without real gateway keys. There are no real webhooks in demo mode.
             */
            public class DemoPaymentGateway implements PaymentGateway {

                @Override
                public String createOrder(int amountMinor, String currency, String receipt) {
                    return "demo_order_" + UUID.randomUUID().toString().replace("-", "");
                }

                @Override
                public String publicKey() {
                    return "demo_key";
                }

                @Override
                public boolean verifySignature(String orderId, String paymentId, String signature) {
                    return true;
                }

                @Override
                public boolean verifyWebhook(String payload, String signature) {
                    return true;
                }

                @Override
                public Optional<GatewayWebhookEvent> parseWebhook(String payload) {
                    return Optional.empty();
                }
            }
            """;

    private static final String PAYMENT_GATEWAY_CONFIG = """
            package __BP__.config;

            import __BP__.gateway.DemoPaymentGateway;
            import __BP__.gateway.PaymentGateway;
            import __BP__.gateway.RazorpayPaymentGateway;
            import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
            import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
            import org.springframework.beans.factory.annotation.Value;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;

            /**
             * Composition root for the payment gateway strategy. This is the ONLY place that changes when
             * a provider is added — PaymentService and every consumer stay closed for modification. The
             * live provider is selected when its keys are present; otherwise the demo fallback is used.
             */
            @Configuration
            public class PaymentGatewayConfig {

                @Bean
                @ConditionalOnExpression("'${razorpay.key.id:}'.length() > 0 and '${razorpay.key.secret:}'.length() > 0")
                public PaymentGateway razorpayPaymentGateway(
                        @Value("${razorpay.key.id:}") String keyId,
                        @Value("${razorpay.key.secret:}") String keySecret,
                        @Value("${razorpay.webhook.secret:}") String webhookSecret) {
                    return new RazorpayPaymentGateway(keyId, keySecret, webhookSecret);
                }

                @Bean
                @ConditionalOnMissingBean(PaymentGateway.class)
                public PaymentGateway demoPaymentGateway() {
                    return new DemoPaymentGateway();
                }
            }
            """;

    private static final String PAYMENT_SERVICE = """
            package __BP__.service;

            import __BP__.dto.CreatePaymentRequest;
            import __BP__.dto.PaymentOrderResponse;
            import __BP__.dto.PaymentVerificationResponse;
            import __BP__.dto.VerifyPaymentRequest;
            import __BP__.event.PaymentCapturedEvent;
            import __BP__.exception.PaymentGatewayException;
            import __BP__.gateway.GatewayWebhookEvent;
            import __BP__.gateway.PaymentGateway;
            import __BP__.model.Payment;
            import __BP__.model.PaymentStatus;
            import __BP__.repository.PaymentRepository;
            import org.springframework.context.ApplicationEventPublisher;
            import org.springframework.stereotype.Service;
            import org.springframework.transaction.annotation.Transactional;

            import java.math.RoundingMode;
            import java.time.Instant;

            /**
             * Business-agnostic payment orchestration. Inject this into any service that needs to take a
             * payment — it depends on nothing in the business domain, so a consumer → PaymentService edge
             * can never close into a cycle. It talks only to the {@link PaymentGateway} abstraction, so it
             * is closed for modification: swapping or adding a provider never touches this class. The
             * consumer calls {@link #createOrder} to open an order, then {@link #verify} after checkout,
             * and updates ITS OWN entity from the returned result. For webhook-driven flows, listen for
             * {@link PaymentCapturedEvent}.
             */
            @Service
            public class PaymentService {

                private final PaymentRepository paymentRepository;
                private final ApplicationEventPublisher eventPublisher;
                private final PaymentGateway gateway;

                public PaymentService(PaymentRepository paymentRepository,
                                      ApplicationEventPublisher eventPublisher,
                                      PaymentGateway gateway) {
                    this.paymentRepository = paymentRepository;
                    this.eventPublisher = eventPublisher;
                    this.gateway = gateway;
                }

                /** Opens a payment order and persists a CREATED record. Returns what the frontend needs. */
                public PaymentOrderResponse createOrder(CreatePaymentRequest request) {
                    String currency = (request.getCurrency() == null || request.getCurrency().isBlank())
                            ? "INR" : request.getCurrency();
                    int amountMinor = request.getAmount()
                            .movePointRight(2)
                            .setScale(0, RoundingMode.HALF_UP)
                            .intValueExact();

                    String gatewayOrderId = gateway.createOrder(amountMinor, currency, request.getReferenceId());

                    Payment payment = paymentRepository.save(Payment.builder()
                            .referenceId(request.getReferenceId())
                            .gatewayOrderId(gatewayOrderId)
                            .amount(request.getAmount())
                            .currency(currency)
                            .status(PaymentStatus.CREATED)
                            .createdAt(Instant.now())
                            .build());

                    return PaymentOrderResponse.builder()
                            .gatewayOrderId(gatewayOrderId)
                            .gatewayKeyId(gateway.publicKey())
                            .amount(amountMinor)
                            .currency(currency)
                            .paymentRecordId(payment.getId())
                            .build();
                }

                /** Verifies the checkout signature, records the outcome, and publishes an event on capture. */
                @Transactional
                public PaymentVerificationResponse verify(VerifyPaymentRequest request) {
                    Payment payment = paymentRepository.findByGatewayOrderId(request.getGatewayOrderId())
                            .orElseThrow(() -> new PaymentGatewayException(
                                    "No payment found for order " + request.getGatewayOrderId()));

                    boolean verified = gateway.verifySignature(
                            request.getGatewayOrderId(), request.getGatewayPaymentId(), request.getSignature());

                    if (verified) {
                        markCaptured(payment, request.getGatewayPaymentId());
                    } else if (payment.getStatus() != PaymentStatus.CAPTURED) {
                        // never downgrade an already-captured payment (e.g. a webhook beat this call)
                        payment.setGatewayPaymentId(request.getGatewayPaymentId());
                        payment.setStatus(PaymentStatus.FAILED);
                        paymentRepository.save(payment);
                    }

                    return PaymentVerificationResponse.builder()
                            .verified(verified)
                            .status(payment.getStatus().name())
                            .referenceId(payment.getReferenceId())
                            .build();
                }

                /**
                 * Reconciles a gateway webhook — the source of truth when the user closes the browser
                 * before the sync verify call fires. Verifies the signature, then captures the matching
                 * Payment and publishes the event. Idempotent: a payment already captured by the sync
                 * verify (or a duplicate webhook delivery) produces no second event.
                 */
                @Transactional
                public void handleWebhook(String payload, String signature) {
                    if (!gateway.verifyWebhook(payload, signature)) {
                        throw new PaymentGatewayException("Invalid webhook signature");
                    }
                    gateway.parseWebhook(payload)
                            .filter(GatewayWebhookEvent::captured)
                            .ifPresent(event -> paymentRepository.findByGatewayOrderId(event.gatewayOrderId())
                                    .ifPresent(payment -> markCaptured(payment, event.gatewayPaymentId())));
                }

                /**
                 * Idempotent capture + event, shared by the sync verify and the async webhook. A payment
                 * already CAPTURED is left untouched so no duplicate PaymentCapturedEvent is published,
                 * whichever path arrives second.
                 */
                private void markCaptured(Payment payment, String gatewayPaymentId) {
                    if (payment.getStatus() == PaymentStatus.CAPTURED) {
                        return;
                    }
                    payment.setGatewayPaymentId(gatewayPaymentId);
                    payment.setStatus(PaymentStatus.CAPTURED);
                    paymentRepository.save(payment);
                    eventPublisher.publishEvent(new PaymentCapturedEvent(
                            this, payment.getReferenceId(), gatewayPaymentId, payment.getAmount()));
                }
            }
            """;

    private static final String PAYMENT_CONTROLLER = """
            package __BP__.controller;

            import __BP__.dto.CreatePaymentRequest;
            import __BP__.dto.PaymentOrderResponse;
            import __BP__.dto.PaymentVerificationResponse;
            import __BP__.dto.VerifyPaymentRequest;
            import __BP__.service.PaymentService;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestBody;
            import org.springframework.web.bind.annotation.RequestHeader;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            @RequestMapping("/api/v1/payments")
            public class PaymentController {

                private final PaymentService paymentService;

                public PaymentController(PaymentService paymentService) {
                    this.paymentService = paymentService;
                }

                @PostMapping("/create-order")
                public ResponseEntity<PaymentOrderResponse> createOrder(@RequestBody CreatePaymentRequest request) {
                    return ResponseEntity.ok(paymentService.createOrder(request));
                }

                @PostMapping("/verify")
                public ResponseEntity<PaymentVerificationResponse> verify(@RequestBody VerifyPaymentRequest request) {
                    return ResponseEntity.ok(paymentService.verify(request));
                }

                @PostMapping("/webhook")
                public ResponseEntity<String> webhook(
                        @RequestBody String payload,
                        @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
                    paymentService.handleWebhook(payload, signature);
                    return ResponseEntity.ok("ok");
                }
            }
            """;
}
