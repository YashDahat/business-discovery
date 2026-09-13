package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UiComponentInventoryTest {

    @TempDir
    Path frontend;

    @Test
    void enumeratesRadixExportsViaNodeFromFakePackage() throws Exception {
        // Fake CJS radix package — node require() resolves it like the real thing
        Path pkgDir = frontend.resolve("node_modules/@radix-ui/react-dialog");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("package.json"),
                "{\"name\":\"@radix-ui/react-dialog\",\"main\":\"index.js\"}");
        Files.writeString(pkgDir.resolve("index.js"),
                "module.exports = { Root: 1, Trigger: 1, Title: 1, createDialogScope: 1 };");
        Files.writeString(frontend.resolve("package.json"),
                "{\"dependencies\":{\"@radix-ui/react-dialog\":\"^1.0.0\",\"react\":\"^19.0.0\"}}");

        Map<String, List<String>> radix = UiComponentInventory.enumerateRadixExports(frontend);

        assertThat(radix).containsKey("@radix-ui/react-dialog");
        assertThat(radix.get("@radix-ui/react-dialog"))
                .contains("Root", "Trigger", "Title")
                .doesNotContain("createDialogScope"); // internals filtered
    }

    @Test
    void parsesShadcnUiFileExports() throws Exception {
        Path uiDir = frontend.resolve("src/components/ui");
        Files.createDirectories(uiDir);
        Files.writeString(uiDir.resolve("dialog.tsx"), """
                const DialogHeader = ({...}) => <div/>
                function DialogFooter() { return <div/> }
                export { Dialog, DialogHeader, DialogFooter, DialogContent as Content }
                export const DialogTitle = () => <h2/>
                """);

        Map<String, List<String>> ui = UiComponentInventory.parseShadcnUiExports(uiDir);

        assertThat(ui).containsKey("dialog");
        assertThat(ui.get("dialog"))
                .contains("Dialog", "DialogHeader", "DialogFooter", "DialogTitle", "Content");
    }

    @Test
    void promptSectionIsShadcnOnlyAndNeverOffersRawRadix() throws Exception {
        Path uiDir = frontend.resolve("src/components/ui");
        Files.createDirectories(uiDir);
        Files.writeString(uiDir.resolve("button.tsx"), "export const Button = () => <button/>\n");
        // A radix dependency IS installed — it must still be absent from the prompt (task 14:
        // shadcn-only; radix stays parsed for JSON + the import rewriter but is never offered).
        Path pkgDir = frontend.resolve("node_modules/@radix-ui/react-dialog");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("index.js"), "exports.Root = {};\nexports.Trigger = {};\n");
        Files.writeString(pkgDir.resolve("package.json"),
                "{\"name\":\"@radix-ui/react-dialog\",\"main\":\"index.js\"}");
        Files.writeString(frontend.resolve("package.json"),
                "{\"dependencies\":{\"@radix-ui/react-dialog\":\"^1.0.0\"}}");

        UiComponentInventory inv = UiComponentInventory.build(frontend);
        String section = inv.toPromptSection();

        assertThat(section)
                .contains("@/components/ui/button: Button")
                .contains("DOES NOT EXIST")
                .contains("never import from @radix-ui/* directly");
        // the installed radix PACKAGE is never listed as an importable source
        assertThat(section).doesNotContain("@radix-ui/react-dialog");
        assertThat(section).doesNotContain("FALLBACK");
        // but it is still parsed for the non-prompt consumers (JSON / import rewriter)
        assertThat(inv.radixExports()).containsKey("@radix-ui/react-dialog");
    }
}
