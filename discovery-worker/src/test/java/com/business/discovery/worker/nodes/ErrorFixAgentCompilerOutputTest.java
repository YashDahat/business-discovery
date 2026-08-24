package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FileType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the loss-aware compiler-output windowing. Circuit-house 2026-07-12: tsc emitted
 * 37KB of errors in path order, so every fixed-size window showed the agent consumer
 * symptoms in src/components/ and cut off the 85 root-cause errors in src/types/ — files
 * the agent is forbidden to edit and therefore must see and escalate, not discover last.
 */
class ErrorFixAgentCompilerOutputTest {

    private static final String TSC_OUTPUT = """
            > frontend@0.0.0 build
            > tsc -b && vite build
            src/components/admin/OrderDetailsView.tsx(21,36): error TS2339: Property 'orderDate' does not exist on type 'OrderDto'.
              21   {format(new Date(order.orderDate), 'PPP p')}
            src/pages/HomePage.tsx(3,4): error TS18047: 'b' is possibly 'null'.
            src/types/order.ts(5,3): error TS2300: Duplicate identifier 'id'.
            src/types/order.ts(14,3): error TS2717: Subsequent property declarations must have the same type.
              14   customerPhone: unknown | null;
            """;

    @Test
    void derivedTypeErrorsSurfaceFirstWithEscalationNote() {
        String out = ErrorFixAgent.prioritizeCompilerOutput(TSC_OUTPUT, 5000);

        assertThat(out.indexOf("src/types/order.ts(5,3)"))
                .isLessThan(out.indexOf("src/components/admin/OrderDetailsView.tsx"));
        assertThat(out).contains("2 error(s) are inside DERIVED files");
        // preamble stays on top, note comes before any error block
        assertThat(out.indexOf("> frontend@0.0.0 build")).isLessThan(out.indexOf("NOTE:"));
        // continuation lines travel with their error block
        assertThat(out.indexOf("customerPhone: unknown | null"))
                .isLessThan(out.indexOf("src/components/admin/OrderDetailsView.tsx"));
    }

    @Test
    void truncationCutsOnBlockBoundaryAndReportsDroppedCount() {
        String out = ErrorFixAgent.prioritizeCompilerOutput(TSC_OUTPUT, 450);

        assertThat(out).contains("of 4 errors");
        assertThat(out).endsWith("to see the rest]");
        // types errors won the window; the components block was the casualty
        assertThat(out).contains("src/types/order.ts(5,3)");
        assertThat(out).doesNotContain("OrderDetailsView");
    }

    @Test
    void nonTscOutputFallsBackToPlainTruncation() {
        String mvn = "[ERROR] Failed to execute goal on project backend\n".repeat(20);
        String out = ErrorFixAgent.prioritizeCompilerOutput(mvn, 100);

        assertThat(out).endsWith("[...truncated]");
        assertThat(out.length()).isLessThan(130);
    }

    @Test
    void smallOutputWithoutTypeErrorsPassesThroughIntact() {
        String small = "src/components/A.tsx(1,2): error TS2339: Property 'x' does not exist.";
        String out = ErrorFixAgent.prioritizeCompilerOutput(small, 5000);

        assertThat(out).contains("error TS2339");
        assertThat(out).doesNotContain("NOTE:");
        assertThat(out).doesNotContain("truncated");
    }

    // ── F3: deterministic input assembly — the union of files named in the errors ──

    @Test
    void extractErrorFiles_frontend_prefixesFrontendAndDedupes() {
        String tsc = """
                src/pages/AdminDashboardPage.tsx(7,10): error TS2305: no exported member 'useAllInquiries'.
                src/pages/AdminDashboardPage.tsx(13,11): error TS2339: Property 'data' does not exist.
                src/components/booking/ClassBookingModal.tsx(42,7): error TS2554: Expected 1 arguments.
                src/types/order.ts(5,3): error TS2300: Duplicate identifier 'id'.
                """;

        assertThat(ErrorFixAgent.extractErrorFiles(FileType.FRONTEND, tsc))
                .containsExactly(
                        "frontend/src/pages/AdminDashboardPage.tsx",
                        "frontend/src/components/booking/ClassBookingModal.tsx",
                        "frontend/src/types/order.ts");
    }

    @Test
    void extractErrorFiles_backend_normalizesAbsolutePathsToBackendRelative() {
        String mvn = """
                [ERROR] /workspace/backend/src/main/java/com/foo/OrderService.java:[12,20] cannot find symbol
                [ERROR] /workspace/backend/src/main/java/com/foo/dto/OrderResponse.java:[4,5] cannot find symbol
                """;

        assertThat(ErrorFixAgent.extractErrorFiles(FileType.BACKEND, mvn))
                .containsExactly(
                        "backend/src/main/java/com/foo/OrderService.java",
                        "backend/src/main/java/com/foo/dto/OrderResponse.java");
    }

    @Test
    void isDerivedPath_flagsDerivedTypesServicesRoutes_notLocalOrPages() {
        assertThat(ErrorFixAgent.isDerivedPath("frontend/src/types/order.ts")).isTrue();
        assertThat(ErrorFixAgent.isDerivedPath("frontend/src/services/orderService.ts")).isTrue();
        assertThat(ErrorFixAgent.isDerivedPath("frontend/src/routes.ts")).isTrue();
        assertThat(ErrorFixAgent.isDerivedPath("frontend/src/types/local/cart.ts")).isFalse();
        assertThat(ErrorFixAgent.isDerivedPath("frontend/src/services/local/persist.ts")).isFalse();
        assertThat(ErrorFixAgent.isDerivedPath("frontend/src/pages/HomePage.tsx")).isFalse();
    }
}
