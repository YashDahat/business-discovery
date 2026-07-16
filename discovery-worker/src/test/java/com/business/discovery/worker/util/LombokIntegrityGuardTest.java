package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixtures are the real circuit-house rewrite (2026-07-12): TEMPLATED is what
 * JavaFileTemplater emitted for OrderDto, HAND_ROLLED is what the backend fix loop replaced
 * it with — the rewrite that compiled fine and then poisoned the derived TS types.
 */
class LombokIntegrityGuardTest {

    private static final String TEMPLATED = """
            package com.circuithouse.dto;

            import java.util.UUID;
            import java.util.List;
            import lombok.Data;
            import lombok.Builder;
            import lombok.NoArgsConstructor;
            import lombok.AllArgsConstructor;

            @Data
            @Builder
            @NoArgsConstructor
            @AllArgsConstructor
            public class OrderDto {
                private UUID id;
                private String customerName;
                private String customerPhone;
                private BigDecimal totalAmount;
            }
            """;

    private static final String HAND_ROLLED = """
            package com.circuithouse.dto;

            import java.util.UUID;
            import java.util.List;

            public class OrderDto {
                private UUID id;
                private String customerName;
                private String customerPhone;
                private BigDecimal totalAmount;

                public OrderDto() {}

                public UUID getId() { return id; }
                public void setId(UUID id) { this.id = id; }
                public String getCustomerName() { return customerName; }
                public void setCustomerName(String customerName) { this.customerName = customerName; }
                public String getCustomerPhone() { return customerPhone; }
                public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }
                public BigDecimal getTotalAmount() { return totalAmount; }
                public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

                public static class Builder {
                    private UUID id;
                    private String customerName, customerPhone;
                }
            }
            """;

    @Test
    void refusesTheCircuitHouseDeLombokRewrite() {
        String refusal = LombokIntegrityGuard.check("backend/.../OrderDto.java", TEMPLATED, HAND_ROLLED);

        assertThat(refusal).isNotNull();
        assertThat(refusal).startsWith("REFUSED:");
        assertThat(refusal).contains("@Data", "@Builder");
        // the refusal must teach the real fix, or the agent just finds another way to compile
        assertThat(refusal).contains("ANNOTATION PROCESSING IS BROKEN");
        assertThat(refusal).contains("annotationProcessorPaths");
    }

    @Test
    void refusesPartialDeAnnotationViaStrReplace() {
        // a str_replace that deletes only @Builder — the full-rewrite guard would miss this
        String patched = TEMPLATED.replace("@Builder\n", "");

        String refusal = LombokIntegrityGuard.check("backend/.../OrderDto.java", TEMPLATED, patched);

        // names exactly what was removed — @Data survived and must not be misreported
        // (the standing advice text mentions "@Data/@Builder", so assert on the removal list)
        assertThat(refusal).contains("it removes @Builder from");
        assertThat(refusal).doesNotContain("it removes @Data");
    }

    @Test
    void refusesHandExpansionEvenWhenAnnotationsAreLeftInPlace() {
        String annotatedButExpanded = TEMPLATED.replace("""
                    private BigDecimal totalAmount;
                }""", """
                    private BigDecimal totalAmount;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getCustomerName() { return customerName; }
                    public void setCustomerName(String v) { this.customerName = v; }
                }""");

        String refusal = LombokIntegrityGuard.check(
                "backend/.../OrderDto.java", TEMPLATED, annotatedButExpanded);

        assertThat(refusal).contains("hand-written getters/setters");
    }

    // ── the guard must not become a straitjacket ──────────────────────────

    @Test
    void allowsAddingAField() {
        String withField = TEMPLATED.replace("    private BigDecimal totalAmount;",
                "    private BigDecimal totalAmount;\n    private String customerEmail;");

        assertThat(LombokIntegrityGuard.check("backend/.../OrderDto.java", TEMPLATED, withField))
                .isNull();
    }

    @Test
    void allowsRetypingAField() {
        String retyped = TEMPLATED.replace("private UUID id;", "private Long id;");

        assertThat(LombokIntegrityGuard.check("backend/.../OrderDto.java", TEMPLATED, retyped))
                .isNull();
    }

    @Test
    void allowsOneComputedAccessorOnAnAnnotatedClass() {
        String withComputed = TEMPLATED.replace("""
                    private BigDecimal totalAmount;
                }""", """
                    private BigDecimal totalAmount;

                    public String getDisplayName() { return customerName + " (" + id + ")"; }
                }""");

        assertThat(LombokIntegrityGuard.check("backend/.../OrderDto.java", TEMPLATED, withComputed))
                .isNull();
    }

    @Test
    void ignoresClassesThatNeverUsedLombok() {
        String plain = "public class Foo { private int x; }";
        String expanded = """
                public class Foo {
                    private int x;
                    public int getX() { return x; }
                    public void setX(int x) { this.x = x; }
                    public int getY() { return 0; }
                    public void setY(int y) {}
                }
                """;

        assertThat(LombokIntegrityGuard.check("backend/.../Foo.java", plain, expanded)).isNull();
    }

    @Test
    void ignoresNonJavaAndNewFiles() {
        assertThat(LombokIntegrityGuard.check("frontend/src/x.ts", TEMPLATED, HAND_ROLLED)).isNull();
        assertThat(LombokIntegrityGuard.check("backend/.../OrderDto.java", null, HAND_ROLLED)).isNull();
    }
}
