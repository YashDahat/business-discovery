package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TruncationDetectorTest {

    // The yeti-the-himalayan-kitchen signature: AdminMenuPage.tsx was cut mid-JSX on a dangling
    // '<'. Brace counting alone caught this only by luck of where the cut landed.
    private static final String YETI_TRAILING_LT = """
            export function AdminMenuPage() {
              return (
                <div className="p-4">
                  <""";

    // Cut inside a nested block — braces never close.
    private static final String BRACE_TRUNCATED = """
            public class OrderService {
                public void placeOrder(Order order) {
                    if (order.isValid()) {
            """;

    private static final String COMPLETE_TSX = """
            export function MenuPage() {
              return <div>Menu</div>;
            }
            """;

    private static final String COMPLETE_JAVA = """
            public class OrderService {
                public void placeOrder(Order order) {
                    repository.save(order);
                }
            }
            """;

    @Test
    void flagsTrailingOpenAngleBracket() {
        assertThat(TruncationDetector.looksTruncated(YETI_TRAILING_LT)).isTrue();
    }

    @Test
    void flagsUnbalancedBraces() {
        assertThat(TruncationDetector.looksTruncated(BRACE_TRUNCATED)).isTrue();
        assertThat(TruncationDetector.isBraceTruncated(BRACE_TRUNCATED)).isTrue();
    }

    @Test
    void flagsNullAndBlank() {
        assertThat(TruncationDetector.looksTruncated(null)).isTrue();
        assertThat(TruncationDetector.looksTruncated("")).isTrue();
        assertThat(TruncationDetector.looksTruncated("   \n\t ")).isTrue();
    }

    @Test
    void flagsEveryMidExpressionTrailingChar() {
        for (String tail : new String[]{"<", ",", "(", "[", "=", "&", "|", ":", "."}) {
            assertThat(TruncationDetector.looksTruncated("const x = y" + tail))
                    .as("trailing %s should read as truncated", tail)
                    .isTrue();
        }
    }

    @Test
    void acceptsCompleteFiles() {
        assertThat(TruncationDetector.looksTruncated(COMPLETE_TSX)).isFalse();
        assertThat(TruncationDetector.looksTruncated(COMPLETE_JAVA)).isFalse();
    }

    // Guards against false positives that would burn a needless regeneration: trailing whitespace
    // and a closing brace/semicolon are how every complete file actually ends.
    @Test
    void toleratesTrailingWhitespace() {
        assertThat(TruncationDetector.looksTruncated(COMPLETE_JAVA + "\n\n  ")).isFalse();
    }

    // Excess closing braces mean malformed, not truncated — the fix-LLM can still repair it,
    // so it must not be discarded as a partial.
    @Test
    void doesNotFlagExcessClosingBraces() {
        assertThat(TruncationDetector.isBraceTruncated("class A { }}")).isFalse();
    }
}
