package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FeatureCard;
import com.business.discovery.worker.service.llm.FeatureSpec;
import com.business.discovery.worker.service.llm.FileSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnrichmentCardUtilTest {

    @TempDir
    Path workspace;

    private ArchitectureSpec sampleSpec() {
        FileSpec controller = FileSpec.builder()
                .filePath("backend/src/main/java/com/test/OrderController.java")
                .fileRole("CONTROLLER — REST endpoints for orders")
                .featureName("order-management")
                .build();
        FileSpec service = FileSpec.builder()
                .filePath("backend/src/main/java/com/test/OrderService.java")
                .fileRole("SERVICE — order business logic")
                .featureName("order-management")
                .build();

        FeatureSpec feature = FeatureSpec.builder()
                .featureName("order-management")
                .featureDisplayName("Order Management")
                .featureType("BACKEND")
                .changeRequired(true)
                .featureInstruction("Implement order lifecycle across controller and service.")
                .dependsOnFeatures(List.of("auth"))
                .filePaths(List.of(controller.getFilePath(), service.getFilePath()))
                .build();

        ArchitectureSpec spec = new ArchitectureSpec();
        spec.setFiles(List.of(controller, service));
        spec.setFeatures(List.of(feature));
        return spec;
    }

    @Test
    void build_keysByFeatureName_andResolvesFileRoles() {
        Map<String, FeatureCard> cards = EnrichmentCardUtil.build(sampleSpec());

        assertThat(cards).containsOnlyKeys("order-management");
        FeatureCard card = cards.get("order-management");
        assertThat(card.getFeatureDisplayName()).isEqualTo("Order Management");
        assertThat(card.getFeatureType()).isEqualTo("BACKEND");
        assertThat(card.isChangeRequired()).isTrue();
        assertThat(card.getFeatureInstruction()).contains("order lifecycle");
        assertThat(card.getDependsOnFeatures()).containsExactly("auth");
        assertThat(card.getFiles()).hasSize(2);
        assertThat(card.getFiles())
                .extracting(FeatureCard.FileRef::getRole)
                .anyMatch(r -> r != null && r.startsWith("SERVICE"));
    }

    @Test
    void build_skipsFeaturesWithoutName() {
        ArchitectureSpec spec = sampleSpec();
        spec.getFeatures().get(0).setFeatureName(null);

        assertThat(EnrichmentCardUtil.build(spec)).isEmpty();
    }

    @Test
    void writeThenRead_roundTripsPreservingOrderAndFields() throws IOException {
        Map<String, FeatureCard> built = EnrichmentCardUtil.build(sampleSpec());
        EnrichmentCardUtil.write(workspace, built);

        assertThat(EnrichmentCardUtil.exists(workspace)).isTrue();
        assertThat(workspace.resolve(EnrichmentCardUtil.ENRICHMENT_PATH)).exists();

        Map<String, FeatureCard> read = EnrichmentCardUtil.read(workspace);
        assertThat(read).containsOnlyKeys("order-management");
        FeatureCard card = read.get("order-management");
        assertThat(card.getFeatureName()).isEqualTo("order-management");
        assertThat(card.getFiles()).hasSize(2);
        assertThat(card.getFiles().get(0).getPath())
                .isEqualTo("backend/src/main/java/com/test/OrderController.java");
    }

    @Test
    void read_missingFile_returnsEmptyMap() throws IOException {
        assertThat(EnrichmentCardUtil.read(workspace)).isEmpty();
        assertThat(EnrichmentCardUtil.exists(workspace)).isFalse();
    }
}
