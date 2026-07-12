package com.business.discovery.worker.util;

/**
 * Detects LLM output that was cut off mid-generation (Flash hit its output-token ceiling).
 * Shared by the backend and frontend generators so the two heuristics cannot drift apart —
 * both consume the same Gemini truncation signature.
 */
public final class TruncationDetector {

    private TruncationDetector() {}

    // Open-brace count > close-brace count means the LLM stopped mid-output (token limit hit).
    public static boolean isBraceTruncated(String content) {
        if (content == null || content.isBlank()) return true;
        int depth = 0;
        for (char c : content.toCharArray()) {
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return depth > 0;
    }

    /**
     * Truncation heuristic broader than brace balance alone: a file cut mid-token never ends on a
     * clean terminator. Kept conservative to avoid false positives that would trigger needless
     * regeneration — only unbalanced braces, blank output, or a trailing character that is
     * unambiguously mid-expression. This catches the yeti signature (AdminMenuPage.tsx ended on a
     * dangling '<') that brace counting caught only by luck of where the cut landed.
     *
     * <p>The trailing-character set is valid for both TypeScript and Java: no complete file in
     * either language ends on '<' (JSX open / generic), ',', '(', '[', '=', '&amp;', '|', ':' or '.'.
     */
    public static boolean looksTruncated(String content) {
        if (content == null || content.isBlank()) return true;
        if (isBraceTruncated(content)) return true;
        String trimmed = content.stripTrailing();
        if (trimmed.isEmpty()) return true;
        char last = trimmed.charAt(trimmed.length() - 1);
        return last == '<' || last == ',' || last == '(' || last == '['
                || last == '=' || last == '&' || last == '|' || last == ':' || last == '.';
    }
}
