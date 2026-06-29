package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiler-inspired post-generation import fixer — runs deterministically using the
 * JavaClassRegistry symbol table, without any LLM call.
 *
 * Three passes:
 *   1. Fix wrong package prefix for known project classes
 *      ("import com.wrongpkg.UserService" → "import com.ouza.service.UserService")
 *   2. Remove ghost imports (project-internal classes that don't exist in the registry)
 *   3. Add missing project-internal imports for identifiers used in the file body
 */
@Slf4j
public final class JavaImportResolver {

    private static final Pattern IMPORT_LINE =
            Pattern.compile("^import\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern CLASS_REFERENCE =
            Pattern.compile("\\b([A-Z][A-Za-z0-9]+)\\b");

    // Common Java/Spring/Jakarta/Lombok types — never try to resolve these via the project registry
    private static final Set<String> STDLIB_NAMES = Set.of(
            "String", "Integer", "Long", "Double", "Float", "Boolean", "Short", "Byte", "Character",
            "List", "Map", "Set", "Optional", "UUID", "LocalDate", "LocalDateTime", "LocalTime",
            "Instant", "Duration", "BigDecimal", "BigInteger", "Object", "Class", "Enum",
            "Exception", "RuntimeException", "IllegalArgumentException", "IllegalStateException",
            "NullPointerException", "UnsupportedOperationException",
            "Override", "Autowired", "Bean", "Component", "Service", "Repository", "Controller",
            "RestController", "RequestMapping", "GetMapping", "PostMapping", "PutMapping",
            "DeleteMapping", "PatchMapping", "PathVariable", "RequestBody", "RequestParam",
            "ResponseBody", "ResponseEntity", "HttpStatus", "HttpServletRequest", "HttpServletResponse",
            "Entity", "Table", "Column", "Id", "GeneratedValue", "GenerationType", "ManyToOne",
            "OneToMany", "ManyToMany", "OneToOne", "JoinColumn", "Enumerated", "EnumType",
            "Data", "Builder", "NoArgsConstructor", "AllArgsConstructor", "Slf4j", "Getter", "Setter",
            "Configuration", "Value", "Transactional", "Async", "Scheduled", "EnableScheduling",
            "NotNull", "NotBlank", "Size", "Min", "Max", "Email", "Valid", "Validated",
            "JpaRepository", "CrudRepository", "Sort", "Page", "Pageable", "PageRequest",
            "SpringBootApplication", "FilterChain", "Authentication", "SecurityContextHolder",
            "UsernamePasswordAuthenticationToken", "UserDetails", "OncePerRequestFilter",
            "Arrays", "Collections", "Stream", "Collectors", "HashMap", "ArrayList", "LinkedHashMap",
            "JsonIgnoreProperties", "JsonProperty", "ObjectMapper",
            "Logger", "LoggerFactory"
    );

    private JavaImportResolver() {}

    public static void resolve(Path filePath, JavaClassRegistry registry) {
        if (registry.getBasePackage().isEmpty()) return;
        try {
            String content = Files.readString(filePath);
            String resolved = resolveContent(content, registry, filePath.getFileName().toString());
            if (!resolved.equals(content)) {
                Files.writeString(filePath, resolved);
            }
        } catch (IOException e) {
            log.warn("[JavaImportResolver] Could not resolve imports in {}: {}", filePath, e.getMessage());
        }
    }

    static String resolveContent(String content, JavaClassRegistry registry, String fileName) {
        List<String> lines = new ArrayList<>(List.of(content.split("\n", -1)));

        Set<String> existingImportLines = new LinkedHashSet<>();
        int lastImportIdx = -1;

        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.startsWith("import ") && t.endsWith(";")) {
                existingImportLines.add(t);
                lastImportIdx = i;
            }
        }

        List<String[]> toReplace = new ArrayList<>();   // [old, new]
        List<String> toRemove = new ArrayList<>();
        List<String> toAdd = new ArrayList<>();

        // Pass 1: fix wrong package prefix — ONLY for project-internal imports.
        // Never touch third-party imports (e.g. org.springframework.core.annotation.Order)
        // even if a project class shares the same simple name.
        for (String imp : existingImportLines) {
            Matcher m = IMPORT_LINE.matcher(imp);
            if (!m.matches()) continue;
            String fqn = m.group(1);
            if (!fqn.startsWith(registry.getBasePackage() + ".")) continue; // only fix project imports
            int lastDot = fqn.lastIndexOf('.');
            if (lastDot < 0) continue;
            String simpleName = fqn.substring(lastDot + 1);
            if (!registry.isKnown(simpleName)) continue;
            String correctFqn = registry.resolveFqn(simpleName).orElse(null);
            if (correctFqn != null && !correctFqn.equals(fqn)) {
                toReplace.add(new String[]{imp, "import " + correctFqn + ";"});
                log.info("[JavaImportResolver] Fix wrong import in {}: {} → {}", fileName, fqn, correctFqn);
            }
        }

        // Pass 2: remove ghost imports (project-internal classes not in registry)
        for (String imp : existingImportLines) {
            Matcher m = IMPORT_LINE.matcher(imp);
            if (!m.matches()) continue;
            String fqn = m.group(1);
            if (!fqn.startsWith(registry.getBasePackage() + ".")) continue;
            int lastDot = fqn.lastIndexOf('.');
            if (lastDot < 0) continue;
            String simpleName = fqn.substring(lastDot + 1);
            if (!registry.isKnown(simpleName)) {
                toRemove.add(imp);
                log.info("[JavaImportResolver] Remove ghost import in {}: {}", fileName, fqn);
            }
        }

        // Compute effective import set for Pass 3
        Set<String> effectiveImports = new LinkedHashSet<>(existingImportLines);
        for (String[] rep : toReplace) { effectiveImports.remove(rep[0]); effectiveImports.add(rep[1]); }
        effectiveImports.removeAll(toRemove);

        Set<String> importedSimpleNames = new LinkedHashSet<>();
        for (String imp : effectiveImports) {
            Matcher m = IMPORT_LINE.matcher(imp);
            if (m.matches()) {
                String fqn = m.group(1);
                int d = fqn.lastIndexOf('.');
                if (d >= 0) importedSimpleNames.add(fqn.substring(d + 1));
            }
        }

        // Pass 3: add missing project-internal imports for CamelCase identifiers in body.
        // Skip the file's own class name — a class never imports itself.
        String ownClassName = fileName.replace(".java", "");
        String body = lastImportIdx >= 0
                ? String.join("\n", lines.subList(lastImportIdx + 1, lines.size()))
                : content;
        Matcher cm = CLASS_REFERENCE.matcher(body);
        while (cm.find()) {
            String name = cm.group(1);
            if (name.equals(ownClassName)) continue; // never import self
            if (STDLIB_NAMES.contains(name) || importedSimpleNames.contains(name)) continue;
            registry.resolveImport(name).ifPresent(imp -> {
                if (!effectiveImports.contains(imp)) {
                    toAdd.add(imp);
                    importedSimpleNames.add(name);
                    effectiveImports.add(imp);
                    log.info("[JavaImportResolver] Add missing import in {}: {}", fileName, imp);
                }
            });
        }

        if (toReplace.isEmpty() && toRemove.isEmpty() && toAdd.isEmpty()) return content;

        // Apply replacements and removals
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            for (String[] rep : toReplace) {
                if (t.equals(rep[0])) { lines.set(i, rep[1]); break; }
            }
            if (toRemove.contains(lines.get(i).trim())) lines.set(i, null);
        }
        lines.removeIf(l -> l == null);

        // Insert new imports after the last import line
        if (!toAdd.isEmpty()) {
            int insertAt = -1;
            for (int i = lines.size() - 1; i >= 0; i--) {
                String t = lines.get(i).trim();
                if (t.startsWith("import ") && t.endsWith(";")) { insertAt = i + 1; break; }
            }
            if (insertAt < 0) {
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).trim().startsWith("package ")) { insertAt = i + 2; break; }
                }
            }
            if (insertAt < 0) insertAt = 0;
            for (int i = toAdd.size() - 1; i >= 0; i--) {
                lines.add(insertAt, toAdd.get(i));
            }
        }

        return String.join("\n", lines);
    }
}
