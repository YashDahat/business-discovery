package com.business.discovery.worker.util;

import com.business.discovery.worker.util.FoundationImpedanceAudit.Finding;
import com.business.discovery.worker.util.FoundationImpedanceAudit.Layer;
import com.business.discovery.worker.util.FoundationImpedanceAudit.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class FoundationImpedanceAuditTest {

    @TempDir
    Path ws;

    private void write(String rel, String content) throws Exception {
        Path p = ws.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private FoundationSymbolRegistry registry() {
        return FoundationSymbolRegistry.buildFromWorkspace(ws);   // empty is fine; handle name is static
    }

    private static Set<String> categories(Result r) {
        return r.findings().stream().map(Finding::category).collect(Collectors.toSet());
    }

    private static List<String> files(Result r, String category) {
        return r.findings().stream().filter(f -> f.category().equals(category)).map(Finding::file).toList();
    }

    // ── backend ──

    @Test
    void detectsBackendIdentityHacksSurrogateKeyAndWrongRole() throws Exception {
        write("backend/src/main/java/com/x/controller/OrderController.java", """
                package com.x.controller;
                class OrderController {
                  void place() {
                    UUID uid = UUID.nameUUIDFromBytes(userDetails.getUsername().getBytes());
                  }
                }
                """);
        write("backend/src/main/java/com/x/controller/BookingController.java", """
                package com.x.controller;
                class BookingController {
                  void book() {
                    UUID uid = UUID.fromString("00000000-0000-0000-0000-000000000001");
                  }
                }
                """);
        write("backend/src/main/java/com/x/model/Order.java", """
                package com.x.model;
                class Order {
                  private UUID userId;
                  private BigDecimal amount;
                }
                """);
        write("backend/src/main/java/com/x/service/AccessService.java", """
                package com.x.service;
                class AccessService {
                  boolean admin(User u) { return u.getRole() == Role.CUSTOMER; }
                }
                """);
        write("backend/src/main/java/com/x/model/Product.java", """
                package com.x.model;
                class Product { private Long id; private String name; }
                """);

        Result r = FoundationImpedanceAudit.audit(
                ws.resolve("backend/src/main/java"), Layer.BACKEND, ws, registry());

        assertThat(categories(r)).containsExactlyInAnyOrder(
                "IDENTITY_HACK", "USER_SURROGATE_KEY", "WRONG_ROLE_VALUE");
        assertThat(files(r, "IDENTITY_HACK"))
                .anyMatch(f -> f.endsWith("OrderController.java"))
                .anyMatch(f -> f.endsWith("BookingController.java"));
        assertThat(files(r, "USER_SURROGATE_KEY")).allMatch(f -> f.endsWith("Order.java"));
        // the clean entity (Long id PK + plain fields) produces nothing
        assertThat(r.findings()).noneMatch(f -> f.file().endsWith("Product.java"));
    }

    @Test
    void detectsUuidPrimaryKeyAndStrategyButNotLongId() throws Exception {
        write("backend/src/main/java/com/x/model/Booking.java", """
                package com.x.model;
                class Booking {
                  @Id
                  @GeneratedValue(strategy = GenerationType.UUID)
                  private UUID id;
                  private UUID planId;
                }
                """);
        write("backend/src/main/java/com/x/model/Trainer.java", """
                package com.x.model;
                class Trainer {
                  @Id
                  @GeneratedValue(strategy = GenerationType.IDENTITY)
                  private Long id;
                }
                """);

        Result r = FoundationImpedanceAudit.audit(
                ws.resolve("backend/src/main/java"), Layer.BACKEND, ws, registry());

        assertThat(categories(r)).containsExactly("UUID_ID");
        assertThat(files(r, "UUID_ID")).allMatch(f -> f.endsWith("Booking.java"));   // UUID id/planId + strategy
        assertThat(r.findings()).noneMatch(f -> f.file().endsWith("Trainer.java"));  // Long id is clean
    }

    // ── frontend ──

    @Test
    void detectsFrontendTokenParsingAndRedeclaredShapeButSkipsFencedFiles() throws Exception {
        write("frontend/src/components/CartDrawer.tsx", """
                export function CartDrawer() {
                  const t = localStorage.getItem('token');
                  return null;
                }
                """);
        // fenced foundation files legitimately touch the token / user shape — must NOT be flagged
        write("frontend/src/context/AuthContext.tsx", """
                const token = localStorage.getItem('token');
                """);
        write("frontend/src/api/client.ts", """
                const token = localStorage.getItem('token');
                """);
        write("frontend/src/types/order.ts", """
                export interface User { id: string; name: string }
                """);

        Result r = FoundationImpedanceAudit.audit(ws.resolve("frontend/src"), Layer.FRONTEND, ws, registry());

        assertThat(categories(r)).containsExactlyInAnyOrder("TOKEN_PARSING", "REDECLARED_USER_SHAPE");
        assertThat(files(r, "TOKEN_PARSING")).containsExactly("frontend/src/components/CartDrawer.tsx");
        assertThat(r.findings()).noneMatch(f -> f.file().contains("AuthContext"))
                                .noneMatch(f -> f.file().contains("client.ts"));
    }

    // ── report + clean case ──

    @Test
    void writesReportSectionsAndReportsCleanWhenNoImpedance() throws Exception {
        write("backend/src/main/java/com/x/model/Product.java", """
                package com.x.model;
                class Product { private Long id; private String name; }
                """);
        Result r = FoundationImpedanceAudit.audit(
                ws.resolve("backend/src/main/java"), Layer.BACKEND, ws, registry());

        assertThat(r.clean()).isTrue();
        String report = Files.readString(ws.resolve("docs/FOUNDATION_AUDIT.md"));
        assertThat(report).contains("## BACKEND").contains("Clean — no residual foundation impedance");
    }

    @Test
    void reportUpsertKeepsBothLayerSectionsAndIsIdempotent() throws Exception {
        write("frontend/src/components/CartDrawer.tsx",
                "const t = localStorage.getItem('token');\n");

        FoundationImpedanceAudit.audit(ws.resolve("backend/src/main/java"), Layer.BACKEND, ws, registry());
        FoundationImpedanceAudit.audit(ws.resolve("frontend/src"), Layer.FRONTEND, ws, registry());
        String first = Files.readString(ws.resolve("docs/FOUNDATION_AUDIT.md"));
        assertThat(first).contains("## BACKEND").contains("## FRONTEND").contains("TOKEN_PARSING");

        // re-running a layer replaces its section rather than appending a duplicate
        FoundationImpedanceAudit.audit(ws.resolve("frontend/src"), Layer.FRONTEND, ws, registry());
        String second = Files.readString(ws.resolve("docs/FOUNDATION_AUDIT.md"));
        assertThat(second.split("## FRONTEND", -1)).hasSize(2);   // exactly one FRONTEND section
    }
}
