package com.business.discovery.worker.util;

import com.business.discovery.worker.util.ApiAccessPolicy.Tier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiAccessPolicyTest {

    @Test
    void catalogGetsArePublic() {
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/menus/items")).isEqualTo(Tier.PUBLIC);
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/menus/categories")).isEqualTo(Tier.PUBLIC);
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/events")).isEqualTo(Tier.PUBLIC);
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/testimonials/featured")).isEqualTo(Tier.PUBLIC);
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/gallery")).isEqualTo(Tier.PUBLIC);
    }

    @Test
    void personalDataIsAuthenticatedEvenForGet() {
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/orders")).isEqualTo(Tier.AUTHENTICATED);
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/reservations")).isEqualTo(Tier.AUTHENTICATED);
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/loyalty/account")).isEqualTo(Tier.AUTHENTICATED);
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/profile")).isEqualTo(Tier.AUTHENTICATED);
        // "address" ends in -ss, must not depluralize to "addres" and leak
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/addresses")).isEqualTo(Tier.AUTHENTICATED);
    }

    @Test
    void catalogWritesAreNotPublic() {
        // a POST to a catalog domain is a mutation — never anonymous
        assertThat(ApiAccessPolicy.classify("POST", "/api/v1/menus")).isEqualTo(Tier.AUTHENTICATED);
        assertThat(ApiAccessPolicy.classify(null, "/api/v1/menus")).isEqualTo(Tier.AUTHENTICATED);
    }

    @Test
    void adminWinsRegardlessOfDomainOrMethod() {
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/admin/orders")).isEqualTo(Tier.ADMIN);
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/admin/menus")).isEqualTo(Tier.ADMIN);
        assertThat(ApiAccessPolicy.classify("PUT", "/api/v1/admin/reservations/1/status")).isEqualTo(Tier.ADMIN);
    }

    @Test
    void authIsPublic() {
        assertThat(ApiAccessPolicy.classify("POST", "/api/v1/auth/login")).isEqualTo(Tier.PUBLIC);
        assertThat(ApiAccessPolicy.classify("POST", "/api/v1/auth/register")).isEqualTo(Tier.PUBLIC);
    }

    @Test
    void unrecognisedShapesLockDownByDefault() {
        assertThat(ApiAccessPolicy.classify("GET", "/health")).isEqualTo(Tier.AUTHENTICATED);
        assertThat(ApiAccessPolicy.classify("GET", null)).isEqualTo(Tier.AUTHENTICATED);
    }

    // ── Other verticals (fallback heuristic) ─────────────────────────────────

    @Test
    void catalogHeuristicCoversNonRestaurantVerticals() {
        // gym / studio
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/classes")).isEqualTo(Tier.PUBLIC);
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/trainers")).isEqualTo(Tier.PUBLIC);
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/plans")).isEqualTo(Tier.PUBLIC);
        // salon / spa
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/services")).isEqualTo(Tier.PUBLIC);
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/stylists")).isEqualTo(Tier.PUBLIC);
        // clinic
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/doctors")).isEqualTo(Tier.PUBLIC);
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/specialities")).isEqualTo(Tier.PUBLIC);
        // real estate
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/properties/featured")).isEqualTo(Tier.PUBLIC);
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/listings")).isEqualTo(Tier.PUBLIC);
        // education
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/courses")).isEqualTo(Tier.PUBLIC);
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/faculty")).isEqualTo(Tier.PUBLIC);
        // retail
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/products")).isEqualTo(Tier.PUBLIC);
    }

    @Test
    void personalDomainsStayLockedAcrossVerticals() {
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/bookings")).isEqualTo(Tier.AUTHENTICATED);
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/appointments")).isEqualTo(Tier.AUTHENTICATED);
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/enrolments")).isEqualTo(Tier.AUTHENTICATED);
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/invoices")).isEqualTo(Tier.AUTHENTICATED);
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/enquiries")).isEqualTo(Tier.AUTHENTICATED);
    }

    // ── Plan-declared access (the generic mechanism) ─────────────────────────

    @Test
    void planDeclarationBeatsTheHeuristic() {
        // a vertical noun the allowlist has never heard of, declared public by the planner
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/kennels", "public")).isEqualTo(Tier.PUBLIC);
        // and the reverse: a catalog-looking domain the planner says is private
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/products", "authenticated"))
                .isEqualTo(Tier.AUTHENTICATED);
    }

    @Test
    void declaredPublicWriteIsHonoured() {
        // an anonymous contact/enquiry form submission
        assertThat(ApiAccessPolicy.classify("POST", "/api/v1/enquiries", "public")).isEqualTo(Tier.PUBLIC);
    }

    @Test
    void adminPathOverridesEvenADeclaredPublic() {
        // a planner mistake must never open the owner's surface
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/admin/listings", "public"))
                .isEqualTo(Tier.ADMIN);
    }

    @Test
    void unrecognisedDeclarationFallsBackToHeuristic() {
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/classes", "banana")).isEqualTo(Tier.PUBLIC);
        assertThat(ApiAccessPolicy.classify("GET", "/api/v1/orders", "banana")).isEqualTo(Tier.AUTHENTICATED);
    }

    @Test
    void exactMatcherPatternWidensPathVariables() {
        assertThat(ApiAccessPolicy.exactMatcherPattern("/api/v1/classes/{id}")).isEqualTo("/api/v1/classes/*");
        assertThat(ApiAccessPolicy.exactMatcherPattern("/api/v1/enquiries")).isEqualTo("/api/v1/enquiries");
        assertThat(ApiAccessPolicy.exactMatcherPattern(null)).isNull();
    }

    @Test
    void publicPathPatternGeneralisesToDomainGlob() {
        assertThat(ApiAccessPolicy.publicPathPattern("/api/v1/menus/items")).isEqualTo("/api/v1/menus/**");
        assertThat(ApiAccessPolicy.publicPathPattern("/api/v1/events")).isEqualTo("/api/v1/events/**");
        assertThat(ApiAccessPolicy.publicPathPattern("/api/testimonials/featured")).isEqualTo("/api/testimonials/**");
        assertThat(ApiAccessPolicy.publicPathPattern("/api/v2/menus")).isEqualTo("/api/v2/menus/**");
        assertThat(ApiAccessPolicy.publicPathPattern("/health")).isNull();
    }
}
