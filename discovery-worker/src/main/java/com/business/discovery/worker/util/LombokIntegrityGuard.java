package com.business.discovery.worker.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stops the fix agent from hand-expanding Lombok boilerplate.
 *
 * Circuit-house, 2026-07-12: the backend fix loop was seeded with ONE error (a malformed
 * razorpay import — {@code PaymentService.java:[12,26] ';' expected}). It fixed that in
 * round 2, then went on to rewrite 17 files nobody asked it to touch, stripping
 * {@code @Data @Builder} off every DTO produced by {@link JavaFileTemplater} and
 * hand-writing the accessors back: 243 getters/setters, ~34K chars, ~9.7K Sonnet output
 * tokens (~$0.15) to replace code the templater emitted for free. The backend then
 * compiled, so the gate went green and nothing flagged it — and one artifact of that
 * expansion (a nested {@code Builder} carrying {@code private String a, b, c;}) was
 * misparsed by ApiInventory into 85 duplicate-field TS errors that killed the frontend
 * build four nodes later. One syntax error, seventeen rewritten files, a dead run.
 *
 * The pull is structural, not a lapse: broken annotation processing presents as a wall of
 * "cannot find symbol: getX()", and hand-writing the getters makes every one of them
 * disappear. The agent is scored on "does it compile" with no penalty for collateral
 * damage, so the wrong fix is also the locally optimal one. Hence a hard guard rather
 * than a prompt hint.
 *
 * Guards the INVARIANT (Lombok stays), not the provenance, so it needs no templated-path
 * registry and also protects hand-authored Lombok files. Deliberately narrow: adding a
 * field, changing a type, or adding one computed accessor to an annotated class all still
 * pass. Only de-annotation and bulk boilerplate expansion are refused.
 */
public final class LombokIntegrityGuard {

    /** Annotations whose removal means "someone is about to write boilerplate by hand". */
    private static final List<String> ACCESSOR_ANNOTATIONS = List.of(
            "@Data", "@Getter", "@Setter", "@Value", "@Builder",
            "@NoArgsConstructor", "@AllArgsConstructor", "@RequiredArgsConstructor");

    private static final Pattern ACCESSOR_METHOD = Pattern.compile(
            "public\\s+(?:[\\w<>,\\[\\]\\s.]+\\s+get[A-Z]\\w*|boolean\\s+is[A-Z]\\w*|void\\s+set[A-Z]\\w*)\\s*\\(");

    /**
     * One computed accessor on an annotated class is legitimate (a derived property).
     * Three is someone transcribing fields. The circuit-house rewrites added 8-16 each.
     */
    private static final int HAND_EXPANSION_THRESHOLD = 3;

    private LombokIntegrityGuard() {}

    /**
     * @param existing   current on-disk content, or null when creating a new file
     * @param proposed   content the agent wants to write (for str_replace, the post-edit result)
     * @return refusal message when the mutation would strip Lombok or hand-expand its
     *         boilerplate, else null
     */
    public static String check(String relativePath, String existing, String proposed) {
        if (existing == null || proposed == null) return null;
        if (!relativePath.endsWith(".java")) return null;
        if (!usesLombok(existing)) return null;

        List<String> removed = new ArrayList<>();
        for (String annotation : ACCESSOR_ANNOTATIONS) {
            if (contains(existing, annotation) && !contains(proposed, annotation)) {
                removed.add(annotation);
            }
        }
        if (!removed.isEmpty()) {
            return refusal(relativePath,
                    "it removes " + String.join(", ", removed) + " from a Lombok-annotated class");
        }

        int added = countAccessors(proposed) - countAccessors(existing);
        if (added >= HAND_EXPANSION_THRESHOLD) {
            return refusal(relativePath,
                    "it adds " + added + " hand-written getters/setters to a class whose accessors "
                    + "Lombok already generates");
        }
        return null;
    }

    private static String refusal(String relativePath, String because) {
        return "REFUSED: " + relativePath + " — " + because + ".\n"
                + "Lombok generates these accessors at compile time; hand-writing them is never the "
                + "fix. If you are seeing 'cannot find symbol: getX()/setX()' across several "
                + "annotated classes, ANNOTATION PROCESSING IS BROKEN — that is one build problem, "
                + "not N class problems. Fix the build: check that lombok is in backend/pom.xml AND "
                + "listed in maven-compiler-plugin <annotationProcessorPaths>, and that the class "
                + "imports what it annotates. Never delete @Data/@Builder to make symbols resolve — "
                + "it compiles, and it silently corrupts the API types derived from these DTOs "
                + "downstream. To add or retype a FIELD, edit the field list only and leave the "
                + "annotations in place.";
    }

    /** True when the class relies on Lombok to synthesise its accessors. */
    static boolean usesLombok(String content) {
        return content.contains("import lombok")
                || ACCESSOR_ANNOTATIONS.stream().anyMatch(a -> contains(content, a));
    }

    /** Word-boundary match so @Value does not match @ValueSource, @Data not @Database. */
    private static boolean contains(String content, String annotation) {
        return Pattern.compile(Pattern.quote(annotation) + "\\b").matcher(content).find();
    }

    static int countAccessors(String content) {
        Matcher m = ACCESSOR_METHOD.matcher(content);
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}
