package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FileSpec;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Symbol table for the generated project — maps Java class simple names to their
 * fully-qualified names and import statements.
 *
 * Built once from ArchitectureSpec at the start of BackendGeneratorNode.execute()
 * and passed to JavaImportResolver so import fixes are deterministic (zero LLM).
 */
@Slf4j
public final class JavaClassRegistry {

    private final Map<String, String> simpleNameToFqn = new HashMap<>();
    private final Map<String, String> simpleNameToImport = new HashMap<>();
    private final String basePackage;

    private JavaClassRegistry(String basePackage) {
        this.basePackage = basePackage != null ? basePackage : "";
    }

    public static JavaClassRegistry buildFromSpec(ArchitectureSpec spec) {
        String basePackage = spec.getBasePackage();
        // If the spec JSON was written before basePackage was a field (or the LLM omitted it),
        // derive it from the shortest common package prefix across all backend file paths.
        if (basePackage == null || basePackage.isBlank()) {
            basePackage = deriveBasePackage(spec.getFiles());
        }
        JavaClassRegistry registry = new JavaClassRegistry(basePackage);
        if (spec.getFiles() == null) return registry;

        for (FileSpec f : spec.getFiles()) {
            if (!"BACKEND".equalsIgnoreCase(f.getFileType())) continue;
            String name = f.getFileName();
            String path = f.getFilePath();
            if (name == null || !name.endsWith(".java") || path == null) continue;

            String pkg = extractPackage(path);
            if (pkg == null) continue;

            String simpleName = name.replace(".java", "");
            String fqn = pkg + "." + simpleName;
            registry.simpleNameToFqn.put(simpleName, fqn);
            registry.simpleNameToImport.put(simpleName, "import " + fqn + ";");
        }
        log.debug("[JavaClassRegistry] Built registry with {} project classes (base={})",
                registry.simpleNameToFqn.size(), registry.basePackage);
        return registry;
    }

    public Optional<String> resolveImport(String simpleName) {
        return Optional.ofNullable(simpleNameToImport.get(simpleName));
    }

    public Optional<String> resolveFqn(String simpleName) {
        return Optional.ofNullable(simpleNameToFqn.get(simpleName));
    }

    public boolean isKnown(String simpleName) {
        return simpleNameToFqn.containsKey(simpleName);
    }

    public Set<String> allSimpleNames() {
        return simpleNameToFqn.keySet();
    }

    public String getBasePackage() {
        return basePackage;
    }

    private static String extractPackage(String filePath) {
        int idx = filePath.indexOf("java/");
        if (idx < 0) return null;
        String after = filePath.substring(idx + 5);
        int lastSlash = after.lastIndexOf('/');
        return lastSlash < 0 ? null : after.substring(0, lastSlash).replace('/', '.');
    }

    // Finds the common package prefix of all backend files, e.g.
    // [com.ouza.model, com.ouza.service, com.ouza.controller] → "com.ouza"
    private static String deriveBasePackage(java.util.List<FileSpec> files) {
        if (files == null || files.isEmpty()) return "";
        String[] common = null;
        for (FileSpec f : files) {
            if (!"BACKEND".equalsIgnoreCase(f.getFileType()) || f.getFilePath() == null) continue;
            String pkg = extractPackage(f.getFilePath());
            if (pkg == null || pkg.isBlank()) continue;
            String[] parts = pkg.split("\\.");
            if (common == null) {
                common = parts;
            } else {
                int shared = 0;
                while (shared < common.length && shared < parts.length
                        && common[shared].equals(parts[shared])) shared++;
                if (shared == 0) return "";
                String[] trimmed = new String[shared];
                System.arraycopy(common, 0, trimmed, 0, shared);
                common = trimmed;
            }
        }
        return common == null ? "" : String.join(".", common);
    }
}
