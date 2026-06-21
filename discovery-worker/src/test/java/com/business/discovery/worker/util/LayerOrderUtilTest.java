package com.business.discovery.worker.util;

import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.service.llm.FileEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LayerOrderUtilTest {

    // ── Backend priorities ────────────────────────────────────────────────

    @Test
    void backend_entityByDirectory() {
        assertThat(LayerOrderUtil.backendPriority(be("backend/src/main/java/com/test/entity/Order.java"))).isEqualTo(10);
    }

    @Test
    void backend_entityBySuffix() {
        assertThat(LayerOrderUtil.backendPriority(be("backend/src/main/java/com/test/OrderEntity.java"))).isEqualTo(10);
    }

    @Test
    void backend_dtoByDirectory() {
        assertThat(LayerOrderUtil.backendPriority(be("backend/src/main/java/com/test/dto/OrderResponse.java"))).isEqualTo(20);
    }

    @Test
    void backend_dtoByRequestSuffix() {
        assertThat(LayerOrderUtil.backendPriority(be("backend/src/main/java/com/test/CreateOrderRequest.java"))).isEqualTo(20);
    }

    @Test
    void backend_dtoByResponseSuffix() {
        assertThat(LayerOrderUtil.backendPriority(be("backend/src/main/java/com/test/OrderResponse.java"))).isEqualTo(20);
    }

    @Test
    void backend_utilByDirectory() {
        assertThat(LayerOrderUtil.backendPriority(be("backend/src/main/java/com/test/util/DateUtil.java"))).isEqualTo(30);
    }

    @Test
    void backend_exceptionByDirectory() {
        assertThat(LayerOrderUtil.backendPriority(be("backend/src/main/java/com/test/exception/GlobalExceptionHandler.java"))).isEqualTo(30);
    }

    @Test
    void backend_repositoryByDirectory() {
        assertThat(LayerOrderUtil.backendPriority(be("backend/src/main/java/com/test/repository/OrderRepository.java"))).isEqualTo(40);
    }

    @Test
    void backend_repositoryBySuffix() {
        assertThat(LayerOrderUtil.backendPriority(be("backend/src/main/java/com/test/OrderRepository.java"))).isEqualTo(40);
    }

    @Test
    void backend_serviceByDirectory() {
        assertThat(LayerOrderUtil.backendPriority(be("backend/src/main/java/com/test/service/OrderService.java"))).isEqualTo(50);
    }

    @Test
    void backend_controllerByDirectory() {
        assertThat(LayerOrderUtil.backendPriority(be("backend/src/main/java/com/test/controller/OrderController.java"))).isEqualTo(60);
    }

    @Test
    void backend_configByDirectory() {
        assertThat(LayerOrderUtil.backendPriority(be("backend/src/main/java/com/test/config/SecurityConfig.java"))).isEqualTo(70);
    }

    @Test
    void backend_unknown() {
        assertThat(LayerOrderUtil.backendPriority(be("backend/src/main/java/com/test/misc/Foo.java"))).isEqualTo(99);
    }

    @Test
    void backend_sortOrder_entityBeforeServiceBeforeController() {
        var entity = be("backend/.../entity/Order.java");
        var dto = be("backend/.../dto/OrderResponse.java");
        var repo = be("backend/.../repository/OrderRepository.java");
        var service = be("backend/.../service/OrderService.java");
        var controller = be("backend/.../controller/OrderController.java");

        List<FileEntry> sorted = List.of(controller, service, repo, dto, entity)
                .stream()
                .sorted(java.util.Comparator.comparingInt(LayerOrderUtil::backendPriority))
                .toList();

        assertThat(sorted).containsExactly(entity, dto, repo, service, controller);
    }

    @Test
    void backend_layerNames() {
        assertThat(LayerOrderUtil.backendLayerName(be("backend/.../entity/Order.java"))).isEqualTo("ENTITY");
        assertThat(LayerOrderUtil.backendLayerName(be("backend/.../dto/OrderResponse.java"))).isEqualTo("DTO");
        assertThat(LayerOrderUtil.backendLayerName(be("backend/.../repository/OrderRepository.java"))).isEqualTo("REPOSITORY");
        assertThat(LayerOrderUtil.backendLayerName(be("backend/.../service/OrderService.java"))).isEqualTo("SERVICE");
        assertThat(LayerOrderUtil.backendLayerName(be("backend/.../controller/OrderController.java"))).isEqualTo("CONTROLLER");
    }

    // ── Frontend priorities ───────────────────────────────────────────────

    @Test
    void frontend_typesByDirectory() {
        assertThat(LayerOrderUtil.frontendPriority(fe("frontend/src/types/order.types.ts"))).isEqualTo(10);
    }

    @Test
    void frontend_typesBySuffix() {
        assertThat(LayerOrderUtil.frontendPriority(fe("frontend/src/order.types.ts"))).isEqualTo(10);
    }

    @Test
    void frontend_constantsByDirectory() {
        assertThat(LayerOrderUtil.frontendPriority(fe("frontend/src/constants/routes.ts"))).isEqualTo(20);
    }

    @Test
    void frontend_utilsByDirectory() {
        assertThat(LayerOrderUtil.frontendPriority(fe("frontend/src/utils/formatDate.ts"))).isEqualTo(30);
    }

    @Test
    void frontend_apiServiceByDirectory() {
        assertThat(LayerOrderUtil.frontendPriority(fe("frontend/src/services/orderService.ts"))).isEqualTo(35);
    }

    @Test
    void frontend_apiClientByName() {
        assertThat(LayerOrderUtil.frontendPriority(fe("frontend/src/api/client.ts"))).isEqualTo(35);
    }

    @Test
    void frontend_hookByDirectory() {
        assertThat(LayerOrderUtil.frontendPriority(fe("frontend/src/hooks/useOrders.ts"))).isEqualTo(50);
    }

    @Test
    void frontend_hookByPrefix() {
        assertThat(LayerOrderUtil.frontendPriority(fe("frontend/src/useMenu.ts"))).isEqualTo(50);
    }

    @Test
    void frontend_componentByDirectory() {
        assertThat(LayerOrderUtil.frontendPriority(fe("frontend/src/components/MenuCard.tsx"))).isEqualTo(60);
    }

    @Test
    void frontend_pageByDirectory() {
        assertThat(LayerOrderUtil.frontendPriority(fe("frontend/src/pages/MenuPage.tsx"))).isEqualTo(70);
    }

    @Test
    void frontend_appEntry() {
        assertThat(LayerOrderUtil.frontendPriority(fe("frontend/src/App.tsx"))).isEqualTo(80);
    }

    @Test
    void frontend_sortOrder_serviceBeforeHookBeforeComponent() {
        var types = fe("frontend/src/types/order.types.ts");
        var service = fe("frontend/src/services/orderService.ts");
        var hook = fe("frontend/src/hooks/useOrders.ts");
        var component = fe("frontend/src/components/OrderList.tsx");
        var page = fe("frontend/src/pages/OrderPage.tsx");
        var app = fe("frontend/src/App.tsx");

        List<FileEntry> sorted = List.of(app, page, component, hook, service, types)
                .stream()
                .sorted(java.util.Comparator.comparingInt(LayerOrderUtil::frontendPriority))
                .toList();

        assertThat(sorted).containsExactly(types, service, hook, component, page, app);
    }

    @Test
    void frontend_serviceLayerBeforeHookThatCallsIt() {
        // orderService.ts must be generated before useOrders.ts which imports it
        var service = fe("frontend/src/services/orderService.ts");
        var hook = fe("frontend/src/hooks/useOrders.ts");
        assertThat(LayerOrderUtil.frontendPriority(service))
                .isLessThan(LayerOrderUtil.frontendPriority(hook));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private FileEntry be(String path) {
        return new FileEntry(path, FileType.BACKEND, "desc");
    }

    private FileEntry fe(String path) {
        return new FileEntry(path, FileType.FRONTEND, "desc");
    }
}
