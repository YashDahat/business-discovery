package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NodeModuleExportRegistryTest {

    @TempDir
    Path frontend;

    private void pkg(String name, String typesRel, String dts) throws IOException {
        Path dir = frontend.resolve("node_modules").resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("package.json"), "{\"name\":\"" + name + "\",\"types\":\"" + typesRel + "\"}");
        Path d = dir.resolve(typesRel);
        Files.createDirectories(d.getParent());
        Files.writeString(d, dts);
    }

    @Test
    void resolvesNamedExportsFromScopedDeps() throws Exception {
        Files.writeString(frontend.resolve("package.json"),
                "{\"dependencies\":{\"lucide-react\":\"^1\",\"@tanstack/react-query\":\"^5\"}}");
        // lucide-style: one big brace export with aliases
        pkg("lucide-react", "index.d.ts", "export { ShoppingCart, ShoppingCart as ShoppingCartIcon, Menu };\n");
        // tanstack-style: aliased re-export + a declare
        pkg("@tanstack/react-query", "index.d.ts",
                "export { useMutation_1 as useMutation } from './x';\nexport declare function useQuery(): void;\n");

        NodeModuleExportRegistry r = NodeModuleExportRegistry.build(frontend);

        assertThat(r.packageFor("ShoppingCart")).contains("lucide-react");
        assertThat(r.packageFor("ShoppingCartIcon")).contains("lucide-react");
        assertThat(r.packageFor("Menu")).contains("lucide-react");
        assertThat(r.packageFor("useMutation")).contains("@tanstack/react-query");
        assertThat(r.packageFor("useQuery")).contains("@tanstack/react-query");
    }

    @Test
    void dropsSymbolsExportedByMoreThanOnePackage() throws Exception {
        Files.writeString(frontend.resolve("package.json"), "{\"dependencies\":{\"a\":\"1\",\"b\":\"1\"}}");
        pkg("a", "index.d.ts", "export { Table };\n");
        pkg("b", "index.d.ts", "export { Table };\n");

        assertThat(NodeModuleExportRegistry.build(frontend).packageFor("Table")).isEmpty();
    }

    @Test
    void restrictsToDeclaredDependencyScope() throws Exception {
        // present in node_modules but NOT declared in package.json dependencies → out of scope
        Files.writeString(frontend.resolve("package.json"), "{\"dependencies\":{}}");
        pkg("sneaky", "index.d.ts", "export { Foo };\n");

        NodeModuleExportRegistry r = NodeModuleExportRegistry.build(frontend);
        assertThat(r.packageFor("Foo")).isEmpty();
        assertThat(r.isEmpty()).isTrue();
    }

    @Test
    void ignoresDefaultExports() throws Exception {
        Files.writeString(frontend.resolve("package.json"), "{\"dependencies\":{\"p\":\"1\"}}");
        pkg("p", "index.d.ts", "declare const x: number;\nexport default x;\n");

        assertThat(NodeModuleExportRegistry.build(frontend).isEmpty()).isTrue();
    }

    @Test
    void emptyWhenNoPackageJson() {
        assertThat(NodeModuleExportRegistry.build(frontend).isEmpty()).isTrue();
    }
}
