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

    // ── F7.1 — JDK/stdlib types: import, never fabricate a stub ────────────────

    @Test
    void jdkTypeIsImportedNotSynthesized() throws IOException {
        // worker-7fc94b76: `LocalTime` synthesized as `…dto.LocalTime` then collided with real usage.
        writeJava("com/foo/entity/ClassSchedule.java",
                "package com.foo.entity;\npublic class ClassSchedule { private LocalTime startTime; }");
        String javac = "  symbol:   class LocalTime\n  location: class com.foo.entity.ClassSchedule\n";

        int resolved = BackendClassSynthesizer.synthesize(srcJava(), javac);

        assertThat(resolved).isEqualTo(1);
        // No phantom class anywhere
        assertThat(Files.exists(srcJava().resolve("com/foo/entity/LocalTime.java"))).isFalse();
        // The referencer now imports the canonical JDK type
        assertThat(Files.readString(srcJava().resolve("com/foo/entity/ClassSchedule.java")))
                .contains("import java.time.LocalTime;");
    }

    @Test
    void jdkImportReconcilesAWrongPackageImport() throws IOException {
        // A prior stub left a bad import of the same simple name — reconcile it to the JDK FQN.
        writeJava("com/foo/entity/Invoice.java",
                "package com.foo.entity;\nimport com.foo.dto.BigDecimal;\npublic class Invoice { private BigDecimal total; }");
        String javac = "  symbol:   class BigDecimal\n  location: class com.foo.entity.Invoice\n";

        BackendClassSynthesizer.synthesize(srcJava(), javac);

        String src = Files.readString(srcJava().resolve("com/foo/entity/Invoice.java"));
        assertThat(src).contains("import java.math.BigDecimal;")
                       .doesNotContain("import com.foo.dto.BigDecimal;");
    }

    @Test
    void projectClassShadowsAJdkNameOfTheSameSimpleName() throws IOException {
        // A domain `Month` must NOT be replaced by java.time.Month — the project declaration wins.
        writeJava("com/foo/model/Month.java", "package com.foo.model;\npublic class Month { String label; }");
        writeJava("com/foo/service/CalendarService.java",
                "package com.foo.service;\npublic class CalendarService { Month m; }");
        String javac = "  symbol:   class Month\n  location: class com.foo.service.CalendarService\n";

        BackendClassSynthesizer.synthesize(srcJava(), javac);

        String src = Files.readString(srcJava().resolve("com/foo/service/CalendarService.java"));
        assertThat(src).contains("import com.foo.model.Month;")
                       .doesNotContain("import java.time.Month;");
    }

    // ── F7.2 — one canonical package, no dual-package duplicates ───────────────

    @Test
    void sameNameFromTwoPackagesResolvesToOneCanonicalPackage() throws IOException {
        // Referenced from a dto and a service; neither imports it. It must be synthesized ONCE (in the
        // dto/type package) and the service pointed at it by an import — never two stubs.
        writeJava("com/foo/dto/OrderResponse.java",
                "package com.foo.dto;\npublic class OrderResponse { private OrderLine line; }");
        writeJava("com/foo/service/OrderService.java",
                "package com.foo.service;\npublic class OrderService { OrderLine build() { return null; } }");
        String javac =
                "  symbol:   class OrderLine\n  location: class com.foo.dto.OrderResponse\n"
              + "  symbol:   class OrderLine\n  location: class com.foo.service.OrderService\n";

        BackendClassSynthesizer.synthesize(srcJava(), javac);

        // Exactly one placeholder, in the dto (type) package
        assertThat(Files.exists(srcJava().resolve("com/foo/dto/OrderLine.java"))).isTrue();
        assertThat(Files.exists(srcJava().resolve("com/foo/service/OrderLine.java"))).isFalse();
        // The far-package referencer imports the canonical FQN
        assertThat(Files.readString(srcJava().resolve("com/foo/service/OrderService.java")))
                .contains("import com.foo.dto.OrderLine;");
    }

    @Test
    void existingDeclarationIsTheCanonicalPackage() throws IOException {
        // An existing file wins the canonical package even if a nearer referencer package exists.
        writeJava("com/foo/model/Coupon.java", "package com.foo.model;\npublic class Coupon { String code; }");
        writeJava("com/foo/service/CartService.java",
                "package com.foo.service;\npublic class CartService { Coupon c; }");
        String javac = "  symbol:   class Coupon\n  location: class com.foo.service.CartService\n";

        BackendClassSynthesizer.synthesize(srcJava(), javac);

        // No new stub — Coupon already exists in com.foo.model; the service imports that one
        assertThat(Files.exists(srcJava().resolve("com/foo/service/Coupon.java"))).isFalse();
        assertThat(Files.readString(srcJava().resolve("com/foo/service/CartService.java")))
                .contains("import com.foo.model.Coupon;");
    }

    // ── F7.3 — kind inference: enum when used as a value set ───────────────────

    @Test
    void synthesizesEnumWhenUsedAsValueSet() throws IOException {
        // MenuCategory: @Enumerated field + constant access → enum, not an empty class.
        writeJava("com/foo/entity/MenuItem.java",
                "package com.foo.entity;\npublic class MenuItem {\n"
              + "  @Enumerated(EnumType.STRING)\n  private MenuCategory category;\n}");
        writeJava("com/foo/service/MenuService.java",
                "package com.foo.service;\npublic class MenuService {\n"
              + "  boolean isMain(MenuCategory c) { return c == MenuCategory.MAIN_COURSE || c == MenuCategory.DESSERT; }\n}");
        String javac = "  symbol:   class MenuCategory\n  location: class com.foo.entity.MenuItem\n";

        BackendClassSynthesizer.synthesize(srcJava(), javac);

        String src = Files.readString(srcJava().resolve("com/foo/entity/MenuCategory.java"));
        assertThat(src).contains("public enum MenuCategory")
                       .contains("MAIN_COURSE")
                       .contains("DESSERT")
                       .doesNotContain("public class MenuCategory");
    }

    @Test
    void synthesizesClassNotEnumForOrdinaryType() throws IOException {
        // No @Enumerated / values() / CONSTANT usage → stays a placeholder class (regression guard).
        writeJava("com/foo/dto/ShippingAddress.java",
                "package com.foo.dto;\npublic class ShippingAddress { private AddressDetails details; }");
        String javac = "  symbol:   class AddressDetails\n  location: class com.foo.dto.ShippingAddress\n";

        BackendClassSynthesizer.synthesize(srcJava(), javac);

        String src = Files.readString(srcJava().resolve("com/foo/dto/AddressDetails.java"));
        assertThat(src).contains("public class AddressDetails")
                       .doesNotContain("public enum");
    }

    @Test
    void constantsHolderIsNotMisreadAsEnum() throws IOException {
        // Constant access WITHOUT an enum signal (@Enumerated / values / valueOf) must stay a class,
        // so a `public static final` holder is not turned into an enum.
        writeJava("com/foo/config/Limits.java",
                "package com.foo.config;\npublic class Limits { int m = AppConstants.MAX_ITEMS + AppConstants.MIN_ITEMS; }");
        String javac = "  symbol:   class AppConstants\n  location: class com.foo.config.Limits\n";

        BackendClassSynthesizer.synthesize(srcJava(), javac);

        String src = Files.readString(srcJava().resolve("com/foo/config/AppConstants.java"));
        assertThat(src).contains("public class AppConstants")
                       .doesNotContain("public enum");
    }
}
