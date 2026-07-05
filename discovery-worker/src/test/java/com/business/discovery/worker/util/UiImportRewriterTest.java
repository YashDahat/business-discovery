package com.business.discovery.worker.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UiImportRewriterTest {

    @TempDir
    Path frontend;

    private Path src;

    @BeforeEach
    void setUp() throws Exception {
        src = frontend.resolve("src");
        Path uiDir = src.resolve("components/ui");
        Files.createDirectories(uiDir);
        // shadcn dialog file exporting the compositions Radix doesn't have
        Files.writeString(uiDir.resolve("dialog.tsx"),
                "export { Dialog, DialogHeader, DialogFooter, DialogContent, DialogTitle }\n");
        Files.writeString(uiDir.resolve("button.tsx"), "export const Button = () => <button/>\n");
        // fake installed radix package so the inventory knows its true exports
        Path pkgDir = frontend.resolve("node_modules/@radix-ui/react-dialog");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("package.json"),
                "{\"name\":\"@radix-ui/react-dialog\",\"main\":\"index.js\"}");
        Files.writeString(pkgDir.resolve("index.js"),
                "module.exports = { Root: 1, Trigger: 1, Content: 1, Title: 1, Dialog: 1, DialogContent: 1, DialogTitle: 1 };");
        Files.writeString(frontend.resolve("package.json"),
                "{\"dependencies\":{\"@radix-ui/react-dialog\":\"^1.0.0\"}}");
    }

    private UiComponentInventory inventory() {
        return UiComponentInventory.build(frontend);
    }

    @Test
    void relocatesPhantomRadixImportsToShadcn() throws Exception {
        Path page = src.resolve("pages/AdminPage.tsx");
        Files.createDirectories(page.getParent());
        Files.writeString(page, """
                import { Dialog, DialogHeader, DialogFooter, DialogContent } from '@radix-ui/react-dialog';
                export default function AdminPage() { return <Dialog/> }
                """);

        boolean changed = UiImportRewriter.fix(src, inventory());

        assertThat(changed).isTrue();
        String result = Files.readString(page);
        // real radix exports stay; phantom compositions move to the shadcn file that has them
        assertThat(result).contains("import { Dialog, DialogContent } from '@radix-ui/react-dialog';");
        assertThat(result).contains("import { DialogHeader, DialogFooter } from '@/components/ui/dialog';");
    }

    @Test
    void canonicalizesUiPathCasing() throws Exception {
        Path page = src.resolve("pages/Home.tsx");
        Files.createDirectories(page.getParent());
        Files.writeString(page, "import { Button } from '@/components/ui/Button';\n");

        boolean changed = UiImportRewriter.fix(src, inventory());

        assertThat(changed).isTrue();
        assertThat(Files.readString(page)).contains("from '@/components/ui/button'");
    }

    @Test
    void leavesValidImportsAndUnknownNamesAlone() throws Exception {
        Path page = src.resolve("pages/Valid.tsx");
        Files.createDirectories(page.getParent());
        String content = """
                import { Root, Trigger } from '@radix-ui/react-dialog';
                import { TotallyUnknownThing } from '@radix-ui/react-dialog';
                import { Button } from '@/components/ui/button';
                """;
        Files.writeString(page, content);

        UiImportRewriter.fix(src, inventory());

        String result = Files.readString(page);
        assertThat(result).contains("import { Root, Trigger } from '@radix-ui/react-dialog';");
        // unknown everywhere — left for tsc/agent, not guessed at
        assertThat(result).contains("TotallyUnknownThing");
        assertThat(result).contains("from '@/components/ui/button'");
    }

    @Test
    void mergesIntoExistingShadcnImport() throws Exception {
        Path page = src.resolve("pages/Merge.tsx");
        Files.createDirectories(page.getParent());
        Files.writeString(page, """
                import { DialogHeader } from '@radix-ui/react-dialog';
                import { DialogTitle } from '@/components/ui/dialog';
                """);

        UiImportRewriter.fix(src, inventory());

        String result = Files.readString(page);
        assertThat(result).contains("import { DialogTitle, DialogHeader } from '@/components/ui/dialog';");
        assertThat(result).doesNotContain("@radix-ui/react-dialog"); // emptied import removed
    }
}
