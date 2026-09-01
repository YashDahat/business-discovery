package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.BriefContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Derives frontend/src/config/siteConfig.ts mechanically from the plan + brief, the same move
 * {@link RouteManifestGenerator} makes for routes.ts / App.tsx. siteConfig is CONFIG against a fixed,
 * foundation-fenced contract ({@code SiteConfig = { header, footer }} in @/shell) — not creative
 * content — so authoring it by hand (as the LLM used to) only produced shape-divergence errors
 * (missing header, openingHours typed as an array). Everything it needs is already in hand:
 *
 * <ul>
 *   <li>brandName ← {@link BriefContext#businessName()}</li>
 *   <li>header.navLinks ← the public, nav-visible {@link RouteManifest} entries — same single source
 *       of truth as the route table, so the header nav can never drift from the routes</li>
 *   <li>footer address / phone / openingHours ← the brief (openHours is already a formatted STRING,
 *       so the "openingHours as array" error cannot recur by construction)</li>
 *   <li>showAuth ← whether an AuthContext exists; showCart ← a /cart route is planned AND the
 *       business sells goods (category/features signal) — off for service businesses (gym, salon)</li>
 *   <li>header.navLinks ordering pins Contact last (convention), home first from the manifest</li>
 * </ul>
 *
 * <p>Optional, aesthetic fields (email, socialLinks, tagline, design tokens bgClass/…) are omitted:
 * they are optional in the contract and the fenced shell supplies its own theme defaults. Emitted
 * with {@link RouteManifest#PLAN_MARKER} so it is fenced from LLM generation and the ErrorFixAgent.
 */
public final class SiteConfigGenerator {

    private SiteConfigGenerator() {}

    /** Nav keys pinned to the END of the header nav — Contact last is a near-universal convention. */
    private static final Set<String> TRAILING_NAV = Set.of("CONTACT");

    public static String emit(RouteManifest manifest, BriefContext brief, boolean hasAuth) {
        String brand = orDefault(brief == null ? null : brief.businessName(), "Our Business");

        // A header cart button only makes sense for businesses that SELL GOODS (restaurant/retail),
        // not service businesses (gym, salon, clinic) whose "checkout" is a membership/booking flow.
        // Require both a planned /cart route AND a product-commerce signal, so the button never links
        // nowhere and never shows for a service business that merely reuses the cart scaffold.
        boolean hasCartRoute = manifest.entries().stream().anyMatch(e -> "/cart".equals(e.path()));
        boolean showCart = hasCartRoute && sellsProducts(brief);

        // Header nav = public, nav-visible pages only (excludes admin, auth-gated /account, /login,
        // /cart, param + catch-all routes). Manifest gives home first; a stable sort then pins Contact
        // (and any TRAILING_NAV key) to the end while preserving the in-between order.
        List<RouteManifest.Entry> navLinks = new ArrayList<>(manifest.entries().stream()
                .filter(e -> e.gate() == RouteManifest.RouteGate.PUBLIC && e.nav() && !"*".equals(e.path()))
                .toList());
        navLinks.sort(Comparator.comparingInt(e -> TRAILING_NAV.contains(e.key()) ? 1 : 0));

        StringBuilder sb = new StringBuilder();
        sb.append(RouteManifest.PLAN_MARKER).append('\n');
        sb.append("// Business shell configuration (brand, nav, contact) — DERIVED from the plan + brief.\n");
        sb.append("// Header nav mirrors the public route table; never hand-edit. Design tokens are\n");
        sb.append("// omitted on purpose — the fenced shell supplies its own theme defaults.\n");
        sb.append("import type { SiteConfig } from '@/shell';\n\n");
        sb.append("export const siteConfig: SiteConfig = {\n");
        sb.append("  header: {\n");
        sb.append("    brandName: ").append(js(brand)).append(",\n");
        sb.append("    navLinks: [\n");
        for (RouteManifest.Entry e : navLinks) {
            sb.append("      { label: ").append(js(e.label())).append(", href: ").append(js(e.path())).append(" },\n");
        }
        sb.append("    ],\n");
        sb.append("    showAuth: ").append(hasAuth).append(",\n");
        sb.append("    showCart: ").append(showCart).append(",\n");
        sb.append("  },\n");
        sb.append("  footer: {\n");
        sb.append("    brandName: ").append(js(brand)).append(",\n");
        appendIfPresent(sb, "address", brief == null ? null : brief.address());
        appendIfPresent(sb, "phone", brief == null ? null : brief.phone());
        appendIfPresent(sb, "openingHours", brief == null ? null : brief.openHours());
        sb.append("  },\n");
        sb.append("};\n");
        return sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, String key, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("    ").append(key).append(": ").append(js(value)).append(",\n");
        }
    }

    private static String orDefault(String v, String d) {
        return v != null && !v.isBlank() ? v : d;
    }

    // Product-commerce signals matched against category + planned features. Single words are
    // word-bounded so 'workshop'/'marketing'/'delivery'/'restore' don't false-trigger
    // 'shop'/'market'/'deli'/'store'; multi-word phrases catch e-commerce/ordering features.
    private static final Pattern COMMERCE = Pattern.compile(
            "\\b(restaurant|cafe|café|coffee|bakery|patisserie|pizz\\w*|bistro|eatery|diner|deli|"
          + "grocery|supermarket|retail|store|shop|boutique|pharmacy|market|butcher|confection|sweets?|"
          + "apparel|clothing|jewellery|jewelry|florist)\\b"
          + "|e-?commerce|add to cart|shopping cart|online (order|store|shop)|order online|product catalog",
            Pattern.CASE_INSENSITIVE);

    /** True when the business sells goods (→ a header cart button is appropriate). */
    static boolean sellsProducts(BriefContext brief) {
        if (brief == null) return false;
        StringBuilder hay = new StringBuilder(nz(brief.category()));
        appendAll(hay, brief.mustHaveFeatures());
        appendAll(hay, brief.niceToHaveFeatures());
        return COMMERCE.matcher(hay).find();
    }

    private static void appendAll(StringBuilder sb, List<String> items) {
        if (items != null) for (String s : items) if (s != null) sb.append(' ').append(s);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** A double-quoted TS string literal: escape backslash/quote, collapse newlines to spaces. */
    static String js(String s) {
        String v = (s == null ? "" : s)
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", " ").replace("\n", " ").trim();
        return "\"" + v + "\"";
    }
}
