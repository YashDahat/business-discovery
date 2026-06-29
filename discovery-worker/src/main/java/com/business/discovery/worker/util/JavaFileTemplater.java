package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.PublicFunction;
import com.business.discovery.worker.service.llm.PublicVariable;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Template-based generation for mechanical Java layers — ENUM, REPOSITORY, and DTO.
 *
 * These file types have no business logic. Generating them from templates instead of the
 * LLM eliminates hallucinated field names, wrong generics, and wrong annotations.
 * Uses only information already present in the FileSpec (publicVariables, fileName, filePath).
 */
@Slf4j
public final class JavaFileTemplater {

    public enum TemplateType { ENUM, REPOSITORY, DTO, NONE }

    private JavaFileTemplater() {}

    public static TemplateType classify(FileSpec spec, int layerPriority) {
        if (spec == null) return TemplateType.NONE;

        // ENUM (priority 8): must have declared constants in publicVariables
        if (layerPriority == 8
                && spec.getPublicVariables() != null
                && !spec.getPublicVariables().isEmpty()) {
            return TemplateType.ENUM;
        }

        // REPOSITORY (priority 40): only standard CRUD methods — no custom query logic
        if (layerPriority == 40 && hasOnlyStandardCrud(spec)) {
            return TemplateType.REPOSITORY;
        }

        // DTO (priorities 18 and 20): field list must be declared in publicVariables
        if ((layerPriority == 18 || layerPriority == 20)
                && spec.getPublicVariables() != null
                && !spec.getPublicVariables().isEmpty()) {
            return TemplateType.DTO;
        }

        return TemplateType.NONE;
    }

    public static String generate(FileSpec spec, String basePackage, TemplateType type) {
        return switch (type) {
            case ENUM       -> generateEnum(spec, basePackage);
            case REPOSITORY -> generateRepository(spec, basePackage);
            case DTO        -> generateDto(spec, basePackage);
            case NONE       -> null;
        };
    }

    private static String generateEnum(FileSpec spec, String basePackage) {
        String pkg = extractPackage(spec.getFilePath());
        String className = spec.getFileName().replace(".java", "");
        String constants = spec.getPublicVariables().stream()
                .map(v -> "    " + v.getName().toUpperCase())
                .collect(Collectors.joining(",\n"));
        log.info("[JavaFileTemplater] Generated ENUM template for {}", spec.getFileName());
        return "package %s;\n\npublic enum %s {\n%s\n}\n".formatted(pkg, className, constants);
    }

    private static String generateRepository(FileSpec spec, String basePackage) {
        String pkg = extractPackage(spec.getFilePath());
        String className = spec.getFileName().replace(".java", "");
        String entityName = className.replace("Repository", "");
        // Try entity in model or entity subpackage; default to model
        String entityFqn = basePackage + ".model." + entityName;

        List<PublicFunction> custom = getCustomFunctions(spec);
        String customMethods = custom.isEmpty() ? "" :
                "\n" + custom.stream()
                        .map(f -> "    // " + f.getName() + "()")
                        .collect(Collectors.joining("\n")) + "\n";

        log.info("[JavaFileTemplater] Generated REPOSITORY template for {}", spec.getFileName());
        return """
                package %s;

                import %s;
                import org.springframework.data.jpa.repository.JpaRepository;
                import org.springframework.stereotype.Repository;
                import java.util.List;
                import java.util.UUID;

                @Repository
                public interface %s extends JpaRepository<%s, UUID> {%s}
                """.formatted(pkg, entityFqn, className, entityName, customMethods);
    }

    private static String generateDto(FileSpec spec, String basePackage) {
        String pkg = extractPackage(spec.getFilePath());
        String className = spec.getFileName().replace(".java", "");
        String fields = spec.getPublicVariables().stream()
                .map(v -> "    private " + resolveJavaType(v.getType()) + " " + sanitizeFieldName(v.getName()) + ";")
                .collect(Collectors.joining("\n"));
        log.info("[JavaFileTemplater] Generated DTO template for {}", spec.getFileName());
        return """
                package %s;

                import jakarta.validation.constraints.*;
                import java.util.List;
                import java.util.UUID;
                import java.time.LocalDateTime;
                import lombok.Data;
                import lombok.Builder;
                import lombok.NoArgsConstructor;
                import lombok.AllArgsConstructor;

                @Data
                @Builder
                @NoArgsConstructor
                @AllArgsConstructor
                public class %s {
                %s
                }
                """.formatted(pkg, className, fields);
    }

    private static boolean hasOnlyStandardCrud(FileSpec spec) {
        if (spec.getPublicFunctions() == null || spec.getPublicFunctions().isEmpty()) return true;
        List<String> standard = List.of(
                "findAll", "findById", "findBy", "save", "saveAll",
                "deleteById", "delete", "existsById", "count", "getById"
        );
        return spec.getPublicFunctions().stream()
                .allMatch(f -> f.getName() == null
                        || standard.stream().anyMatch(s -> f.getName().startsWith(s)));
    }

    private static List<PublicFunction> getCustomFunctions(FileSpec spec) {
        if (spec.getPublicFunctions() == null) return List.of();
        List<String> standard = List.of(
                "findAll", "findById", "findBy", "save", "saveAll",
                "deleteById", "delete", "existsById", "count", "getById"
        );
        return spec.getPublicFunctions().stream()
                .filter(f -> f.getName() != null
                        && standard.stream().noneMatch(s -> f.getName().startsWith(s)))
                .toList();
    }

    private static String extractPackage(String filePath) {
        int idx = filePath.indexOf("java/");
        if (idx < 0) return "";
        String after = filePath.substring(idx + 5);
        int lastSlash = after.lastIndexOf('/');
        return lastSlash < 0 ? "" : after.substring(0, lastSlash).replace('/', '.');
    }

    private static String resolveJavaType(String type) {
        if (type == null || type.isBlank()) return "String";
        return switch (type.toLowerCase().trim()) {
            case "string"                 -> "String";
            case "int", "integer"         -> "Integer";
            case "long"                   -> "Long";
            case "double"                 -> "Double";
            case "float"                  -> "Float";
            case "boolean"                -> "Boolean";
            case "uuid"                   -> "UUID";
            case "localdatetime", "timestamp" -> "LocalDateTime";
            case "localdate", "date"      -> "java.time.LocalDate";
            case "bigdecimal", "decimal"  -> "java.math.BigDecimal";
            default                       -> type; // enum types, nested types — keep as-is
        };
    }

    private static String sanitizeFieldName(String name) {
        if (name == null || name.isBlank()) return "value";
        // Convert UPPER_CASE to camelCase (LLM sometimes outputs enum-style names in publicVariables)
        if (name.contains("_")) {
            String[] parts = name.toLowerCase().split("_");
            StringBuilder sb = new StringBuilder(parts[0]);
            for (int i = 1; i < parts.length; i++) {
                if (!parts[i].isEmpty()) sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
            }
            return sb.toString();
        }
        return name;
    }
}
