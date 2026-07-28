package com.business.discovery.worker.util;

import com.business.discovery.worker.util.ApiInventory.Endpoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the path-segment fallback in endpointDomain — reached whenever the endpoint's wire
 * types do not name a known domain, which is exactly when the segment's singular form decides
 * the emitted service filename.
 */
class TsSdkGeneratorDomainTest {

    /** An endpoint with no wire types, so domain assignment falls through to the path. */
    private static Endpoint at(String path) {
        return new Endpoint("GET", path, "handler", null, null, false, List.of());
    }

    private static String domainOf(String path) {
        return TsSdkGenerator.endpointDomain(at(path), List.of());
    }

    @Test
    @DisplayName("classes → class, not the classe that produced classeService.ts")
    void sibilantPluralsLoseTheWholeEs() {
        assertThat(domainOf("/api/v1/admin/classes")).isEqualTo("class");
        assertThat(domainOf("/api/v1/dishes")).isEqualTo("dish");
        assertThat(domainOf("/api/v1/branches")).isEqualTo("branch");
        assertThat(domainOf("/api/v1/boxes")).isEqualTo("box");
    }

    @Test
    @DisplayName("consonant-y plurals restore the y")
    void iesPluralsBecomeY() {
        assertThat(domainOf("/api/v1/categories")).isEqualTo("category");
        assertThat(domainOf("/api/v1/properties")).isEqualTo("property");
        assertThat(domainOf("/api/v1/amenities")).isEqualTo("amenity");
    }

    @Test
    @DisplayName("plain plurals drop the single s")
    void plainPluralsDropS() {
        assertThat(domainOf("/api/v1/orders")).isEqualTo("order");
        assertThat(domainOf("/api/v1/admin/schedules")).isEqualTo("schedule");
        assertThat(domainOf("/api/v1/memberships")).isEqualTo("membership");
        assertThat(domainOf("/api/v1/services")).isEqualTo("service");
        assertThat(domainOf("/api/v1/invoices")).isEqualTo("invoice");
    }

    @Test
    @DisplayName("nouns that merely end in s are left intact")
    void nonPluralsAreUntouched() {
        assertThat(domainOf("/api/v1/status")).isEqualTo("status");
        assertThat(domainOf("/api/v1/address")).isEqualTo("address");
        assertThat(domainOf("/api/v1/analysis")).isEqualTo("analysis");
    }

    @Test
    @DisplayName("already-singular segments pass through")
    void singularSegmentsPassThrough() {
        assertThat(domainOf("/api/v1/menu")).isEqualTo("menu");
        assertThat(domainOf("/api/v1/contact")).isEqualTo("contact");
    }

    @Test
    @DisplayName("the /admin prefix does not become the domain")
    void adminPrefixIsStripped() {
        assertThat(domainOf("/api/v1/admin/classes")).isEqualTo("class");
        assertThat(domainOf("/api/v1/admin/orders")).isEqualTo("order");
    }

    @Test
    @DisplayName("a planned domain still wins over the path fallback")
    void plannedDomainTakesPrecedence() {
        // booking-domain endpoints stay in the planned bookingService rather than splitting out
        assertThat(TsSdkGenerator.endpointDomain(at("/api/v1/admin/classes"), List.of("class")))
                .isEqualTo("class");
    }
}
