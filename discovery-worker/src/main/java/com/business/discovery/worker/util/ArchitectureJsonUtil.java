package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FileSpec;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ArchitectureJsonUtil {

    public static final String ARCH_PATH = "docs/ARCHITECTURE.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ArchitectureJsonUtil() {}

    // ── Write ─────────────────────────────────────────────────────────────

    public static void write(Path workspace, ArchitectureSpec spec) throws IOException {
        Path target = workspace.resolve(ARCH_PATH);
        Files.createDirectories(target.getParent());
        MAPPER.writeValue(target.toFile(), spec);
    }

    // ── Read ──────────────────────────────────────────────────────────────

    public static ArchitectureSpec read(Path workspace) throws IOException {
        return MAPPER.readValue(workspace.resolve(ARCH_PATH).toFile(), ArchitectureSpec.class);
    }

    public static boolean exists(Path workspace) {
        return Files.exists(workspace.resolve(ARCH_PATH));
    }

    // ── Query ─────────────────────────────────────────────────────────────

    public static Optional<FileSpec> findByPath(Path workspace, String filePath) throws IOException {
        return read(workspace).getFiles().stream()
                .filter(f -> filePath.equals(f.getFilePath()))
                .findFirst();
    }

    public static List<FileSpec> findByType(Path workspace, String fileType) throws IOException {
        return read(workspace).getFiles().stream()
                .filter(f -> fileType.equalsIgnoreCase(f.getFileType()))
                .toList();
    }

    public static List<FileSpec> findByStatus(Path workspace, String status) throws IOException {
        return read(workspace).getFiles().stream()
                .filter(f -> status.equalsIgnoreCase(f.getStatus()))
                .toList();
    }

    // ── Update ────────────────────────────────────────────────────────────

    public static void updateFileStatus(Path workspace, String filePath, String newStatus) throws IOException {
        ArchitectureSpec spec = read(workspace);
        spec.getFiles().stream()
                .filter(f -> filePath.equals(f.getFilePath()))
                .findFirst()
                .ifPresent(f -> {
                    f.setStatus(newStatus);
                    f.setUpdatedDate(LocalDate.now().toString());
                });
        write(workspace, spec);
    }

    public static void updateFile(Path workspace, FileSpec updated) throws IOException {
        ArchitectureSpec spec = read(workspace);
        List<FileSpec> files = new ArrayList<>(spec.getFiles());
        for (int i = 0; i < files.size(); i++) {
            if (updated.getFilePath().equals(files.get(i).getFilePath())) {
                updated.setUpdatedDate(LocalDate.now().toString());
                files.set(i, updated);
                break;
            }
        }
        spec.setFiles(files);
        write(workspace, spec);
    }

    // ── Add / Remove ──────────────────────────────────────────────────────

    public static void addFile(Path workspace, FileSpec fileSpec) throws IOException {
        ArchitectureSpec spec = read(workspace);
        List<FileSpec> files = new ArrayList<>(spec.getFiles());
        files.add(fileSpec);
        spec.setFiles(files);
        write(workspace, spec);
    }

    public static void removeFile(Path workspace, String filePath) throws IOException {
        ArchitectureSpec spec = read(workspace);
        List<FileSpec> files = spec.getFiles().stream()
                .filter(f -> !filePath.equals(f.getFilePath()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        spec.setFiles(files);
        write(workspace, spec);
    }
}
