package com.business.discovery.worker.util;

import com.business.discovery.worker.util.ApiInventory.Field;
import com.business.discovery.worker.util.ApiInventory.TypeDef;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic Java→TypeScript transpilation of the backend's wire types. The frontend
 * imports these files instead of LLM-imagined parallels, so an invented field
 * (plan.features) becomes a tsc compile error instead of a runtime white-screen.
 *
 * Emission targets the spec's PLANNED paths (frontend/src/types/<domain>.ts) so every
 * imports_from reference in ARCHITECTURE.json stays valid — pages import exactly what
 * the spec told them to; only the content source changed.
 */
@Slf4j
public final class TsTypeGenerator {

    private static final Map<String, String> PRIMITIVES = Map.ofEntries(
            Map.entry("String", "string"), Map.entry("UUID", "string"),
            Map.entry("LocalDate", "string"), Map.entry("LocalDateTime", "string"),
            Map.entry("Instant", "string"), Map.entry("OffsetDateTime", "string"),
            Map.entry("ZonedDateTime", "string"), Map.entry("LocalTime", "string"),
            Map.entry("int", "number"), Map.entry("Integer", "number"),
            Map.entry("long", "number"), Map.entry("Long", "number"),
            Map.entry("double", "number"), Map.entry("Double", "number"),
            Map.entry("float", "number"), Map.entry("Float", "number"),
            Map.entry("short", "number"), Map.entry("BigDecimal", "number"),
            Map.entry("BigInteger", "number"),
            Map.entry("boolean", "boolean"), Map.entry("Boolean", "boolean"),
            Map.entry("Object", "unknown"));

    private static final Pattern COLLECTION = Pattern.compile("^(?:List|Set|Collection)\\s*<(.+)>$");
    private static final Pattern MAP = Pattern.compile("^Map\\s*<\\s*[^,]+,(.+)>$");

    private TsTypeGenerator() {}

    /**
     * @param plannedTypePaths spec-planned files like frontend/src/types/trainer.ts
     * @return relativePath → file content, plus a lookup of typeName → its file path
     */
    public record Result(Map<String, String> files, Map<String, String> typeToPath) {}

    public static Result generate(ApiInventory inventory, List<String> plannedTypePaths) {
        Map<String, TypeDef> relevant = relevantTypes(inventory);
        List<String> domains = plannedTypePaths.stream()
                .map(TsTypeGenerator::domainOfPath).distinct().toList();

        // type → domain, then domain → planned path (or derived fallback)
        Map<String, String> typeToDomain = new LinkedHashMap<>();
        relevant.keySet().forEach(name ->
                typeToDomain.put(name, assignDomain(stemTokens(name), domains)));

        Map<String, String> domainToPath = new LinkedHashMap<>();
        for (String path : plannedTypePaths) domainToPath.put(domainOfPath(path), path);
        Map<String, String> typeToPath = new LinkedHashMap<>();
        typeToDomain.forEach((type, domain) -> typeToPath.put(type,
                domainToPath.computeIfAbsent(domain, d -> "frontend/src/types/" + d + ".ts")));

        // group and emit
        Map<String, List<TypeDef>> byPath = new LinkedHashMap<>();
        relevant.values().forEach(def ->
                byPath.computeIfAbsent(typeToPath.get(def.name()), k -> new ArrayList<>()).add(def));

        Map<String, String> files = new LinkedHashMap<>();
        byPath.forEach((path, defs) -> files.put(path, emitFile(path, defs, relevant, typeToPath)));
        log.info("[TsTypeGenerator] Emitting {} type file(s) covering {} backend types",
                files.size(), relevant.size());
        return new Result(files, typeToPath);
    }

    /** Types reachable from the API surface: endpoint request/response + transitive field refs. */
    static Map<String, TypeDef> relevantTypes(ApiInventory inv) {
        Map<String, TypeDef> all = inv.types();
        Map<String, TypeDef> out = new LinkedHashMap<>();
        Set<String> queue = new LinkedHashSet<>();
        for (ApiInventory.Endpoint e : inv.endpoints()) {
            if (e.requestType() != null) queue.add(e.requestType());
            if (e.responseType() != null) queue.add(e.responseType());
        }
        while (!queue.isEmpty()) {
            String name = queue.iterator().next();
            queue.remove(name);
            TypeDef def = all.get(name);
            if (def == null || out.containsKey(name)) continue;
            out.put(name, def);
            for (Field f : def.fields()) {
                for (String ref : referencedNames(f.javaType())) {
                    if (all.containsKey(ref) && !out.containsKey(ref)) queue.add(ref);
                }
            }
        }
        return out;
    }

    // ── Emission ──────────────────────────────────────────────────────────

