package com.business.discovery.worker.util;

import com.business.discovery.worker.util.FoundationSymbolRegistry.Field;
import com.business.discovery.worker.util.FoundationSymbolRegistry.Kind;
import com.business.discovery.worker.util.FoundationSymbolRegistry.Layer;
import com.business.discovery.worker.util.FoundationSymbolRegistry.Symbol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FoundationSymbolRegistryTest {

    @TempDir
    Path workspace;

    // Trimmed but format-faithful copies of the real contract cards (fences, single- and multi-line
    // bodies, trailing comments, nested TS objects) so the parser is exercised on the real shapes.
    private static final String BACKEND_CONTRACT = """
            # Foundation Backend Contract (FENCED Java spine — ground truth)

            Prose that must be ignored — class Nope { should not parse } lives outside a fence.

            ```java
            // Entity — table "_user"; implements UserDetails. getUsername() returns email.
            class User {
              Integer id; String firstName; String lastName; String email; String phone; String password; Role role;
            }
            enum Role { ADMIN, USER }        // authority is the bare name ("ADMIN"), no ROLE_ prefix

            interface UserRepository extends JpaRepository<User, Integer> {
              Optional<User> findByEmail(String email);
            }
            ```

            ## Payments

            ```java
            class CreatePaymentRequest { BigDecimal amount; String currency; String referenceId; }   // amount in MAJOR units; referenceId opaque, e.g. "order_42"
            class Payment { Long id; String referenceId; String gatewayOrderId; String gatewayPaymentId;
                            BigDecimal amount; String currency; PaymentStatus status; Instant createdAt; }
            enum PaymentStatus { CREATED, CAPTURED, FAILED }
            ```

            ## Shared exceptions

            ```java
            class ResourceNotFoundException extends RuntimeException {
              ResourceNotFoundException(String message);
              ResourceNotFoundException(String message, Throwable cause);
            }
            ```
            """;

    private static final String FRONTEND_CONTRACT = """
            # Foundation Frontend Contract (FENCED)

            ```ts
            useAuth(): {
              token: string | null;
              isAuthenticated: boolean;
              user: AuthUser | null;
            }

            type AuthUser     = { username: string; role: string };
            type RegisterData = { name: string; email?: string; phone?: string; password: string };
            ```

            ```ts
            interface CartItem   { id: string | number; name: string; unitPrice: number; quantity: number;
                                   imageUrl?: string | null; variantKey?: string; metadata?: Record<string, unknown> }
            interface CartTotals { subtotal: number; adjustments: { id: string; label: string; amount: number }[]; total: number }
            interface SiteHeaderProps { brandName: string; navLinks: NavLink[];
                                        ctaButton?: { label: string; href: string } | null; showAuth?: boolean }
            ```
            """;

    private FoundationSymbolRegistry build() throws Exception {
        Files.createDirectories(workspace.resolve("backend"));
        Files.createDirectories(workspace.resolve("frontend"));
        Files.writeString(workspace.resolve("backend/FOUNDATION_CONTRACT.md"), BACKEND_CONTRACT);
        Files.writeString(workspace.resolve("frontend/FOUNDATION_CONTRACT.md"), FRONTEND_CONTRACT);
        return FoundationSymbolRegistry.buildFromWorkspace(workspace);
    }

    private static Optional<Field> field(Symbol s, String name) {
        return s.fields().stream().filter(f -> f.name().equals(name)).findFirst();
    }

    @Test
    void parsesUserEntityFieldsAndType() throws Exception {
        FoundationSymbolRegistry reg = build();
        Symbol user = reg.get("User").orElseThrow();
        assertThat(user.layer()).isEqualTo(Layer.BACKEND);
        assertThat(user.kind()).isEqualTo(Kind.CLASS);
        assertThat(field(user, "id")).contains(new Field("id", "Integer", false));
        assertThat(field(user, "role")).contains(new Field("role", "Role", false));
        assertThat(field(user, "email")).contains(new Field("email", "String", false));
    }

    @Test
    void parsesRoleAsExactlyAdminUser_notCustomer() throws Exception {
        FoundationSymbolRegistry reg = build();
        Symbol role = reg.get("Role").orElseThrow();
        assertThat(role.isEnum()).isTrue();
        assertThat(role.enumConstants()).containsExactly("ADMIN", "USER");
        assertThat(role.enumConstants()).doesNotContain("CUSTOMER");
    }

    @Test
    void parsesPaymentSpineAndStripsTrailingComments() throws Exception {
        FoundationSymbolRegistry reg = build();
        Symbol payment = reg.get("Payment").orElseThrow();
        assertThat(field(payment, "id")).contains(new Field("id", "Long", false));
        assertThat(field(payment, "referenceId")).contains(new Field("referenceId", "String", false));
        assertThat(field(payment, "createdAt")).contains(new Field("createdAt", "Instant", false));

        Symbol req = reg.get("CreatePaymentRequest").orElseThrow();
        // trailing "// amount in MAJOR units; referenceId opaque..." must not leak into fields
        assertThat(req.fields()).extracting(Field::name)
                .containsExactly("amount", "currency", "referenceId");

        assertThat(reg.get("PaymentStatus").orElseThrow().enumConstants())
                .containsExactly("CREATED", "CAPTURED", "FAILED");
    }

    @Test
    void recordsFencedExceptionWithNoFields() throws Exception {
        FoundationSymbolRegistry reg = build();
        Symbol ex = reg.get("ResourceNotFoundException").orElseThrow();
        assertThat(ex.kind()).isEqualTo(Kind.CLASS);
        assertThat(ex.fields()).isEmpty();   // constructors carry '(' and are skipped
        assertThat(reg.isFenced("ResourceNotFoundException")).isTrue();
    }

    @Test
    void ignoresDeclarationsOutsideCodeFences() throws Exception {
        FoundationSymbolRegistry reg = build();
        assertThat(reg.isFenced("Nope")).isFalse();
    }

    @Test
    void parsesFrontendTypesIncludingNestedObjectsAndOptionality() throws Exception {
        FoundationSymbolRegistry reg = build();

        Symbol authUser = reg.get("AuthUser").orElseThrow();
        assertThat(authUser.layer()).isEqualTo(Layer.FRONTEND);
        assertThat(authUser.kind()).isEqualTo(Kind.TYPE);
        assertThat(field(authUser, "username")).contains(new Field("username", "string", false));
        assertThat(field(authUser, "role")).contains(new Field("role", "string", false));

        Symbol register = reg.get("RegisterData").orElseThrow();
        assertThat(field(register, "email").orElseThrow().optional()).isTrue();
        assertThat(field(register, "password").orElseThrow().optional()).isFalse();

        // nested object must NOT be split on its internal ';' — adjustments + total both survive
        Symbol totals = reg.get("CartTotals").orElseThrow();
        assertThat(field(totals, "subtotal")).isPresent();
        assertThat(field(totals, "adjustments").orElseThrow().type()).startsWith("{");
        assertThat(field(totals, "total")).contains(new Field("total", "number", false));

        Symbol header = reg.get("SiteHeaderProps").orElseThrow();
        assertThat(field(header, "ctaButton").orElseThrow().optional()).isTrue();
        assertThat(field(header, "brandName")).contains(new Field("brandName", "string", false));

        // union type with no braces stays intact
        Symbol item = reg.get("CartItem").orElseThrow();
        assertThat(field(item, "id")).contains(new Field("id", "string | number", false));
    }

    @Test
    void referenceConventionsAreUserIdAndReferenceIdHandles() throws Exception {
        FoundationSymbolRegistry reg = build();
        var user = reg.referenceConventionFor("User").orElseThrow();
        assertThat(user.handleField()).isEqualTo("userId");
        assertThat(user.handleType()).isEqualTo("Integer");
        var payment = reg.referenceConventionFor("Payment").orElseThrow();
        assertThat(payment.handleField()).isEqualTo("referenceId");
    }

    @Test
    void renderForPlannerIsCorrectShapeAndHandle() throws Exception {
        FoundationSymbolRegistry reg = build();
        String block = reg.renderForPlanner();
        assertThat(block).contains("Role (enum): ADMIN, USER");
        assertThat(block).doesNotContain("CUSTOMER");
        assertThat(block).contains("Integer userId");
        assertThat(block).contains("BACKEND:").contains("FRONTEND:");
    }

    @Test
    void emptyWorkspaceDegradesGracefully() {
        FoundationSymbolRegistry reg = FoundationSymbolRegistry.buildFromWorkspace(workspace);
        assertThat(reg.isEmpty()).isTrue();
        assertThat(reg.renderForPlanner()).isEmpty();
    }
}
