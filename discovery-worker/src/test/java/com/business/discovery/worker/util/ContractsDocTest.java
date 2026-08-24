package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.ContractRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContractsDocTest {

    @TempDir
    Path workspace;

    @Test
    void write_producesDiffableArtifactWithHeaderAndEntries() throws IOException {
        ContractRecord rec = ContractRecord.builder()
                .featureName("order-management")
                .module("backend/src/main/java/com/x/OrderDto.java")
                .kind("FIELDS")
                .plannedInterface("userId: UUID; total: BigDecimal")
                .reconciledInterface("userId: Integer; total: BigDecimal")
                .build();

        ContractsDoc.write(workspace, List.of(rec));

        assertThat(ContractsDoc.exists(workspace)).isTrue();
        String json = Files.readString(workspace.resolve(ContractsDoc.CONTRACTS_PATH));
        assertThat(json).contains("\"reconciled_count\" : 1");
        assertThat(json).contains("\"feature_name\" : \"order-management\"");
        assertThat(json).contains("\"planned_interface\" : \"userId: UUID; total: BigDecimal\"");
        assertThat(json).contains("\"reconciled_interface\" : \"userId: Integer; total: BigDecimal\"");
    }

    @Test
    void write_emptyRecords_ok() throws IOException {
        ContractsDoc.write(workspace, List.of());
        assertThat(Files.readString(workspace.resolve(ContractsDoc.CONTRACTS_PATH)))
                .contains("\"reconciled_count\" : 0");
    }
}
