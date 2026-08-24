package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the deterministic default↔named import correction wired into FrontendValidationNode's
 * pre-pass. Fixtures mirror the real farmaaish-restaurant defects: pages default-importing a
 * named-only layout (TS2613) and named-importing a default-only one (TS2614) — provably-correct
 * rewrites that would otherwise burn ErrorFixAgent rounds one file at a time.
 */
class TypeScriptImportFixerTest {

    @TempDir Path workspace;

    private Path src() {
        return workspace.resolve("frontend/src");
    }

    private void write(String rel, String content) throws IOException {
        Path p = src().resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void rewritesDefaultImportToNamedWhenTargetHasNoDefault() throws IOException {
        // AdminLayout is a NAMED export; the page default-imports it → TS2613.
        write("shell/AdminLayout.tsx", "export function AdminLayout() { return null; }\n");
        write("pages/AdminDashboardPage.tsx",
                "import AdminLayout from '@/shell/AdminLayout';\nexport default function Page() { return null; }\n");

        boolean changed = TypeScriptImportFixer.fixAll(src(), workspace,
                TypeScriptExportRegistry.buildFromDisk(src(), workspace));

        assertThat(changed).isTrue();
        assertThat(Files.readString(src().resolve("pages/AdminDashboardPage.tsx")))
                .contains("import { AdminLayout } from '@/shell/AdminLayout';")
                .doesNotContain("import AdminLayout from");
    }

    @Test
    void rewritesNamedImportToDefaultWhenTargetHasDefault() throws IOException {
        // Layout is a DEFAULT export; the page named-imports it → TS2614.
        write("components/Layout.tsx", "export default function Layout() { return null; }\n");
        write("pages/HomePage.tsx",
                "import { Layout } from '@/components/Layout';\nexport default function Home() { return null; }\n");

        TypeScriptImportFixer.fixAll(src(), workspace,
                TypeScriptExportRegistry.buildFromDisk(src(), workspace));

        assertThat(Files.readString(src().resolve("pages/HomePage.tsx")))
                .contains("import Layout from '@/components/Layout';")
                .doesNotContain("import { Layout }");
    }

    @Test
    void leavesCorrectImportsUntouched() throws IOException {
        write("shell/AdminLayout.tsx", "export function AdminLayout() { return null; }\n");
        String good = "import { AdminLayout } from '@/shell/AdminLayout';\nexport default function P() { return null; }\n";
        write("pages/OkPage.tsx", good);

        boolean changed = TypeScriptImportFixer.fixAll(src(), workspace,
                TypeScriptExportRegistry.buildFromDisk(src(), workspace));

        assertThat(changed).isFalse();
        assertThat(Files.readString(src().resolve("pages/OkPage.tsx"))).isEqualTo(good);
    }

    @Test
    void doesNotRewriteDefaultImportWhenTargetActuallyHasDefault() throws IOException {
        // Valid default import must be preserved even though a same-named NAMED export exists elsewhere.
        write("components/Widget.tsx", "export default function Widget() { return null; }\n");
        write("util/Widget.ts", "export const Widget = 1;\n"); // registry also sees a named Widget
        String valid = "import Widget from '@/components/Widget';\nexport default function P() { return null; }\n";
        write("pages/WidgetPage.tsx", valid);

        TypeScriptImportFixer.fixAll(src(), workspace,
                TypeScriptExportRegistry.buildFromDisk(src(), workspace));

        assertThat(Files.readString(src().resolve("pages/WidgetPage.tsx"))).isEqualTo(valid);
    }
}
