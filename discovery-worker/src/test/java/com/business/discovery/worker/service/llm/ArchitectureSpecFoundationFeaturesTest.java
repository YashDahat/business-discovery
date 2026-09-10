package com.business.discovery.worker.service.llm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the §6b Part A wiring: the planner's {@code foundation_features} assertion must
 * deserialize from ARCHITECTURE.json's snake_case keys and survive a write→read round-trip,
 * while old specs that omit it stay null (safe, no failure).
 */
class ArchitectureSpecFoundationFeaturesTest {

    // Same mapper contract as ArchitectureJsonUtil.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void deserializesFoundationFeatures_fromSnakeCaseKeys() throws Exception {
        String json = """
                {
                  "businessName": "Prakash Stores",
                  "files": [],
                  "foundation_features": [
                    { "id": "auth",    "consumed_via": ["useAuth()", "@/api/client", "@CurrentUser Integer userId"] },
                    { "id": "payment", "consumed_via": ["PaymentService.createOrder", "PaymentCapturedEvent"] }
                  ]
                }
                """;

        ArchitectureSpec spec = MAPPER.readValue(json, ArchitectureSpec.class);

        assertThat(spec.getFoundationFeatures()).hasSize(2);
        FoundationFeatureRef auth = spec.getFoundationFeatures().get(0);
        assertThat(auth.getId()).isEqualTo("auth");
        assertThat(auth.getConsumedVia()).contains("useAuth()", "@CurrentUser Integer userId");
        assertThat(spec.getFoundationFeatures().get(1).getId()).isEqualTo("payment");
    }

    @Test
    void roundTrips_throughSnakeCaseSerialization() throws Exception {
        ArchitectureSpec spec = ArchitectureSpec.builder()
                .businessName("Yoga Studio")
                .files(List.of())
                .foundationFeatures(List.of(
                        FoundationFeatureRef.builder()
                                .id("gallery")
                                .consumedVia(List.of("useGallery()", "<GallerySection/>"))
                                .build()))
                .build();

        String json = MAPPER.writeValueAsString(spec);
        assertThat(json).contains("\"foundation_features\"");
        assertThat(json).contains("\"consumed_via\"");

        ArchitectureSpec back = MAPPER.readValue(json, ArchitectureSpec.class);
        assertThat(back.getFoundationFeatures()).hasSize(1);
        assertThat(back.getFoundationFeatures().get(0).getId()).isEqualTo("gallery");
        assertThat(back.getFoundationFeatures().get(0).getConsumedVia()).containsExactly("useGallery()", "<GallerySection/>");
    }

    @Test
    void oldSpecWithoutField_deserializesToNull() throws Exception {
        String json = """
                { "businessName": "Legacy", "files": [] }
                """;

        ArchitectureSpec spec = MAPPER.readValue(json, ArchitectureSpec.class);

        assertThat(spec.getFoundationFeatures()).isNull();
    }
}
