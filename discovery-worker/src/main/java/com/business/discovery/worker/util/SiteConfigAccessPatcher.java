package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Deterministic backstop for the fenced {@code SiteConfig} access path (Theme D / F4 in
 * docs/frontend-error-patterns-abs-fitness.md). {@code SiteConfig} is NESTED — {@code { header, footer }} —
 * but the model reads its fields flat ({@code siteConfig.phone}), which does not exist and fails with
 * {@code TS2339} ({@code WhatsAppCta.tsx} read {@code siteConfig.phone}; the number is
 * {@code siteConfig.footer.phone}). The shape is already documented in the frontend foundation contract,
 * yet — like the other Theme-C/D leaks — the doc is ignored, so this rewrites the flat access to the
 * correct nested path.
 *
 * <p>The fixed foundation shape (see {@code arch_outline.txt}):
 * <pre>
 *   header: { brandName, navLinks, ctaButton, bgClass, textClass, hoverClass, ctaClass }
 *   footer: { brandName, tagline, address, phone, email, openingHours, quickLinks, socialLinks,
 *             bgClass, textClass, accentClass }
 * </pre>
 *
 * <p>Only fields that live in EXACTLY ONE section are rewritten — an unambiguous flat access has a single
 * correct nesting. Fields present in BOTH sections ({@code brandName}, {@code bgClass}, {@code textClass})
 * are deliberately left alone: a blind guess would silently bind the wrong value (worse than a compile
 * error), so those are left to the ErrorFixAgent. Already-nested access ({@code siteConfig.footer.phone})
 * is untouched — the pattern only matches a field directly on {@code siteConfig}. Zero LLM; idempotent.
 */
@Slf4j
public final class SiteConfigAccessPatcher {

    /** Fields that live only under {@code footer} → {@code siteConfig.<field>} becomes {@code siteConfig.footer.<field>}. */
    private static final String[] FOOTER_FIELDS = {
            "tagline", "address", "phone", "email", "openingHours", "quickLinks", "socialLinks", "accentClass"
    };
    /** Fields that live only under {@code header}. */
    private static final String[] HEADER_FIELDS = {
            "navLinks", "ctaButton", "hoverClass", "ctaClass"
    };
    // Deliberately NOT rewritten (present in BOTH header and footer): brandName, bgClass, textClass.

    /** field → owning section, unambiguous only. */
    private static final Map<String, String> FIELD_SECTION = buildFieldSection();

    private static Map<String, String> buildFieldSection() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String f : FOOTER_FIELDS) m.put(f, "footer");
        for (String f : HEADER_FIELDS) m.put(f, "header");
        return m;
    }

    /**
     * {@code siteConfig.<field>} where {@code <field>} is one of the unambiguous single-section fields.
     * The leading {@code \b} rejects {@code mySiteConfig.phone}; the trailing {@code \b} rejects
     * {@code siteConfig.phoneNumber}; and because the char right after {@code siteConfig.} must be the
     * field itself, {@code siteConfig.footer.phone} never matches (there it is {@code footer}).
     */
    private static final Pattern FLAT_ACCESS = Pattern.compile(
            "\\bsiteConfig\\.(" + String.join("|", FIELD_SECTION.keySet()) + ")\\b");

    private SiteConfigAccessPatcher() {}

    /** Returns true if any file was modified. */
    public static boolean fix(Path frontendSrc) {
        if (!Files.exists(frontendSrc)) return false;
        boolean[] changed = {false};
        try (Stream<Path> files = Files.walk(frontendSrc)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".tsx") || p.toString().endsWith(".ts"))
                 .filter(p -> !p.toString().contains("node_modules"))
                 .filter(p -> !p.toString().contains("/components/ui/"))
                 .forEach(p -> {
                     try {
                         String content = Files.readString(p);
                         String rewritten = rewrite(content);
                         if (!rewritten.equals(content)) {
                             Files.writeString(p, rewritten);
                             changed[0] = true;
                             log.info("[SiteConfigAccessPatcher] Nested flat siteConfig access in {}", p.getFileName());
                         }
                     } catch (IOException e) {
                         log.warn("[SiteConfigAccessPatcher] Could not process {}: {}", p, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.warn("[SiteConfigAccessPatcher] Walk failed for {}: {}", frontendSrc, e.getMessage());
        }
        return changed[0];
    }

    static String rewrite(String content) {
        Matcher m = FLAT_ACCESS.matcher(content);
        return m.replaceAll(mr -> {
            String field = mr.group(1);
            return Matcher.quoteReplacement("siteConfig." + FIELD_SECTION.get(field) + "." + field);
        });
    }
}
