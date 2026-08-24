package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforcement Point B: over real generated code, every local import must resolve to a file on disk;
 * an unresolved one is a missing producer. Node-module imports and resolvable local imports are left
 * alone.
 */
class ImportClosureCheckerTest {

    @TempDir Path workspace;

    private Path src() { return workspace.resolve("frontend/src"); }

    private void write(String rel, String content) throws IOException {
        Path p = src().resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void flagsUnresolvedLocalImportsOnly() throws IOException {
        write("shell/SiteLayout.tsx", "export function SiteLayout() { return null; }");
        write("components/Bar.tsx", "export const Bar = 1;");
        write("pages/Home.tsx", String.join("\n",
                "import { SiteLayout } from '@/shell/SiteLayout';",     // resolves (@/ on disk)
                "import AdminLayout from '@/components/AdminLayout';",   // MISSING
                "import { Bar } from '../components/Bar';",              // resolves (relative)
                "import { Missing } from './Missing';",                 // MISSING (relative)
                "import React from 'react';",                           // node_modules — ignored
                "export default function Home() { return null; }"));

        List<ImportClosureChecker.Unresolved> unresolved = ImportClosureChecker.check(src());
        List<String> specs = unresolved.stream().map(ImportClosureChecker.Unresolved::specifier).toList();

        assertThat(specs).containsExactlyInAnyOrder("@/components/AdminLayout", "./Missing");
        assertThat(specs).doesNotContain("@/shell/SiteLayout", "../components/Bar", "react");
        assertThat(unresolved.stream()
                .filter(u -> u.specifier().equals("@/components/AdminLayout")).findFirst().orElseThrow()
                .importedBy()).contains("Home.tsx");
    }

    @Test
    void emptyWhenEverythingResolves() throws IOException {
        write("shell/SiteLayout.tsx", "export function SiteLayout() { return null; }");
        write("pages/Home.tsx", "import { SiteLayout } from '@/shell/SiteLayout';\nexport default function H(){return null;}");

        assertThat(ImportClosureChecker.check(src())).isEmpty();
    }

    @Test
    void writesAdvisoryReport() throws IOException {
        write("pages/Home.tsx", "import AdminLayout from '@/components/AdminLayout';\nexport default function H(){return null;}");

        List<ImportClosureChecker.Unresolved> unresolved = ImportClosureChecker.check(src());
        ImportClosureChecker.writeReport(workspace, ImportClosureChecker.render(unresolved));

        Path report = workspace.resolve("docs/IMPORT_CLOSURE_REPORT.md");
        assertThat(Files.exists(report)).isTrue();
        assertThat(Files.readString(report)).contains("@/components/AdminLayout").contains("Home.tsx");
    }
}
