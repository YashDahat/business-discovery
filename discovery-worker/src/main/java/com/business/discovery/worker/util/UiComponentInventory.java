package com.business.discovery.worker.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Ground-truth inventory of importable UI components — replaces hardcoded prompt
 * knowledge about what Radix and shadcn export.
 *
 * The LLM habitually invents exports from training data (DialogHeader from
 * @radix-ui/react-dialog, capitalized @/components/ui/Button). Instead of
 * whack-a-mole prompt rules per symptom, this inventory is built from reality:
 *  - Radix: `node -p "Object.keys(require('<pkg>'))"` against installed node_modules
 *  - shadcn: parsed exports of the actual files in src/components/ui/
 *
 * Consumers: the generation prompt (compact AVAILABLE UI IMPORTS section) and
 * UiImportRewriter (mechanical, inventory-driven import correction).
 */
@Slf4j
public final class UiComponentInventory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern EXPORT_DECL = Pattern.compile(
            "export\\s+(?:default\\s+)?(?:async\\s+)?(?:function|const|class|interface|type|enum)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern EXPORT_BRACE = Pattern.compile("export\\s*\\{([^}]*)}");

    /** package name (e.g. @radix-ui/react-dialog) → its actual runtime exports */
    private final Map<String, List<String>> radixExports;
    /** ui file stem (e.g. dialog) → exports of src/components/ui/<stem>.tsx */
    private final Map<String, List<String>> shadcnUiExports;

    private UiComponentInventory(Map<String, List<String>> radixExports,
                                 Map<String, List<String>> shadcnUiExports) {
        this.radixExports = radixExports;
        this.shadcnUiExports = shadcnUiExports;
    }

    public Map<String, List<String>> radixExports() { return radixExports; }
    public Map<String, List<String>> shadcnUiExports() { return shadcnUiExports; }

    public boolean isEmpty() { return radixExports.isEmpty() && shadcnUiExports.isEmpty(); }

    // ── Construction ──────────────────────────────────────────────────────

    public static UiComponentInventory build(Path frontendDir) {
        return new UiComponentInventory(
                enumerateRadixExports(frontendDir),
                parseShadcnUiExports(frontendDir.resolve("src/components/ui")));
    }

    /** Every @radix-ui/* dependency in package.json → its real exports via node. */
    static Map<String, List<String>> enumerateRadixExports(Path frontendDir) {
        Map<String, List<String>> result = new TreeMap<>();
        Path pkgJson = frontendDir.resolve("package.json");
        if (!Files.exists(pkgJson)) return result;
        try {
            JsonNode deps = MAPPER.readTree(Files.readString(pkgJson)).path("dependencies");
            deps.properties().forEach(entry -> {
                String pkg = entry.getKey();
                if (!pkg.startsWith("@radix-ui/")) return;
                List<String> exports = nodeEnumerate(frontendDir, pkg);
                if (!exports.isEmpty()) result.put(pkg, exports);
            });
        } catch (IOException e) {
            log.warn("[UiInventory] Could not read package.json: {}", e.getMessage());
        }
        return result;
    }

    private static List<String> nodeEnumerate(Path frontendDir, String pkg) {
        try {
            ProcessBuilder pb = new ProcessBuilder("node", "-p",
                    "JSON.stringify(Object.keys(require('" + pkg + "')))");
            pb.directory(frontendDir.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes()).trim();
            if (!proc.waitFor(20, TimeUnit.SECONDS) || proc.exitValue() != 0) {
                log.warn("[UiInventory] node enumeration failed for {}: {}", pkg,
                        output.length() > 200 ? output.substring(0, 200) : output);
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (JsonNode n : MAPPER.readTree(output)) {
                String name = n.asText();
                // Skip internals — scopes/providers are not for app code
                if (!name.startsWith("create") && !name.endsWith("Provider")) names.add(name);
            }
            return names;
        } catch (Exception e) {
            log.warn("[UiInventory] Could not enumerate {}: {}", pkg, e.getMessage());
            return List.of();
        }
    }

    /** Exports of every installed shadcn file — the file names ARE the canonical import paths. */
    static Map<String, List<String>> parseShadcnUiExports(Path uiDir) {
        Map<String, List<String>> result = new TreeMap<>();
        if (!Files.exists(uiDir)) return result;
        try (Stream<Path> files = Files.list(uiDir)) {
            files.filter(p -> p.toString().endsWith(".tsx") || p.toString().endsWith(".ts"))
                 .forEach(file -> {
                     String stem = file.getFileName().toString().replaceAll("\\.tsx?$", "");
                     try {
                         result.put(stem, parseExports(Files.readString(file)));
                     } catch (IOException e) {
                         log.warn("[UiInventory] Could not read {}: {}", file, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.warn("[UiInventory] Could not list ui dir: {}", e.getMessage());
        }
        return result;
    }

    static List<String> parseExports(String content) {
        List<String> names = new ArrayList<>();
        Matcher decl = EXPORT_DECL.matcher(content);
        while (decl.find()) {
            if (!names.contains(decl.group(1))) names.add(decl.group(1));
        }
        Matcher brace = EXPORT_BRACE.matcher(content);
        while (brace.find()) {
            for (String part : brace.group(1).split(",")) {
                // "Foo as Bar" exports Bar; plain "Foo" exports Foo
                String[] asParts = part.trim().split("\\s+as\\s+");
                String name = asParts[asParts.length - 1].trim();
                if (!name.isEmpty() && !names.contains(name)) names.add(name);
            }
        }
        return names;
    }

    // ── Consumers ─────────────────────────────────────────────────────────

    /** Compact prompt section — the only permitted UI imports, from reality. */
    public String toPromptSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("These are the ONLY importable names, enumerated from the installed packages.\n")
          .append("Any UI import not listed here DOES NOT EXIST and will fail to compile.\n\n");
        radixExports.forEach((pkg, exports) ->
                sb.append(pkg).append(": ").append(String.join(", ", exports)).append("\n"));
        sb.append("\n");
        shadcnUiExports.forEach((stem, exports) ->
                sb.append("@/components/ui/").append(stem).append(": ")
                  .append(String.join(", ", exports)).append("\n"));
        return sb.toString();
    }

    public void writeJson(Path workspace) {
        try {
            Path out = workspace.resolve("docs/UI_INVENTORY.json");
            Files.createDirectories(out.getParent());
            Files.writeString(out, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                    Map.of("radix", radixExports, "shadcn_ui", shadcnUiExports)));
        } catch (IOException e) {
            log.warn("[UiInventory] Could not write UI_INVENTORY.json: {}", e.getMessage());
        }
    }
}
