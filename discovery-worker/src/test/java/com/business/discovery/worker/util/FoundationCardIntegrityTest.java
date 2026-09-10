package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FoundationCardIntegrityTest {

    private final FoundationManifest manifest = FoundationManifest.defaultManifest();

    private void writeBackendModel(Path workspace, String simpleName) throws IOException {
        Path model = workspace.resolve("backend/src/main/java/com/x/model");
        Files.createDirectories(model);
        Files.writeString(model.resolve(simpleName + ".java"),
                "package com.x.model;\npublic class " + simpleName + " { Long id; }\n");
    }

    private void writeBackendCard(Path workspace, String... declaredTypes) throws IOException {
        StringBuilder sb = new StringBuilder("# Foundation Backend Contract\n\n## Auth & users\n\n```java\n");
        for (String t : declaredTypes) sb.append("class ").append(t).append(" { Integer id; }\n");
        sb.append("```\n");
        Files.createDirectories(workspace.resolve("backend"));
        Files.writeString(workspace.resolve("backend/FOUNDATION_CONTRACT.md"), sb.toString());
    }

    @Test
    void absentCards_reportedAndRegistryEmpty(@TempDir Path workspace) {
        var report = FoundationCardIntegrity.check(
                workspace, manifest, FoundationSymbolRegistry.buildFromWorkspace(workspace));

        assertThat(report.backendCardPresent()).isFalse();
        assertThat(report.frontendCardPresent()).isFalse();
        assertThat(report.registryEmpty()).isTrue();
        assertThat(report.hasDrift()).isTrue();
    }

    @Test
    void fencedDataTypeOnDisk_missingFromCard_flaggedAsDrift(@TempDir Path workspace) throws IOException {
        // Both are manifest-fenced data types and both exist on disk...
        writeBackendModel(workspace, "User");
        writeBackendModel(workspace, "Payment");
        // ...but the card only declares User → Payment is stale-card drift.
        writeBackendCard(workspace, "User");

        var report = FoundationCardIntegrity.check(
                workspace, manifest, FoundationSymbolRegistry.buildFromWorkspace(workspace));

        assertThat(report.registryEmpty()).isFalse();
        assertThat(report.staleDataTypes()).hasSize(1);
        assertThat(report.staleDataTypes().get(0)).startsWith("Payment");
        assertThat(report.hasDrift()).isTrue();
    }

    @Test
    void allFencedTypesInCard_noDrift(@TempDir Path workspace) throws IOException {
        writeBackendModel(workspace, "User");
        writeBackendModel(workspace, "Payment");
        writeBackendCard(workspace, "User", "Payment");

        var report = FoundationCardIntegrity.check(
                workspace, manifest, FoundationSymbolRegistry.buildFromWorkspace(workspace));

        assertThat(report.staleDataTypes()).isEmpty();
    }

    @Test
    void nonFencedTypeOnDisk_notFlagged(@TempDir Path workspace) throws IOException {
        // A domain type the manifest does NOT fence must never be reported, even if absent from the card.
        writeBackendModel(workspace, "Order");
        writeBackendModel(workspace, "User");
        writeBackendCard(workspace, "User");

        var report = FoundationCardIntegrity.check(
                workspace, manifest, FoundationSymbolRegistry.buildFromWorkspace(workspace));

        assertThat(report.staleDataTypes()).isEmpty();
    }
}
