package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FeatureCard;
import com.business.discovery.worker.service.llm.FeatureSpec;
import com.business.discovery.worker.service.llm.FileSpec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reads/writes docs/ENRICHMENT.json — the machine-readable {@code Map<featureName, FeatureCard>}
 * derived from {@link ArchitectureSpec}. Twin of {@link ArchitectureJsonUtil}: same SNAKE_CASE
 * mapper, same docs/ location, same forgiving deserialization.
 *
 * <p>Written once by ProjectPlanningNode after enrichment; read by the generator nodes so each file
 * can be handed its whole feature's context (sibling files + roles + the holistic instruction),
 * looked up by the {@code featureName} already present on every {@link FileSpec}.
 *
 * @see FeatureCard
 */
public final class EnrichmentCardUtil {

    public static final String ENRICHMENT_PATH = "docs/ENRICHMENT.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<LinkedHashMap<String, FeatureCard>> CARD_MAP_TYPE =
            new TypeReference<>() {};

    private EnrichmentCardUtil() {}

    // ── Build ───────────────────────────────────────────────────────────────

    /**
     * Derives one {@link FeatureCard} per feature from the spec, keyed by {@code featureName} in
     * declaration order. Files are resolved from the feature's {@code filePaths} to pick up each
     * file's role; features without a name are skipped (nothing can key or reference them).
     */
    public static Map<String, FeatureCard> build(ArchitectureSpec spec) {
        Map<String, FeatureCard> cards = new LinkedHashMap<>();
        if (spec == null || spec.getFeatures() == null) return cards;

        Map<String, FileSpec> fileByPath = (spec.getFiles() == null ? List.<FileSpec>of() : spec.getFiles())
                .stream()
                .filter(f -> f.getFilePath() != null)
                .collect(Collectors.toMap(FileSpec::getFilePath, f -> f, (a, b) -> a, LinkedHashMap::new));

        for (FeatureSpec feature : spec.getFeatures()) {
            if (feature.getFeatureName() == null) continue;

            List<FeatureCard.FileRef> files = (feature.getFilePaths() == null ? List.<String>of()
                    : feature.getFilePaths()).stream()
                    .map(path -> {
                        FileSpec fs = fileByPath.get(path);
                        return FeatureCard.FileRef.builder()
                                .path(path)
                                .role(fs != null ? fs.getFileRole() : null)
                                .build();
                    })
                    .toList();

            cards.put(feature.getFeatureName(), FeatureCard.builder()
                    .featureName(feature.getFeatureName())
                    .featureDisplayName(feature.getFeatureDisplayName())
                    .featureType(feature.getFeatureType())
                    .changeRequired(feature.isChangeRequired())
                    .featureInstruction(feature.getFeatureInstruction())
                    .dependsOnFeatures(feature.getDependsOnFeatures())
                    .files(files)
                    .build());
        }
        return cards;
    }

    // ── Write ───────────────────────────────────────────────────────────────

    public static void write(Path workspace, Map<String, FeatureCard> cards) throws IOException {
        Path target = workspace.resolve(ENRICHMENT_PATH);
        Files.createDirectories(target.getParent());
        MAPPER.writeValue(target.toFile(), cards);
    }

    // ── Read ────────────────────────────────────────────────────────────────

    public static boolean exists(Path workspace) {
        return Files.exists(workspace.resolve(ENRICHMENT_PATH));
    }

    /**
     * Loads the {@code Map<featureName, FeatureCard>}, or an empty map when the file is absent or
     * unreadable — callers treat a miss as "no card" and fall back to the raw spec instruction.
     */
    public static Map<String, FeatureCard> read(Path workspace) throws IOException {
        Path source = workspace.resolve(ENRICHMENT_PATH);
        if (!Files.exists(source)) return new LinkedHashMap<>();
        return MAPPER.readValue(source.toFile(), CARD_MAP_TYPE);
    }
}
