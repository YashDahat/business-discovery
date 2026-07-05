package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JsxTypeImportFixerTest {

    @TempDir
    Path src;

    @Test
    void addsImportToFilesUsingJsxNamespace() throws Exception {
        Path file = src.resolve("components/ProtectedRoute.tsx");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "export function Guard(): JSX.Element { return <div/> }\n");

        boolean changed = JsxTypeImportFixer.fix(src);

        assertThat(changed).isTrue();
        assertThat(Files.readString(file))
                .startsWith("import type { JSX } from 'react';\n")
                .contains("JSX.Element");
    }

    @Test
    void leavesFilesWithoutJsxNamespaceUntouched() throws Exception {
        Path file = src.resolve("App.tsx");
        Files.writeString(file, "export default function App() { return <div/> }\n");

        boolean changed = JsxTypeImportFixer.fix(src);

        assertThat(changed).isFalse();
        assertThat(Files.readString(file)).doesNotContain("import type { JSX }");
    }

    @Test
    void doesNotDuplicateExistingImport() throws Exception {
        Path file = src.resolve("Header.tsx");
        String content = "import type { JSX } from 'react';\nexport const H = (): JSX.Element => <div/>\n";
        Files.writeString(file, content);

        boolean changed = JsxTypeImportFixer.fix(src);

        assertThat(changed).isFalse();
        assertThat(Files.readString(file)).isEqualTo(content);
    }
}
