package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforcement Point B backend repair: synthesizes a placeholder class for each javac
 * "cannot find symbol: class X" whose file was never generated (the OrderItemResponse case).
 * Target package = the referencer's import of X, else the referencer's own package.
 */
class BackendClassSynthesizerTest {

    @TempDir Path workspace;

    private Path srcJava() { return workspace.resolve("backend/src/main/java"); }

    private void writeJava(String rel, String content) throws IOException {
        Path p = srcJava().resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void synthesizesMissingClassInReferencerPackage() throws IOException {
        writeJava("com/foo/dto/OrderResponse.java",
                "package com.foo.dto;\npublic class OrderResponse { java.util.List<OrderItemResponse> items; }");
        // nested location (a Lombok builder) must still resolve to the top-level package
        String javac = "[ERROR] .../OrderResponse.java:[2,40] cannot find symbol\n"
                + "  symbol:   class OrderItemResponse\n"
                + "  location: class com.foo.dto.OrderResponse.OrderResponseBuilder\n";

        assertThat(BackendClassSynthesizer.synthesize(srcJava(), javac)).isEqualTo(1);

        Path created = srcJava().resolve("com/foo/dto/OrderItemResponse.java");
        assertThat(Files.exists(created)).isTrue();
        assertThat(Files.readString(created))
                .contains("package com.foo.dto;")
                .contains("public class OrderItemResponse {");
    }

    @Test
    void exceptionClassExtendsRuntimeException() throws IOException {
        writeJava("com/foo/service/OrderService.java", "package com.foo.service;\npublic class OrderService {}");
        String javac = "  symbol:   class OrderNotFoundException\n  location: class com.foo.service.OrderService\n";

        BackendClassSynthesizer.synthesize(srcJava(), javac);

        String src = Files.readString(srcJava().resolve("com/foo/service/OrderNotFoundException.java"));
        assertThat(src).contains("extends RuntimeException")
                       .contains("public OrderNotFoundException(String message)");
    }

    @Test
    void usesImportedPackageWhenReferencerImportsTheClass() throws IOException {
        writeJava("com/foo/dto/Thing.java",
                "package com.foo.dto;\nimport com.other.Bar;\npublic class Thing { Bar b; }");
        String javac = "  symbol:   class Bar\n  location: class com.foo.dto.Thing\n";

        BackendClassSynthesizer.synthesize(srcJava(), javac);

        assertThat(Files.exists(srcJava().resolve("com/other/Bar.java"))).isTrue();
        assertThat(Files.exists(srcJava().resolve("com/foo/dto/Bar.java"))).isFalse();
    }

    @Test
    void skipsWhenClassAlreadyExists() throws IOException {
        writeJava("com/foo/dto/OrderResponse.java", "package com.foo.dto;\npublic class OrderResponse {}");
        writeJava("com/foo/dto/OrderItemResponse.java", "package com.foo.dto;\npublic class OrderItemResponse { int id; }");
        String javac = "  symbol:   class OrderItemResponse\n  location: class com.foo.dto.OrderResponse\n";

        assertThat(BackendClassSynthesizer.synthesize(srcJava(), javac)).isZero();
        // existing content untouched
        assertThat(Files.readString(srcJava().resolve("com/foo/dto/OrderItemResponse.java"))).contains("int id;");
    }

    @Test
    void packageOfStopsAtFirstClassSegment() {
        assertThat(BackendClassSynthesizer.packageOf("com.foo.dto.OrderResponse")).isEqualTo("com.foo.dto");
        assertThat(BackendClassSynthesizer.packageOf("com.foo.dto.OrderResponse.OrderResponseBuilder"))
                .isEqualTo("com.foo.dto");
    }
}
