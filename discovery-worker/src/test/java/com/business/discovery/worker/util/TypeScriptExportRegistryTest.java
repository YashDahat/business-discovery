package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the import-catalog renderer that seeds the frontend generation prompt with a
 * closed-world list of already-generated modules, so Flash imports what exists instead of inventing
 * modules the fixer must repair.
 */
class TypeScriptExportRegistryTest {

    private final Path workspace = Path.of("/ws");

    @Test
    void emptyRegistryRendersBlankAndReportsEmpty() {
        TypeScriptExportRegistry reg = new TypeScriptExportRegistry(workspace);

        assertThat(reg.isEmpty()).isTrue();
        assertThat(reg.toImportCatalog()).isEmpty();
    }

    @Test
    void groupsSymbolsByModuleUnderAliasSortedDeterministically() {
        TypeScriptExportRegistry reg = new TypeScriptExportRegistry(workspace);
        reg.register(workspace.resolve("frontend/src/context/AuthContext.tsx"),
                "export const AuthProvider = () => null;\nexport function useAuth() { return null; }");
        reg.register(workspace.resolve("frontend/src/services/bookingService.ts"),
                "export function getBookings() {}\nexport function createBooking() {}");
        reg.register(workspace.resolve("frontend/src/types/local/booking.ts"),
                "export interface BookingState { id: string; }");

        assertThat(reg.isEmpty()).isFalse();
        // Modules sorted by alias; symbols sorted within each module; @/ alias applied; no trailing newline.
        assertThat(reg.toImportCatalog()).isEqualTo(
                "@/context/AuthContext -> AuthProvider, useAuth\n"
                + "@/services/bookingService -> createBooking, getBookings\n"
                + "@/types/local/booking -> BookingState");
    }

    @Test
    void leavesPathWithoutFrontendSrcPrefixUnaliased() {
        TypeScriptExportRegistry reg = new TypeScriptExportRegistry(workspace);
        reg.register(workspace.resolve("shared/thing.ts"), "export type Thing = string;");

        assertThat(reg.toImportCatalog()).isEqualTo("shared/thing -> Thing");
    }

    @Test
    void includesReExportedSymbols() {
        TypeScriptExportRegistry reg = new TypeScriptExportRegistry(workspace);
        reg.register(workspace.resolve("frontend/src/components/index.ts"),
                "export { Card, Modal } from './ui';");

        assertThat(reg.toImportCatalog()).isEqualTo("@/components/index -> Card, Modal");
    }
}
