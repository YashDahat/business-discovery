package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F4 (Theme D) — flat {@code siteConfig.<field>} access on the nested fenced {@code SiteConfig}
 * ({@code { header, footer }}) is rewritten to the correct section path. The motivating defect:
 * {@code WhatsAppCta.tsx} read {@code siteConfig.phone} (→ TS2339); the number is
 * {@code siteConfig.footer.phone}.
 */
class SiteConfigAccessPatcherTest {

    @TempDir Path frontendSrc;

    // ── Footer-only fields → siteConfig.footer.<field> ─────────────────────────

    @Test
    void rewritesFooterOnlyFieldsToFooter() {
        assertThat(SiteConfigAccessPatcher.rewrite("const n = siteConfig.phone;"))
                .isEqualTo("const n = siteConfig.footer.phone;");
        assertThat(SiteConfigAccessPatcher.rewrite("siteConfig.email"))
                .isEqualTo("siteConfig.footer.email");
        assertThat(SiteConfigAccessPatcher.rewrite("siteConfig.address"))
                .isEqualTo("siteConfig.footer.address");
        assertThat(SiteConfigAccessPatcher.rewrite("siteConfig.openingHours"))
                .isEqualTo("siteConfig.footer.openingHours");
        assertThat(SiteConfigAccessPatcher.rewrite("siteConfig.socialLinks.map(s => s)"))
                .isEqualTo("siteConfig.footer.socialLinks.map(s => s)");
        assertThat(SiteConfigAccessPatcher.rewrite("siteConfig.tagline"))
                .isEqualTo("siteConfig.footer.tagline");
        assertThat(SiteConfigAccessPatcher.rewrite("siteConfig.accentClass"))
                .isEqualTo("siteConfig.footer.accentClass");
    }

    // ── Header-only fields → siteConfig.header.<field> ─────────────────────────

    @Test
    void rewritesHeaderOnlyFieldsToHeader() {
        assertThat(SiteConfigAccessPatcher.rewrite("siteConfig.navLinks.map(l => l)"))
                .isEqualTo("siteConfig.header.navLinks.map(l => l)");
        assertThat(SiteConfigAccessPatcher.rewrite("siteConfig.ctaButton"))
                .isEqualTo("siteConfig.header.ctaButton");
        assertThat(SiteConfigAccessPatcher.rewrite("siteConfig.hoverClass"))
                .isEqualTo("siteConfig.header.hoverClass");
        assertThat(SiteConfigAccessPatcher.rewrite("siteConfig.ctaClass"))
                .isEqualTo("siteConfig.header.ctaClass");
    }

    @Test
    void rewritesWhatsAppCtaPhoneAccess() {
        String src = "const href = `https://wa.me/${siteConfig.phone.replace(/\\D/g, '')}`;";
        assertThat(SiteConfigAccessPatcher.rewrite(src))
                .isEqualTo("const href = `https://wa.me/${siteConfig.footer.phone.replace(/\\D/g, '')}`;");
    }

    // ── Ambiguous / already-correct / unrelated: left untouched ────────────────

    @Test
    void leavesAmbiguousFieldsAlone() {
        // brandName / bgClass / textClass exist in BOTH header and footer — cannot be resolved safely.
        for (String s : new String[]{"siteConfig.brandName", "siteConfig.bgClass", "siteConfig.textClass"}) {
            assertThat(SiteConfigAccessPatcher.rewrite(s)).isEqualTo(s);
        }
    }

    @Test
    void leavesAlreadyNestedAccessUntouched() {
        for (String s : new String[]{
                "siteConfig.footer.phone", "siteConfig.header.navLinks", "siteConfig.footer.socialLinks"}) {
            assertThat(SiteConfigAccessPatcher.rewrite(s)).isEqualTo(s);
        }
    }

    @Test
    void doesNotMatchLongerFieldNamesOrOtherIdentifiers() {
        assertThat(SiteConfigAccessPatcher.rewrite("siteConfig.phoneNumber"))   // not the 'phone' field
                .isEqualTo("siteConfig.phoneNumber");
        assertThat(SiteConfigAccessPatcher.rewrite("xsiteConfig.phone"))        // different identifier
                .isEqualTo("xsiteConfig.phone");
        assertThat(SiteConfigAccessPatcher.rewrite("footer.phone"))             // destructured — no siteConfig
                .isEqualTo("footer.phone");
    }

    @Test
    void isIdempotent() {
        String once = SiteConfigAccessPatcher.rewrite("siteConfig.phone + siteConfig.navLinks");
        assertThat(once).isEqualTo("siteConfig.footer.phone + siteConfig.header.navLinks");
        assertThat(SiteConfigAccessPatcher.rewrite(once)).isEqualTo(once);
    }

    // ── On-disk walk ───────────────────────────────────────────────────────────

    @Test
    void fixRewritesFilesOnDiskAndSkipsUiComponents() throws Exception {
        Path cta = write("components/WhatsAppCta.tsx", "export const x = siteConfig.phone;");
        Path ui  = write("components/ui/button.tsx", "export const y = siteConfig.phone;"); // fenced — skip

        boolean changed = SiteConfigAccessPatcher.fix(frontendSrc);

        assertThat(changed).isTrue();
        assertThat(Files.readString(cta)).contains("siteConfig.footer.phone");
        assertThat(Files.readString(ui)).contains("siteConfig.phone");   // untouched (components/ui)
    }

    @Test
    void fixReturnsFalseWhenNothingToRewrite() throws Exception {
        write("pages/HomePage.tsx", "export const x = siteConfig.footer.phone;");
        assertThat(SiteConfigAccessPatcher.fix(frontendSrc)).isFalse();
    }

    private Path write(String rel, String content) throws Exception {
        Path p = frontendSrc.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }
}
