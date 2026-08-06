package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforcement Point B repair (Change 2 synthesis): writes placeholder modules that close the missing
 * imports found by {@link ImportClosureChecker}. Verifies the export shape matches the import form and
 * that a layout placeholder passes children through (never silently drops page content).
 */
class MissingModuleSynthesizerTest {

    @TempDir Path workspace;

    private Path src() { return workspace.resolve("frontend/src"); }

    private void write(String rel, String content) throws IOException {
        Path p = src().resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void synthesizesDefaultAndNamedPlaceholdersThatCloseTheImports() throws IOException {
        write("pages/Home.tsx", String.join("\n",
                "import AdminLayout from '@/components/AdminLayout';",   // default → missing
                "import { useEvents } from '@/hooks/useEvents';",         // named → missing
                "export default function Home() { return null; }"));

        List<ImportClosureChecker.Unresolved> unresolved = ImportClosureChecker.check(src());
        assertThat(unresolved).hasSize(2);

        int written = MissingModuleSynthesizer.synthesize(src(), unresolved);
        assertThat(written).isEqualTo(2);

        // default-imported module gets a default export
        String adminLayout = Files.readString(src().resolve("components/AdminLayout.tsx"));
        assertThat(adminLayout).contains("export default __stub");
        // named-imported module gets the named export (value + type)
        String useEvents = Files.readString(src().resolve("hooks/useEvents.tsx"));
        assertThat(useEvents).contains("export const useEvents: any = __stub")
                             .contains("export type useEvents = any");

        // the invariant now holds — re-check finds nothing
        assertThat(ImportClosureChecker.check(src())).isEmpty();
    }

    @Test
    void layoutPlaceholderPassesChildrenThrough() throws IOException {
        write("pages/AdminPage.tsx",
                "import AdminLayout from '@/shell/AdminLayout';\nexport default function P(){return null;}");

        MissingModuleSynthesizer.synthesize(src(), ImportClosureChecker.check(src()));

        String stub = Files.readString(src().resolve("shell/AdminLayout.tsx"));
        // renders children rather than null, so a synthesized layout doesn't blank the page
        assertThat(stub).contains("props.children");
    }

    @Test
    void skipsWhenTargetAlreadyResolves() throws IOException {
        write("components/Card.tsx", "export const Card = 1;");
        write("pages/Home.tsx", "import { Card } from '@/components/Card';\nexport default function H(){return null;}");

        // nothing unresolved → nothing synthesized
        assertThat(MissingModuleSynthesizer.synthesize(src(), ImportClosureChecker.check(src()))).isZero();
    }
}
