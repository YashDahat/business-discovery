package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Deterministic net for issue #7 (docs/frontend-issue-solution-plan-9312afa6.md): react-day-picker
 * v10 <b>removed the {@code initialFocus} prop</b>, but the model emits the pre-v10 shadcn idiom
 * {@code <Calendar initialFocus … />} (the classic shadcn Calendar example shipped it). Because the
 * foundation {@code Calendar} wrapper types its props as {@code React.ComponentProps<typeof DayPicker>},
 * a page passing {@code initialFocus} to {@code <Calendar>} fails to compile with
 * {@code TS2322: Property 'initialFocus' does not exist on type …DayPickerProps…}. The foundation fix
 * only covers {@code initialFocus} used <em>inside</em> the wrapper — page-level usage is uncovered.
 *
 * <p>v10 replaces it with {@code autoFocus?: boolean} (the documented accessibility prop), so the fix is
 * a provable 1:1 rewrite {@code initialFocus → autoFocus} — verified against react-day-picker@10.0.1
 * types with {@code tsc}. This pass rewrites the {@code initialFocus} prop only inside a
 * {@code <Calendar …>} / {@code <DayPicker …>} opening tag (never {@code <CalendarIcon>}/{@code <CalendarDays>},
 * never a same-named variable elsewhere), preserving the focus-on-open UX the agent otherwise dropped.
 * Zero LLM; idempotent. Same family as {@link SiteConfigAccessPatcher} / {@code LucideIconValidator}.
 */
@Slf4j
public final class DayPickerPropPatcher {

    // A <Calendar / <DayPicker opening tag start — the negative lookahead excludes <CalendarIcon, <CalendarDays, etc.
    private static final Pattern TAG_START = Pattern.compile("<(?:Calendar|DayPicker)(?![A-Za-z0-9_])");
    // The removed prop as a whole JSX attribute token.
    private static final Pattern INITIAL_FOCUS = Pattern.compile("\\binitialFocus\\b");

    private DayPickerPropPatcher() {}

    /** Returns true if any file's {@code initialFocus} prop was rewritten to {@code autoFocus}. */
    public static boolean fix(Path frontendSrc) {
        if (!Files.exists(frontendSrc)) return false;
        boolean[] changed = {false};
        try (Stream<Path> files = Files.walk(frontendSrc)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".tsx") || p.toString().endsWith(".ts"))
                 .filter(p -> !p.toString().contains("node_modules"))
                 .filter(p -> !p.toString().contains("/components/ui/")) // never rewrite the foundation wrapper
                 .forEach(p -> {
                     try {
                         String content = Files.readString(p);
                         if (content.stripLeading().startsWith("// GENERATED")) return; // fenced/derived
                         String rewritten = rewrite(content);
                         if (!rewritten.equals(content)) {
                             Files.writeString(p, rewritten);
                             changed[0] = true;
                             log.info("[DayPickerPropPatcher] Rewrote initialFocus -> autoFocus on <Calendar>/<DayPicker> in {}", p.getFileName());
                         }
                     } catch (IOException e) {
                         log.warn("[DayPickerPropPatcher] Could not process {}: {}", p, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.warn("[DayPickerPropPatcher] Walk failed for {}: {}", frontendSrc, e.getMessage());
        }
        return changed[0];
    }

    static String rewrite(String content) {
        Matcher m = TAG_START.matcher(content);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            if (m.start() < last) continue; // inside a tag we already rewrote
            int tagEnd = openingTagEnd(content, m.end());
            if (tagEnd < 0) continue; // unterminated tag — leave it for tsc/agent
            out.append(content, last, m.start());
            String tag = content.substring(m.start(), tagEnd);
            out.append(INITIAL_FOCUS.matcher(tag).replaceAll("autoFocus"));
            last = tagEnd;
        }
        if (last == 0) return content;
        out.append(content.substring(last));
        return out.toString();
    }

    /**
     * Index just past the {@code >} that closes the opening tag beginning at {@code i}, tracking JSX
     * expression braces and string literals so a {@code >} inside {@code onSelect={(d) => …}} or a
     * string is not mistaken for the tag end. Returns -1 if unterminated.
     */
    private static int openingTagEnd(String s, int i) {
        int depth = 0;      // {} depth for JSX expression containers
        char quote = 0;     // current string delimiter, or 0
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            switch (c) {
                case '"', '\'', '`' -> quote = c;
                case '{' -> depth++;
                case '}' -> { if (depth > 0) depth--; }
                case '>' -> { if (depth == 0) return i + 1; }
                default -> { /* keep scanning */ }
            }
        }
        return -1;
    }
}
