package com.business.discovery.worker.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Golden tests for the Java→TS transpilation + typed SDK emission. */
class TsGeneratorsTest {

    @TempDir
    Path src;

    private ApiInventory inventory;

    @BeforeEach
    void fixtures() throws Exception {
        write("com/x/controller/MembershipController.java", """
                @RequestMapping("/api/v1")
                public class MembershipController {
                    @GetMapping("/memberships/plans")
                    public ResponseEntity<List<MembershipPlan>> getPlans() { return null; }
                }
                """);
        write("com/x/controller/AdminTrainerController.java", """
                @RequestMapping("/api/v1/admin")
                public class AdminTrainerController {
                    @GetMapping("/trainers")
                    public ResponseEntity<List<TrainerDto>> getAllTrainers() { return null; }
                    @PostMapping("/trainers")
                    public ResponseEntity<TrainerDto> createTrainer(@RequestBody CreateTrainerRequest request) { return null; }
                    @DeleteMapping("/trainers/{id}")
                    public ResponseEntity<Void> deleteTrainer(@PathVariable UUID id) { return null; }
                }
                """);
        write("com/x/model/MembershipPlan.java", """
                @Entity
                public class MembershipPlan {
                    @Id
                    private UUID id;
                    private String name;
                    private BigDecimal price;
                    private Integer durationInDays;
                }
                """);
        write("com/x/dto/TrainerDto.java", """
                public class TrainerDto {
                    @NotNull
                    private UUID id;
                    private String name;
                    private BookingStatus status;
                }
                """);
        write("com/x/dto/CreateTrainerRequest.java", """
                public class CreateTrainerRequest {
                    @NotBlank
                    private String name;
                }
                """);
        write("com/x/model/BookingStatus.java", """
                public enum BookingStatus { CONFIRMED, CANCELLED_BY_USER }
                """);
        inventory = ApiInventory.extract(src);
    }

