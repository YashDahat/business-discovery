package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the root-cause clustering that replaced the truncated seed. Fixtures are the real
 * farmaaish-restaurant tsc output (2026-08) where the 6000-char window showed "8 of 57":
 * the same missing module hits 6 pages, the same type-as-value 5 sites — one bulk fix each,
 * but only if the agent sees the whole cluster.
 */
class CompileErrorClustererTest {

    private static final String TSC_OUTPUT = """
            > frontend@0.0.0 typecheck
            > tsc --noEmit -p tsconfig.app.json

            src/pages/AdminDashboardPage.tsx(1,25): error TS2307: Cannot find module '@/components/AdminLayout' or its corresponding type declarations.
            src/pages/AdminEventsPage.tsx(1,25): error TS2307: Cannot find module '@/components/AdminLayout' or its corresponding type declarations.
            src/pages/AdminOrdersPage.tsx(1,25): error TS2307: Cannot find module '@/components/AdminLayout' or its corresponding type declarations.
            src/components/menu/MenuItemsGrid.tsx(58,20): error TS2304: Cannot find name 'Badge'.
            src/components/menu/MenuItemsGrid.tsx(61,20): error TS2304: Cannot find name 'Badge'.
            src/pages/OrderPage.tsx(35,28): error TS2693: 'CheckoutStep' only refers to a type, but is being used as a value here.
            src/pages/OrderPage.tsx(58,28): error TS2693: 'CheckoutStep' only refers to a type, but is being used as a value here.
            src/components/order/PaymentStep.tsx(25,16): error TS2339: Property 'id' does not exist on type 'AuthUser'.
            """;

    @Test
    void groupsRepeatedErrorsIntoOneClusterWithAllAffectedFiles() {
        String out = CompileErrorClusterer.cluster(TSC_OUTPUT, 6000);

        // 8 errors collapse to 4 root causes
        assertThat(out).contains("8 error(s) in 4 root-cause cluster(s)");
        // The AdminLayout module cluster names all 3 pages so it maps onto one bulk_str_replace
        assertThat(out).contains("TS2307 '@/components/AdminLayout'");
        assertThat(out).contains("3 occurrence(s) in 3 file(s)");
        assertThat(out).contains("AdminDashboardPage.tsx")
                       .contains("AdminEventsPage.tsx")
                       .contains("AdminOrdersPage.tsx");
    }

    @Test
    void ordersClustersBiggestFirst() {
        String out = CompileErrorClusterer.cluster(TSC_OUTPUT, 6000);
        // AdminLayout (3) before CheckoutStep (2) before the singletons
        assertThat(out.indexOf("AdminLayout")).isLessThan(out.indexOf("CheckoutStep"));
        assertThat(out.indexOf("CheckoutStep")).isLessThan(out.indexOf("AuthUser"));
    }

    @Test
    void detectsTscErrorsAndIgnoresNpmPreamble() {
        assertThat(CompileErrorClusterer.hasTscErrors(TSC_OUTPUT)).isTrue();
    }

    @Test
    void javacOutputHasNoTscErrorsSoCallerFallsBack() {
        String javac = """
                [ERROR] /workspace/backend/.../OrderResponse.java:[30,18] cannot find symbol
                  symbol:   class OrderItemResponse
                """;
        assertThat(CompileErrorClusterer.hasTscErrors(javac)).isFalse();
        assertThat(CompileErrorClusterer.cluster(javac, 6000)).isEmpty();
    }

    @Test
    void underTightBudgetEveryClusterStillAppears() {
        // Budget smaller than the full detail forces one-line summaries, but no cluster is dropped.
        String out = CompileErrorClusterer.cluster(TSC_OUTPUT, 200);
        assertThat(out).contains("[1]").contains("[2]").contains("[3]").contains("[4]");
    }
}
