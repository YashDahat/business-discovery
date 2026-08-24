package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.ContractRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes docs/CONTRACTS.json — the observability artifact recording what {@link ContractReconciler}
 * decided for each file (planned interface → reconciled interface). Write-only: nothing reads it back
 * (generation binds to the reconciled fields in ARCHITECTURE.json); it exists so a human/PR can review
 * and diff the reconciler's decisions. Same SNAKE_CASE indented mapper as {@link ArchitectureJsonUtil}.
 */
public final class ContractsDoc {

    public static final String CONTRACTS_PATH = "docs/CONTRACTS.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private ContractsDoc() {}

    public static boolean exists(Path workspace) {
        return Files.exists(workspace.resolve(CONTRACTS_PATH));
    }

    /** Writes the records under a small header ({@code generated_at}, {@code reconciled_count}, {@code contracts}). */
    public static void write(Path workspace, List<ContractRecord> records) throws IOException {
        Path target = workspace.resolve(CONTRACTS_PATH);
        Files.createDirectories(target.getParent());

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("generated_at", LocalDate.now().toString());
        doc.put("reconciled_count", records == null ? 0 : records.size());
        doc.put("contracts", records == null ? List.of() : records);

        MAPPER.writeValue(target.toFile(), doc);
    }
}
