package com.business.discovery.worker.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixtures are the real circuit-house artifacts (2026-07-12). OrderDetailsView had
 * imports_from = [] in the spec, so it received no dependency context and invented
 * order.orderDate / order.items / order.paymentInfo against a derived OrderDto declaring
 * orderTime / orderItems / no payment field — 64 of that run's build errors. The card
 * exists so those field names reach the prompt no matter what the planner emitted.
 */
class ApiContractCardTest {

    @TempDir
    Path workspace;

    private static final String DERIVED_HEADER =
            "// GENERATED from the backend API contract — do not edit by hand.\n"
            + "// Source of truth: backend controllers/DTOs (see docs/API_INVENTORY.json).\n\n";

    @BeforeEach
    void fixtures() throws Exception {
        write("frontend/src/types/order.ts", DERIVED_HEADER + """
                export interface OrderDto {
                  id: string | null;
                  orderTime: string | null;
                  totalAmount: number | null;
                  orderItems: OrderItemDto[] | null;
                }
                """);
        write("frontend/src/services/orderService.ts", DERIVED_HEADER + """
                import apiClient from '@/api/client';
                import type { OrderDto } from '@/types/order';

                export const getAllOrders = async (): Promise<OrderDto[]> => {
                  const response = await apiClient.get<OrderDto[]>('/api/v1/admin/orders');
                  return response.data;
                };

                export const updateOrderStatus = async (orderId: string, request: unknown): Promise<OrderDto> => {
                  const response = await apiClient.put<OrderDto>(`/api/v1/admin/orders/${orderId}/status`, request);
                  return response.data;
                };
                """);
        // the LLM-written parallel service that shipped alongside the derived one:
        // wrong prefix, wrong verb, and it must never be presented as ground truth
        write("frontend/src/services/adminOrderService.ts", """
                import apiClient from './apiClient';
                export const adminOrderService = {
                  getAllOrders(): Promise<OrderDto[]> { return apiClient.get('/admin/orders'); },
                };
                """);
    }

    private void write(String rel, String content) throws Exception {
        Path p = workspace.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void carriesTheRealFieldNamesSoTheyCannotBeInvented() {
        String card = ApiContractCard.build(workspace).toPromptSection();

        assertThat(card).contains("orderTime: string | null;");
        assertThat(card).contains("orderItems: OrderItemDto[] | null;");
        // the three fields OrderDetailsView hallucinated are absent from the contract,
        // and the card states plainly that absence means non-existence
        assertThat(card).doesNotContain("orderDate");
        assertThat(card).doesNotContain("paymentInfo");
        assertThat(card).contains("DOES NOT EXIST");
        assertThat(card).contains("ARE nullable");
    }

    @Test
    void exposesSdkSignaturesAndPrefixedRoutes() {
        ApiContractCard card = ApiContractCard.build(workspace);
        String section = card.toPromptSection();

        assertThat(section).contains("getAllOrders(): Promise<OrderDto[]>");
        assertThat(section).contains("updateOrderStatus(orderId: string, request: unknown): Promise<OrderDto>");
        assertThat(section).contains("GET /api/v1/admin/orders");
        assertThat(section).contains("PUT /api/v1/admin/orders/${orderId}/status");
        assertThat(card.routeCount()).isEqualTo(2);
    }

    @Test
    void refusesToLaunderLlmWrittenServicesIntoGroundTruth() {
        String card = ApiContractCard.build(workspace).toPromptSection();

        // adminOrderService.ts lacks the derived marker: its wrong-prefix path and PATCH verb
        // must not appear in the card, or a hallucination becomes law for 140 downstream calls
        assertThat(card).doesNotContain("adminOrderService");
        assertThat(card).doesNotContain("'/admin/orders'");
    }

    @Test
    void bodyIsRunConstantSoItCachesAcrossEveryGenerationCall() {
        assertThat(ApiContractCard.build(workspace).toPromptSection())
                .isEqualTo(ApiContractCard.build(workspace).toPromptSection());
    }

    @Test
    void emptyWorkspaceIsEmptyRatherThanFabricated() {
        ApiContractCard card = ApiContractCard.build(workspace.resolve("nonexistent"));

        assertThat(card.isEmpty()).isTrue();
        assertThat(card.typeFileCount()).isZero();
    }
}
