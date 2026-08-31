package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AdminLayoutWrapperPatcherTest {

    @TempDir
    Path frontendSrc;

    private Path page(String rel, String body) throws Exception {
        Path p = frontendSrc.resolve("pages").resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
        return p;
    }

    private static final String WRAPPED = """
        import { useState } from 'react';
        import AdminLayout from '@/components/AdminLayout';
        import { BookingTable } from '@/components/booking/BookingTable';

        export default function AdminBookingsPage() {
          if (loading) {
            return (
              <AdminLayout>
                <div>Loading...</div>
              </AdminLayout>
            );
          }
          return (
            <AdminLayout>
              <BookingTable />
            </AdminLayout>
          );
        }
        """;

    @Test
    void unwrapsEveryBranchAndRemovesImport() throws Exception {
        Path p = page("AdminBookingsPage.tsx", WRAPPED);

        assertThat(AdminLayoutWrapperPatcher.fix(frontendSrc)).isTrue();

        String out = Files.readString(p);
        assertThat(out).doesNotContain("<AdminLayout>").doesNotContain("</AdminLayout>");
        assertThat(out).doesNotContain("import AdminLayout from '@/components/AdminLayout'");
        assertThat(out).contains("<>").contains("</>").contains("<BookingTable />");
        // both return branches unwrapped (2 fragment pairs)
        assertThat(out.split("<>", -1).length - 1).isEqualTo(2);
    }

    @Test
    void idempotentAndLeavesCleanPagesUntouched() throws Exception {
        page("AdminBookingsPage.tsx", WRAPPED);
        page("HomePage.tsx", "export default function HomePage() { return <div>home</div>; }");

        assertThat(AdminLayoutWrapperPatcher.fix(frontendSrc)).isTrue();   // first pass changes the admin page
        assertThat(AdminLayoutWrapperPatcher.fix(frontendSrc)).isFalse();  // second pass is a no-op
    }

    @Test
    void leavesSelfClosingAdminLayoutAlone() throws Exception {
        // AppRoutes-style usage (not under pages, but assert the pattern is skipped anyway)
        Path p = page("AdminShellPage.tsx", """
            import AdminLayout from '@/components/AdminLayout';
            export default function AdminShellPage() { return <AdminLayout />; }
            """);

        assertThat(AdminLayoutWrapperPatcher.fix(frontendSrc)).isFalse();
        assertThat(Files.readString(p)).contains("<AdminLayout />");   // untouched
    }

    @Test
    void handlesAttributesOnWrapperTag() {
        String in = "return (<AdminLayout className=\"x\"><Child /></AdminLayout>);";
        String out = AdminLayoutWrapperPatcher.rewrite(in);
        assertThat(out).isEqualTo("return (<><Child /></>);");
    }
}
