package com.business.discovery.worker.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceImportRewriterTest {

    private static final String DERIVED_HEADER =
            "// GENERATED from the backend API contract — do not edit by hand.\n";

    @TempDir
    Path frontend;

    private Path src;

    @BeforeEach
    void setUp() throws Exception {
        src = frontend.resolve("src");
        Path services = src.resolve("services");
        Files.createDirectories(services);
        Files.writeString(services.resolve("orderService.ts"), DERIVED_HEADER + """
                import apiClient from '@/api/client';
                export const getAllOrders = async (): Promise<OrderDto[]> => { return [] };
                export const updateOrderStatus = async (id: string): Promise<void> => {};
                """);
        Files.writeString(services.resolve("menuService.ts"), DERIVED_HEADER + """
                import apiClient from '@/api/client';
                export const getMenu = async (): Promise<MenuItemDto[]> => { return [] };
                """);
    }

    @Test
    void rewritesAliasImportOfPrunedServiceToDerivedModule() throws Exception {
        Path comp = write("components/order/OrderTable.tsx", """
                import { getAllOrders, updateOrderStatus } from '@/services/adminOrderService';
                export default function OrderTable() { return null }
                """);

        boolean changed = ServiceImportRewriter.fix(src);

        assertThat(changed).isTrue();
        assertThat(Files.readString(comp))
                .contains("import { getAllOrders, updateOrderStatus } from '@/services/orderService';")
                .doesNotContain("adminOrderService");
    }

    @Test
    void rewritesRelativeImportForm() throws Exception {
        Path hook = write("hooks/useAdminOrders.ts", """
                import { getAllOrders } from '../services/adminOrderService';
                export const useAdminOrders = () => getAllOrders();
                """);

        ServiceImportRewriter.fix(src);

        assertThat(Files.readString(hook))
                .contains("import { getAllOrders } from '@/services/orderService';");
    }

    @Test
    void splitsSymbolsAcrossTheirRealHomes() throws Exception {
        Path comp = write("components/admin/Dashboard.tsx", """
                import { getAllOrders, getMenu } from '@/services/adminService';
                export default function Dashboard() { return null }
                """);

        ServiceImportRewriter.fix(src);

        String result = Files.readString(comp);
        assertThat(result).contains("import { getAllOrders } from '@/services/orderService';")
                .contains("import { getMenu } from '@/services/menuService';");
    }

    @Test
    void leavesUnknownSymbolsForTheFixAgent() throws Exception {
        Path comp = write("components/admin/Weird.tsx", """
                import { deleteEverything } from '@/services/adminOrderService';
                export default function Weird() { return null }
                """);

        boolean changed = ServiceImportRewriter.fix(src);

        assertThat(changed).isFalse();
        assertThat(Files.readString(comp)).contains("deleteEverything");
    }

    @Test
    void neverTouchesImportsOfExistingModules() throws Exception {
        Path localSvc = src.resolve("services/local/cartService.ts");
        Files.createDirectories(localSvc.getParent());
        Files.writeString(localSvc, "export const getCart = () => [];\n");
        Path comp = write("components/cart/Cart.tsx", """
                import { getAllOrders } from '@/services/orderService';
                import { getCart } from '@/services/local/cartService';
                export default function Cart() { return null }
                """);
        String before = Files.readString(comp);

        boolean changed = ServiceImportRewriter.fix(src);

        assertThat(changed).isFalse();
        assertThat(Files.readString(comp)).isEqualTo(before);
    }

    @Test
    void leavesDefaultImportsAlone() throws Exception {
        Path comp = write("components/order/Legacy.tsx", """
                import adminOrderService from '@/services/adminOrderService';
                export default function Legacy() { return null }
                """);
        String before = Files.readString(comp);

        ServiceImportRewriter.fix(src);

        assertThat(Files.readString(comp)).isEqualTo(before);
    }

    @Test
    void mergesIntoExistingImportOfTargetModule() throws Exception {
        Path comp = write("components/order/Merge.tsx", """
                import { getAllOrders } from '@/services/orderService';
                import { updateOrderStatus } from '@/services/adminOrderService';
                export default function Merge() { return null }
                """);

        ServiceImportRewriter.fix(src);

        String result = Files.readString(comp);
        assertThat(result)
                .contains("import { getAllOrders, updateOrderStatus } from '@/services/orderService';")
                .doesNotContain("adminOrderService");
    }

    @Test
    void ignoresLlmWrittenStraysInServicesDir() throws Exception {
        // a stray without the derived marker must not attract imports
        Files.writeString(src.resolve("services/strayService.ts"),
                "export const getAllOrders = async () => [];\n");
        Path comp = write("components/order/OrderTable.tsx", """
                import { getAllOrders } from '@/services/adminOrderService';
                export default function OrderTable() { return null }
                """);

        ServiceImportRewriter.fix(src);

        assertThat(Files.readString(comp))
                .contains("from '@/services/orderService';");
    }

    private Path write(String rel, String content) throws Exception {
        Path file = src.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
