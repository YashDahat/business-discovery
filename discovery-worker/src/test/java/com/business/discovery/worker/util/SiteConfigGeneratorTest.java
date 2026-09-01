package com.business.discovery.worker.util;

import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.service.llm.BriefContext;
import com.business.discovery.worker.service.llm.FileEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SiteConfigGeneratorTest {

    private static FileEntry page(String name) {
        return new FileEntry("frontend/src/pages/" + name + ".tsx", FileType.FRONTEND, name);
    }

    /** Brief with the fields siteConfig derives from; the rest are irrelevant here (nulls ok). */
    private static BriefContext brief(String name, String address, String phone, String openHours) {
        return new BriefContext(name, "Gym", "Pune", "FULL_PLATFORM",
                List.of(), List.of(), List.of(), java.util.Map.of(), List.of(),
                null, null, null, null, null, null, null, null,
                address, phone, "18.5", "73.8", openHours);
    }

    // The abs-fitness 9312afa6 public+admin page set (subset sufficient for nav/gate coverage).
    private RouteManifest absFitnessManifest() {
        return RouteManifest.fromSpec(List.of(
                page("HomePage"), page("AboutPage"), page("ClassesPage"), page("MembershipPage"),
                page("TrainersPage"), page("GalleryPage"), page("ContactPage"),
                page("AccountPage"), page("CheckoutPage"), page("CartPage"), page("LoginPage"),
                page("AdminDashboardPage"), page("NotFoundPage")));
    }

    @Test
    void emitsContractShapedConfigWithDerivedNavAndBriefContact() {
        String ts = SiteConfigGenerator.emit(absFitnessManifest(),
                brief("ABS FITNESS", "123 Fitness Ave, Pune", "+91 98765 43210",
                        "Mon-Sat: 6AM-10PM"), true);

        // fenced + contract-shaped
        assertThat(ts).startsWith(RouteManifest.PLAN_MARKER);
        assertThat(ts).contains("import type { SiteConfig } from '@/shell';")
                .contains("export const siteConfig: SiteConfig = {")
                .contains("header: {").contains("footer: {");

        // brand from the brief, on both header and footer
        assertThat(ts).contains("brandName: \"ABS FITNESS\"");

        // nav = public nav-visible pages only; NOT account (auth), login (nav:false), cart (nav:false), admin
        assertThat(ts).contains("{ label: \"Home\", href: \"/\" }")
                .contains("{ label: \"Contact\", href: \"/contact\" }")
                .contains("{ label: \"Membership\", href: \"/membership\" }");
        assertThat(ts).doesNotContain("href: \"/account\"")
                .doesNotContain("href: \"/login\"")
                .doesNotContain("href: \"/cart\"")
                .doesNotContain("href: \"/admin\"");
        // Home first, Contact last (convention)
        assertThat(ts.indexOf("href: \"/\" }")).isLessThan(ts.indexOf("href: \"/about\""));
        assertThat(ts.indexOf("href: \"/contact\"")).isGreaterThan(ts.indexOf("href: \"/trainers\""));

        // footer contact straight from the brief; openingHours is a STRING (not an array)
        assertThat(ts).contains("address: \"123 Fitness Ave, Pune\"")
                .contains("phone: \"+91 98765 43210\"")
                .contains("openingHours: \"Mon-Sat: 6AM-10PM\"");
        assertThat(ts).doesNotContain("openingHours: [");

        // showAuth follows the AuthContext flag; showCart FALSE — a gym is a service business, not
        // product-commerce, even though a /cart route (membership checkout) is planned
        assertThat(ts).contains("showAuth: true").contains("showCart: false");
        // no aesthetic/optional fields in v1
        assertThat(ts).doesNotContain("bgClass").doesNotContain("socialLinks").doesNotContain("mapCoordinates");
    }

    @Test
    void showsCartOnlyForProductCommerceBusinessWithCartRoute() {
        RouteManifest withCart = RouteManifest.fromSpec(List.of(page("HomePage"), page("CartPage")));

        // restaurant (sells goods) + a /cart route → cart button on
        BriefContext restaurant = new BriefContext("Spice Villa", "Restaurant", "Pune", "FULL_PLATFORM",
                List.of("Online food ordering"), List.of(), List.of(), java.util.Map.of(), List.of(),
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(SiteConfigGenerator.emit(withCart, restaurant, true)).contains("showCart: true");

        // a gym with the SAME /cart route stays off (service business)
        assertThat(SiteConfigGenerator.emit(withCart, brief("Gym X", null, null, null), true))
                .contains("showCart: false");
    }

    @Test
    void sellsProductsDistinguishesCommerceFromServiceAndAvoidsSubstringFalsePositives() {
        assertThat(SiteConfigGenerator.sellsProducts(cat("Restaurant"))).isTrue();
        assertThat(SiteConfigGenerator.sellsProducts(cat("Clothing store"))).isTrue();
        assertThat(SiteConfigGenerator.sellsProducts(cat("Bakery"))).isTrue();
        assertThat(SiteConfigGenerator.sellsProducts(cat("Gym"))).isFalse();
        assertThat(SiteConfigGenerator.sellsProducts(cat("Beauty salon"))).isFalse();
        assertThat(SiteConfigGenerator.sellsProducts(cat("Dental clinic"))).isFalse();
        // substring traps: 'workshop'/'marketing'/'delivery' must NOT match shop/market/deli
        assertThat(SiteConfigGenerator.sellsProducts(cat("Yoga studio offering workshops"))).isFalse();
        assertThat(SiteConfigGenerator.sellsProducts(cat("Digital marketing agency"))).isFalse();
    }

    private static BriefContext cat(String category) {
        return new BriefContext("Biz", category, "Pune", "FULL_PLATFORM",
                List.of(), List.of(), List.of(), java.util.Map.of(), List.of(),
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void omitsMissingContactFieldsAndDefaultsBrandAndFlags() {
        // no cart route, no auth, brief missing contact
        RouteManifest m = RouteManifest.fromSpec(List.of(page("HomePage"), page("AboutPage")));
        String ts = SiteConfigGenerator.emit(m, brief(null, null, null, null), false);

        assertThat(ts).contains("brandName: \"Our Business\"");   // safe default
        assertThat(ts).contains("showAuth: false").contains("showCart: false");
        // absent brief fields are omitted, not emitted as null/empty
        assertThat(ts).doesNotContain("address:").doesNotContain("phone:").doesNotContain("openingHours:");
    }

    @Test
    void escapesQuotesInBusinessValues() {
        assertThat(SiteConfigGenerator.js("A \"B\" C")).isEqualTo("\"A \\\"B\\\" C\"");
        assertThat(SiteConfigGenerator.js("line1\nline2")).isEqualTo("\"line1 line2\"");
    }
}
