package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.ApiEndpoint;
import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.PublicFunction;
import com.business.discovery.worker.service.llm.PublicVariable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileContractCardTest {

    @Test
    void render_controller_includesPurposeMethodsAndEndpointsWithAccess() {
        ApiEndpoint ep = new ApiEndpoint();
        ep.setMethod("POST");
        ep.setPath("/api/v1/admin/orders");
        ep.setRequestBody("CreateOrderRequest");
        ep.setResponseBody("OrderDto");
        ep.setAccess("admin");

        FileSpec spec = FileSpec.builder()
                .filePath("backend/src/main/java/com/x/OrderController.java")
                .description("REST controller for orders.")
                .publicFunctions(List.of(PublicFunction.builder()
                        .name("createOrder").parameters(List.of("CreateOrderRequest req"))
                        .returnType("OrderDto").build()))
                .apiEndpoints(List.of(ep))
                .build();

        String out = FileContractCard.render(spec, "REST Controller — order endpoints");

        assertThat(out).contains("REST Controller — order endpoints");           // role preserved first
        assertThat(out).contains("THIS FILE'S PLANNED CONTRACT");                 // not reconciled here
        assertThat(out).contains("- Purpose: REST controller for orders.");
        assertThat(out).contains("- Methods: createOrder(CreateOrderRequest req): OrderDto");
        assertThat(out).contains("- Endpoints: POST /api/v1/admin/orders (body: CreateOrderRequest) → OrderDto [admin]");
    }

    @Test
    void render_dto_includesFields() {
        FileSpec spec = FileSpec.builder()
                .filePath("backend/src/main/java/com/x/OrderDto.java")
                .publicVariables(List.of(
                        new PublicVariable("userId", "Integer", null),
                        new PublicVariable("totalAmount", "BigDecimal", null)))
                .build();

        String out = FileContractCard.render(spec, "DTO");
        assertThat(out).contains("- Fields: userId: Integer; totalAmount: BigDecimal");
        assertThat(out).doesNotContain("- Methods:");
        assertThat(out).doesNotContain("- Endpoints:");
    }

    @Test
    void render_reconciledMethodShape_fullSignatureInSingleParam_kept() {
        FileSpec spec = FileSpec.builder()
                .filePath("backend/src/main/java/com/x/OrderService.java")
                .publicFunctions(List.of(PublicFunction.builder()
                        .parameters(List.of("OrderDto createOrder(CreateOrderRequest req)")).build()))
                .build();

        String out = FileContractCard.render(spec, "SERVICE");
        assertThat(out).contains("- Methods: OrderDto createOrder(CreateOrderRequest req)");
    }

    @Test
    void render_noSpecDetail_returnsRoleUnchanged() {
        FileSpec spec = FileSpec.builder()
                .filePath("backend/src/main/java/com/x/Empty.java").build();
        assertThat(FileContractCard.render(spec, "just a role")).isEqualTo("just a role");
    }

    @Test
    void render_nullSpec_returnsRoleNeverNull() {
        assertThat(FileContractCard.render(null, "role")).isEqualTo("role");
        assertThat(FileContractCard.render(null, null)).isEqualTo("");
    }

    @Test
    void render_labelsReconciledVsPlanned() {
        FileSpec planned = FileSpec.builder()
                .filePath("backend/src/main/java/com/x/OrderDto.java")
                .publicVariables(List.of(new PublicVariable("userId", "Integer", null)))
                .build();
        assertThat(FileContractCard.render(planned, "DTO")).contains("THIS FILE'S PLANNED CONTRACT");

        planned.setContractReconciled(true);
        assertThat(FileContractCard.render(planned, "DTO"))
                .contains("THIS FILE'S EXACT CONTRACT (RECONCILED ground truth");
    }

    @Test
    void renderInterfaceOnly_fieldsMethodsEndpoints_noHeader() {
        FileSpec spec = FileSpec.builder()
                .filePath("backend/src/main/java/com/x/OrderService.java")
                .publicVariables(List.of(new PublicVariable("id", "UUID", null)))
                .publicFunctions(List.of(PublicFunction.builder()
                        .name("createOrder").parameters(List.of("CreateOrderRequest req"))
                        .returnType("OrderDto").build()))
                .build();

        String iface = FileContractCard.renderInterfaceOnly(spec);
        assertThat(iface).contains("{ id: UUID }");
        assertThat(iface).contains("methods: createOrder(CreateOrderRequest req): OrderDto");
        assertThat(iface).doesNotContain("THIS FILE");        // no header
        assertThat(FileContractCard.renderInterfaceOnly(null)).isEmpty();
    }
}
