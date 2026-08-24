package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.PublicFunction;
import com.business.discovery.worker.service.llm.PublicVariable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FrontendPlannedContractCardTest {

    private FileSpec hook() {
        return FileSpec.builder()
                .filePath("frontend/src/hooks/useMenu.ts")
                .publicFunctions(List.of(PublicFunction.builder()
                        .name("useMenu").parameters(List.of())
                        .returnType("{ data: MenuItemDto[]; isLoading: boolean }").build()))
                .build();
    }

    private FileSpec service() {
        return FileSpec.builder()
                .filePath("frontend/src/services/orderService.ts")
                .publicFunctions(List.of(PublicFunction.builder()
                        .name("createOrder").parameters(List.of("req: CreateOrderRequest"))
                        .returnType("Promise<OrderDto>").build()))
                .build();
    }

    private FileSpec type() {
        return FileSpec.builder()
                .filePath("frontend/src/types/menu.ts")
                .publicVariables(List.of(new PublicVariable("MenuItemDto", "interface", null)))
                .build();
    }

    private FileSpec component() {
        return FileSpec.builder()
                .filePath("frontend/src/components/menu/MenuItemsGrid.tsx")
                .publicFunctions(List.of(PublicFunction.builder()
                        .name("MenuItemsGrid").parameters(List.of("items: MenuItemDto[]")).build()))
                .build();
    }

    private FileSpec backend() {
        return FileSpec.builder()
                .filePath("backend/src/main/java/com/x/OrderService.java")
                .publicFunctions(List.of(PublicFunction.builder().name("createOrder").build()))
                .build();
    }

    @Test
    void build_includesHooksServicesTypes_keyedByImportAlias() {
        FrontendPlannedContractCard card = FrontendPlannedContractCard.build(
                List.of(hook(), service(), type()));

        assertThat(card.moduleCount()).isEqualTo(3);
        String out = card.toPromptSection();
        assertThat(out).contains("@/hooks/useMenu:");
        assertThat(out).contains("@/services/orderService: methods: createOrder(req: CreateOrderRequest): Promise<OrderDto>");
        assertThat(out).contains("@/types/menu: { MenuItemDto: interface }");
        assertThat(out).contains("PLANNED FRONTEND MODULE CONTRACTS");
    }

    @Test
    void build_excludesComponentsPagesAndBackend() {
        FrontendPlannedContractCard card = FrontendPlannedContractCard.build(
                List.of(component(), backend()));

        assertThat(card.isEmpty()).isTrue();          // component -> props card; .java -> backend card
    }

    @Test
    void build_null_isEmpty() {
        assertThat(FrontendPlannedContractCard.build(null).isEmpty()).isTrue();
        assertThat(FrontendPlannedContractCard.build(List.of()).toPromptSection()).isEmpty();
    }
}