    private static String emitFile(String path, List<TypeDef> defs,
                                   Map<String, TypeDef> known, Map<String, String> typeToPath) {
        Set<String> local = new LinkedHashSet<>();
        defs.forEach(d -> local.add(d.name()));

        // cross-file imports
        Map<String, Set<String>> importsByPath = new LinkedHashMap<>();
        for (TypeDef def : defs) {
            for (Field f : def.fields()) {
                for (String ref : referencedNames(f.javaType())) {
                    if (known.containsKey(ref) && !local.contains(ref)) {
                        importsByPath.computeIfAbsent(typeToPath.get(ref), k -> new TreeSet<>()).add(ref);
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("// GENERATED from the backend API contract — do not edit by hand.\n");
        sb.append("// Source of truth: backend controllers/DTOs (see docs/API_INVENTORY.json).\n\n");
        importsByPath.forEach((p, names) -> sb.append("import type { ")
                .append(String.join(", ", names)).append(" } from '@/types/")
                .append(domainOfPath(p)).append("';\n"));
        if (!importsByPath.isEmpty()) sb.append("\n");

        for (TypeDef def : defs) {
            if (def.isEnum()) {
                sb.append("export type ").append(def.name()).append(" = ")
                  .append(String.join(" | ", def.enumConstants().stream()
                          .map(c -> "'" + c + "'").toList()))
                  .append(";\n\n");
            } else {
                sb.append("export interface ").append(def.name()).append(" {\n");
                for (Field f : dedupeFields(def.name(), def.fields(), known.keySet())) {
                    String ts = mapType(f.javaType(), known.keySet());
                    sb.append("  ").append(f.name()).append(": ").append(ts);
                    if (!f.required()) sb.append(" | null");
                    sb.append(";\n");
                }
                sb.append("}\n\n");
            }
        }
        return sb.toString();
    }

    /**
     * ApiInventory's FIELD regex scans whole files without class-boundary tracking, so a
     * nested class re-contributes the outer DTO's fields, and a multi-variable declaration
     * (`private String a, b, c;`) yields one field whose javaType is unresolvable garbage.
     * Emitting those raw made every derived interface a TS2300/TS2717 error farm (circuit-house,
     * 2026-07-12: 85 of 149 build errors). First occurrence wins; a later duplicate only
     * replaces it when the kept type maps to `unknown` and the newcomer resolves.
     */
    static List<Field> dedupeFields(String typeName, List<Field> fields, Set<String> knownTypes) {
        Map<String, Field> byName = new LinkedHashMap<>();
        for (Field f : fields) {
            Field kept = byName.get(f.name());
            if (kept == null) {
                byName.put(f.name(), f);
            } else if ("unknown".equals(mapType(kept.javaType(), knownTypes))
                    && !"unknown".equals(mapType(f.javaType(), knownTypes))) {
                byName.put(f.name(), f);
            }
        }
        if (byName.size() < fields.size()) {
            log.info("[TsTypeGenerator] {}: dropped {} duplicate field declaration(s) from inventory",
                    typeName, fields.size() - byName.size());
        }
        return List.copyOf(byName.values());
    }

    // ── Java → TS mapping ─────────────────────────────────────────────────

    static String mapType(String javaType, Set<String> knownTypes) {
        // fully-qualified names (java.math.BigDecimal, java.util.List<...>) → simple names
        String t = javaType.trim().replaceAll("\\b[a-z][\\w]*\\.", "");
        if (t.endsWith("[]")) return mapType(t.substring(0, t.length() - 2), knownTypes) + "[]";
        Matcher col = COLLECTION.matcher(t);
        if (col.matches()) return mapType(col.group(1), knownTypes) + "[]";
        Matcher map = MAP.matcher(t);
        if (map.matches()) return "Record<string, " + mapType(map.group(1), knownTypes) + ">";
        String primitive = PRIMITIVES.get(t);
        if (primitive != null) return primitive;
        if (knownTypes.contains(t)) return t;
        return "unknown";
    }

    static List<String> referencedNames(String javaType) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\b([A-Z][A-Za-z0-9]*)\\b").matcher(javaType);
        while (m.find()) {
            String name = m.group(1);
            if (!PRIMITIVES.containsKey(name) && !name.matches("List|Set|Collection|Map")) out.add(name);
        }
        return out;
    }

    // ── Domain assignment ─────────────────────────────────────────────────

    static String domainOfPath(String path) {
        String base = path.substring(path.lastIndexOf('/') + 1);
        return base.replaceAll("\\.tsx?$", "");
    }

    /** TrainerDto → [trainer]; CreateTrialLeadRequest → [trial, lead] */
    static List<String> stemTokens(String typeName) {
        String stem = typeName
                .replaceAll("^(Create|Update|Delete|Get)", "")
                .replaceAll("(Dto|Request|Response|Entity)$", "");
        List<String> tokens = new ArrayList<>();
        for (String part : stem.split("(?<=[a-z0-9])(?=[A-Z])")) {
            if (!part.isEmpty()) tokens.add(part.toLowerCase());
        }
        return tokens;
    }

    static String assignDomain(List<String> tokens, List<String> domains) {
        String best = null;
        int bestScore = 0;
        for (String domain : domains) {
            int score = 0;
            for (String token : tokens) {
                if (tokenMatches(domain, token)) score++;
            }
            if (score > bestScore) { bestScore = score; best = domain; }
        }
        if (best != null) return best;
        return tokens.isEmpty() ? "api" : tokens.get(0);
    }

    static boolean tokenMatches(String domain, String token) {
        String d = domain.toLowerCase();
        return d.equals(token) || d.equals(token + "s") || (token.endsWith("s") && d.equals(token.substring(0, token.length() - 1)))
                || d.contains(token) || token.contains(d);
    }
}
