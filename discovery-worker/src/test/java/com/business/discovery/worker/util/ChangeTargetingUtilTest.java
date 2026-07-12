package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FeatureSpec;
import com.business.discovery.worker.service.llm.FileSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChangeTargetingUtilTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Spec as loaded from a previous run's ARCHITECTURE.json: every feature carries the
    // serialized default change_required=true — the stale marks the reset must clear.
    private ArchitectureSpec spec() {
        return ArchitectureSpec.builder()
                .features(List.of(
                        FeatureSpec.builder().featureName("menu-display").featureType("FRONTEND")
                                .changeRequired(true).build(),
                        FeatureSpec.builder().featureName("reservation-booking").featureType("FRONTEND")
                                .changeRequired(true).changeInstruction("stale directive from last update").build(),
                        FeatureSpec.builder().featureName("infrastructure").featureType("INFRA")
                                .changeRequired(true).build()))
                .files(List.of(
                        FileSpec.builder().filePath("frontend/src/pages/MenuPage.tsx")
                                .featureName("menu-display").build(),
                        FileSpec.builder().filePath("frontend/src/components/menu/MenuTable.tsx")
                                .featureName("menu-display").changeRequired(true).build(),
                        FileSpec.builder().filePath("frontend/src/pages/ReservationPage.tsx")
                                .featureName("reservation-booking").build()))
                .build();
    }

    private static JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    @Test
    void marksTargetedFeatureAndFilesResetsEverythingElse() throws Exception {
        ArchitectureSpec spec = spec();
        int changed = ChangeTargetingUtil.apply(spec, json("""
                {"features": [
                  {"feature_name": "menu-display", "change_required": true,
                   "change_instruction": "Turn the order button green (#16a34a) in MenuTable.tsx.",
                   "files": [
                     {"file_path": "frontend/src/pages/MenuPage.tsx", "change_required": false},
                     {"file_path": "frontend/src/components/menu/MenuTable.tsx", "change_required": true}
                   ]},
                  {"feature_name": "reservation-booking", "change_required": false, "files": []}
                ]}
                """));

        assertThat(changed).isEqualTo(1);
        FeatureSpec menu = spec.getFeatures().get(0);
        assertThat(menu.isChangeRequired()).isTrue();
        assertThat(menu.getChangeInstruction()).contains("#16a34a");

        // Stale marks cleared: previously-true feature + its old directive are gone.
        FeatureSpec reservation = spec.getFeatures().get(1);
        assertThat(reservation.isChangeRequired()).isFalse();
        assertThat(reservation.getChangeInstruction()).isNull();

        // File grain: page false, component true, untouched feature's file reset to null.
        assertThat(spec.getFiles().get(0).getChangeRequired()).isFalse();
        assertThat(spec.getFiles().get(1).getChangeRequired()).isTrue();
        assertThat(spec.getFiles().get(2).getChangeRequired()).isNull();
    }

    // A feature the response omits entirely must end up unmarked — its stale persisted
    // change_required=true (serialized default) must not trigger a spurious regeneration.
    @Test
    void featureOmittedFromResponseIsReset() throws Exception {
        ArchitectureSpec spec = spec();
        ChangeTargetingUtil.apply(spec, json("""
                {"features": [{"feature_name": "menu-display", "change_required": true, "files": []}]}
                """));
        assertThat(spec.getFeatures().get(1).isChangeRequired()).isFalse();
    }

    @Test
    void infraFeatureNeverMarked() throws Exception {
        ArchitectureSpec spec = spec();
        ChangeTargetingUtil.apply(spec, json("""
                {"features": [{"feature_name": "infrastructure", "change_required": true, "files": []}]}
                """));
        assertThat(spec.getFeatures().get(2).isChangeRequired()).isFalse();
    }

    // The outline owns both lists — hallucinated names must be dropped, not added.
    @Test
    void unknownFeatureAndFileDropped() throws Exception {
        ArchitectureSpec spec = spec();
        int changed = ChangeTargetingUtil.apply(spec, json("""
                {"features": [
                  {"feature_name": "gift-cards", "change_required": true, "files": []},
                  {"feature_name": "menu-display", "change_required": true,
                   "files": [{"file_path": "frontend/src/pages/GiftCardPage.tsx", "change_required": true}]}
                ]}
                """));
        assertThat(changed).isEqualTo(1);
        assertThat(spec.getFiles()).hasSize(3);
        assertThat(spec.getFeatures()).hasSize(3);
    }

    @Test
    void markAllChangedIsTheFullRegenerationFallback() {
        ArchitectureSpec spec = spec();
        ChangeTargetingUtil.markAllChanged(spec);
        assertThat(spec.getFeatures().get(0).isChangeRequired()).isTrue();
        assertThat(spec.getFeatures().get(1).isChangeRequired()).isTrue();
        assertThat(spec.getFeatures().get(1).getChangeInstruction()).isNull();
        assertThat(spec.getFeatures().get(2).isChangeRequired()).isFalse(); // INFRA
        // File flags cleared → every file falls back to its feature's decision.
        spec.getFiles().forEach(f -> assertThat(f.getChangeRequired()).isNull());
    }

    @Test
    void effectiveInstructionAppendsDirectiveOnlyInUpdateMode() {
        FeatureSpec feature = FeatureSpec.builder()
                .featureName("menu-display")
                .featureInstruction("Build the menu display feature.")
                .changeInstruction("Turn the order button green.")
                .build();
        assertThat(feature.effectiveInstruction(false)).isEqualTo("Build the menu display feature.");
        assertThat(feature.effectiveInstruction(true))
                .contains("Build the menu display feature.")
                .contains("== REQUESTED CHANGE")
                .contains("Turn the order button green.");

        FeatureSpec noDirective = FeatureSpec.builder()
                .featureInstruction("Build the menu display feature.").build();
        assertThat(noDirective.effectiveInstruction(true)).isEqualTo("Build the menu display feature.");
    }
}
