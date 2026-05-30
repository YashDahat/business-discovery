package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FileSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureJsonUtilTest {

    @TempDir Path tempDir;

    private ArchitectureSpec buildSpec() {
        return ArchitectureSpec.builder()
                .generatedAt("2026-05-30T10:00:00")
                .businessName("Raj Restaurant")
                .basePackage("com.rajrestaurant")
                .files(List.of(
                        FileSpec.builder()
                                .fileName("MenuItem.java")
                                .filePath("backend/src/main/java/com/rajrestaurant/model/MenuItem.java")
                                .fileType("BACKEND").layer("MODEL").status("PLANNED")
                                .description("Menu item entity").build(),
                        FileSpec.builder()
                                .fileName("MenuPage.tsx")
                                .filePath("frontend/src/pages/MenuPage.tsx")
                                .fileType("FRONTEND").layer("PAGE").status("PLANNED")
                                .description("Menu page").build()
                ))
                .build();
    }

    @Test
    void writeAndRead_roundTrip() throws IOException {
        ArchitectureSpec original = buildSpec();

        ArchitectureJsonUtil.write(tempDir, original);
        ArchitectureSpec read = ArchitectureJsonUtil.read(tempDir);

        assertThat(read.getBusinessName()).isEqualTo("Raj Restaurant");
        assertThat(read.getBasePackage()).isEqualTo("com.rajrestaurant");
        assertThat(read.getFiles()).hasSize(2);
        assertThat(read.getFiles().get(0).getFileName()).isEqualTo("MenuItem.java");
    }

    @Test
    void write_createsFileAtCorrectPath() throws IOException {
        ArchitectureJsonUtil.write(tempDir, buildSpec());

        assertThat(tempDir.resolve("docs/ARCHITECTURE.json")).exists();
    }

    @Test
    void updateFileStatus_changesOnlyTargetFile() throws IOException {
        ArchitectureJsonUtil.write(tempDir, buildSpec());

        ArchitectureJsonUtil.updateFileStatus(
                tempDir,
                "backend/src/main/java/com/rajrestaurant/model/MenuItem.java",
                "GENERATED");

        ArchitectureSpec updated = ArchitectureJsonUtil.read(tempDir);
        assertThat(updated.getFiles().get(0).getStatus()).isEqualTo("GENERATED");
        assertThat(updated.getFiles().get(1).getStatus()).isEqualTo("PLANNED");
    }

    @Test
    void findByPath_returnsCorrectFile() throws IOException {
        ArchitectureJsonUtil.write(tempDir, buildSpec());

        var result = ArchitectureJsonUtil.findByPath(
                tempDir, "frontend/src/pages/MenuPage.tsx");

        assertThat(result).isPresent();
        assertThat(result.get().getFileName()).isEqualTo("MenuPage.tsx");
    }

    @Test
    void findByType_returnsOnlyMatchingFiles() throws IOException {
        ArchitectureJsonUtil.write(tempDir, buildSpec());

        List<FileSpec> backendFiles = ArchitectureJsonUtil.findByType(tempDir, "BACKEND");

        assertThat(backendFiles).hasSize(1);
        assertThat(backendFiles.get(0).getFileName()).isEqualTo("MenuItem.java");
    }

    @Test
    void findByStatus_returnsMatchingFiles() throws IOException {
        ArchitectureJsonUtil.write(tempDir, buildSpec());
        ArchitectureJsonUtil.updateFileStatus(
                tempDir,
                "backend/src/main/java/com/rajrestaurant/model/MenuItem.java",
                "GENERATED");

        assertThat(ArchitectureJsonUtil.findByStatus(tempDir, "GENERATED")).hasSize(1);
        assertThat(ArchitectureJsonUtil.findByStatus(tempDir, "PLANNED")).hasSize(1);
    }

    @Test
    void addFile_appendsToList() throws IOException {
        ArchitectureJsonUtil.write(tempDir, buildSpec());

        FileSpec newFile = FileSpec.builder()
                .fileName("OrderService.java")
                .filePath("backend/src/main/java/com/rajrestaurant/service/OrderService.java")
                .fileType("BACKEND").layer("SERVICE").status("PLANNED")
                .description("Order processing service").build();

        ArchitectureJsonUtil.addFile(tempDir, newFile);

        assertThat(ArchitectureJsonUtil.read(tempDir).getFiles()).hasSize(3);
    }

    @Test
    void removeFile_removesOnlyTargetFile() throws IOException {
        ArchitectureJsonUtil.write(tempDir, buildSpec());

        ArchitectureJsonUtil.removeFile(
                tempDir, "frontend/src/pages/MenuPage.tsx");

        List<FileSpec> remaining = ArchitectureJsonUtil.read(tempDir).getFiles();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getFileName()).isEqualTo("MenuItem.java");
    }

    @Test
    void updateFile_replacesFullFileSpec() throws IOException {
        ArchitectureJsonUtil.write(tempDir, buildSpec());

        FileSpec updated = FileSpec.builder()
                .fileName("MenuItem.java")
                .filePath("backend/src/main/java/com/rajrestaurant/model/MenuItem.java")
                .fileType("BACKEND").layer("MODEL").status("VALIDATED")
                .description("Updated description").build();

        ArchitectureJsonUtil.updateFile(tempDir, updated);

        var result = ArchitectureJsonUtil.findByPath(
                tempDir, "backend/src/main/java/com/rajrestaurant/model/MenuItem.java");
        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo("VALIDATED");
        assertThat(result.get().getDescription()).isEqualTo("Updated description");
    }
}
