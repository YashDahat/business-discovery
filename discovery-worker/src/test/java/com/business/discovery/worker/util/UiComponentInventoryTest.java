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
    void promptSectionListsBothSources() throws Exception {
        Path uiDir = frontend.resolve("src/components/ui");
        Files.createDirectories(uiDir);
        Files.writeString(uiDir.resolve("button.tsx"), "export const Button = () => <button/>\n");
        Files.writeString(frontend.resolve("package.json"), "{\"dependencies\":{}}");

        UiComponentInventory inv = UiComponentInventory.build(frontend);

        assertThat(inv.toPromptSection())
                .contains("@/components/ui/button: Button")
                .contains("DOES NOT EXIST");
    }
}