    private void write(String rel, String content) throws Exception {
        Path p = src.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    // ── Type mapping table ────────────────────────────────────────────────

    @Test
    void mapsJavaTypesToTs() {
        Set<String> known = Set.of("TrainerDto", "BookingStatus");
        assertThat(TsTypeGenerator.mapType("String", known)).isEqualTo("string");
        assertThat(TsTypeGenerator.mapType("UUID", known)).isEqualTo("string");
        assertThat(TsTypeGenerator.mapType("LocalDateTime", known)).isEqualTo("string");
        assertThat(TsTypeGenerator.mapType("BigDecimal", known)).isEqualTo("number");
        assertThat(TsTypeGenerator.mapType("Integer", known)).isEqualTo("number");
        assertThat(TsTypeGenerator.mapType("boolean", known)).isEqualTo("boolean");
        assertThat(TsTypeGenerator.mapType("List<String>", known)).isEqualTo("string[]");
        assertThat(TsTypeGenerator.mapType("Set<TrainerDto>", known)).isEqualTo("TrainerDto[]");
        assertThat(TsTypeGenerator.mapType("Map<String, Integer>", known)).isEqualTo("Record<string, number>");
        assertThat(TsTypeGenerator.mapType("BookingStatus", known)).isEqualTo("BookingStatus");
        assertThat(TsTypeGenerator.mapType("SomethingWeird", known)).isEqualTo("unknown");
    }

    // ── Enum emission (const object + derived type + version-proof values tuple) ──

    @Test
    void emitsEnumAsConstObjectDerivedTypeAndValuesTuple() {
        String ts = TsTypeGenerator.emitEnum("InquiryType",
                List.of("GENERAL", "MEMBERSHIP", "TRAINING", "COMPLAINT"));

        // runtime const object — usable as a value (Object.values / X.MEMBER / z.nativeEnum)
        assertThat(ts).contains("export const InquiryType = {")
                .contains("  GENERAL: 'GENERAL',")
                .contains("  MEMBERSHIP: 'MEMBERSHIP',")
                .contains("  COMPLAINT: 'COMPLAINT',")
                .contains("} as const;");
        // same-named derived type — usable in type positions
        assertThat(ts).contains("export type InquiryType = typeof InquiryType[keyof typeof InquiryType];");
        // version-proof tuple for z.enum(...) + dropdowns (valid + non-deprecated in zod v3 AND v4)
        assertThat(ts).contains("export const InquiryTypeValues = ['GENERAL', 'MEMBERSHIP', 'TRAINING', 'COMPLAINT'] as const;");
        // the old type-only union is gone
        assertThat(ts).doesNotContain("'GENERAL' | 'MEMBERSHIP'");
    }

    // ── Type file emission ────────────────────────────────────────────────

    @Test
    void emitsInterfacesAtPlannedPathsWithNullabilityAndEnums() {
        TsTypeGenerator.Result result = TsTypeGenerator.generate(inventory,
                List.of("frontend/src/types/trainer.ts", "frontend/src/types/membership.ts"));

        String trainer = result.files().get("frontend/src/types/trainer.ts");
        assertThat(trainer).contains("export interface TrainerDto {");
        assertThat(trainer).contains("id: string;");                 // @NotNull → required
        assertThat(trainer).contains("name: string | null;");       // unannotated → nullable
        assertThat(trainer).contains("export interface CreateTrainerRequest {");
        assertThat(trainer).contains("name: string;");               // @NotBlank → required

        String membership = result.files().get("frontend/src/types/membership.ts");
        assertThat(membership).contains("export interface MembershipPlan {");
        assertThat(membership).contains("price: number | null;");
        // the invented-field class: 'features' can never appear
        assertThat(membership).doesNotContain("features");

        // enum lands with its domain as a const object + derived type + version-proof values tuple,
        // so it resolves in BOTH value and type positions (no more type-only union)
        String enumHome = result.files().values().stream()
                .filter(c -> c.contains("export type BookingStatus"))
                .findFirst().orElseThrow();
        assertThat(enumHome).contains("export const BookingStatus = {")
                .contains("CONFIRMED: 'CONFIRMED',")
                .contains("CANCELLED_BY_USER: 'CANCELLED_BY_USER',")
                .contains("} as const;")
                .contains("export type BookingStatus = typeof BookingStatus[keyof typeof BookingStatus];")
                .contains("export const BookingStatusValues = ['CONFIRMED', 'CANCELLED_BY_USER'] as const;")
                .doesNotContain("'CONFIRMED' | 'CANCELLED_BY_USER'");   // the old type-only union is gone
    }

    // circuit-house 2026-07-12: ApiInventory attributes nested-class fields to the outer
    // DTO (no class-boundary tracking) and mangles multi-var declarations into one field
    // with an unresolvable javaType — emitted raw, every interface was a TS2300 farm
    @Test
    void dedupesInventoryArtifactsFromNestedClassesAndMultiVarDeclarations() throws Exception {
        write("com/x/controller/OrderController.java", """
                @RequestMapping("/api/v1")
                public class OrderController {
                    @GetMapping("/orders")
                    public ResponseEntity<List<OrderDto>> getOrders() { return null; }
                }
                """);
        write("com/x/dto/OrderDto.java", """
                public class OrderDto {
                    private UUID id;
                    private String customerName;
                    private String customerEmail;
                    private String customerPhone;
                    private BigDecimal totalAmount;
                    public static class Builder {
                        private UUID id;
                        private String customerName, customerEmail, customerPhone;
                        private BigDecimal totalAmount;
                    }
                }
                """);

        TsTypeGenerator.Result result = TsTypeGenerator.generate(ApiInventory.extract(src),
                List.of("frontend/src/types/order.ts"));
        String order = result.files().get("frontend/src/types/order.ts");

        assertThat(order).containsOnlyOnce("  id: string | null;");
        assertThat(order).containsOnlyOnce("  customerPhone: string | null;");
        assertThat(order).containsOnlyOnce("  totalAmount: number | null;");
        // the multi-var junk duplicate ("String customerName, customerEmail," customerPhone)
        // must lose to the properly-typed first declaration
        assertThat(order).doesNotContain("unknown");
    }

    // ── SDK emission ──────────────────────────────────────────────────────

    @Test
    void emitsTypedSdkFunctionsWithExactPathsAndMethods() {
        TsTypeGenerator.Result types = TsTypeGenerator.generate(inventory,
                List.of("frontend/src/types/trainer.ts", "frontend/src/types/membership.ts"));
        Map<String, String> services = TsSdkGenerator.generate(inventory, types.typeToPath(),
                List.of("frontend/src/services/trainerService.ts",
                        "frontend/src/services/membershipService.ts"));

        String trainerSvc = services.get("frontend/src/services/trainerService.ts");
        assertThat(trainerSvc).contains("import apiClient from '@/api/client';");
        assertThat(trainerSvc).contains("import type {");
        assertThat(trainerSvc).contains(
                "export const getAllTrainers = async (): Promise<TrainerDto[]> => {");
        assertThat(trainerSvc).contains("apiClient.get<TrainerDto[]>('/api/v1/admin/trainers')");
        assertThat(trainerSvc).contains(
                "export const createTrainer = async (request: CreateTrainerRequest): Promise<TrainerDto> => {");
        assertThat(trainerSvc).contains("apiClient.post<TrainerDto>('/api/v1/admin/trainers', request)");
        // path param → typed arg + template literal
        assertThat(trainerSvc).contains(
                "export const deleteTrainer = async (id: string): Promise<void> => {");
        assertThat(trainerSvc).contains("apiClient.delete<void>(`/api/v1/admin/trainers/${id}`)");

        String membershipSvc = services.get("frontend/src/services/membershipService.ts");
        assertThat(membershipSvc).contains("apiClient.get<MembershipPlan[]>('/api/v1/memberships/plans')");
    }
}
