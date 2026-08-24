package com.business.discovery.worker.util;

import com.business.discovery.worker.util.ApiInventory.Endpoint;
import com.business.discovery.worker.util.ApiInventory.Param;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic typed SDK: one exported async function per backend endpoint, with the
 * exact path, HTTP method, and request/response types baked in. Pages and hooks call
 * these functions instead of writing axios calls — so a wrong path, wrong method, or
 * doubled prefix cannot be written (the /leads/trial and /api/v1/api/v1 classes).
 *
 * Emission targets the spec's planned services/<domain>Service.ts paths. Paths are
 * written in full ('/api/v1/...') and pair with the axios client's empty baseURL
 * (normalized by ApiContractChecker).
 */
@Slf4j
public final class TsSdkGenerator {

    private TsSdkGenerator() {}

    /**
     * @param typeToPath from TsTypeGenerator.Result — where each wire type lives
     * @param plannedServicePaths spec-planned files like frontend/src/services/trainerService.ts
     */
    public static Map<String, String> generate(ApiInventory inventory,
                                               Map<String, String> typeToPath,
                                               List<String> plannedServicePaths) {
        List<String> domains = plannedServicePaths.stream()
                .map(TsSdkGenerator::domainOfServicePath).distinct().toList();
        Map<String, String> domainToPath = new LinkedHashMap<>();
        for (String p : plannedServicePaths) domainToPath.put(domainOfServicePath(p), p);

        // endpoint → domain (by its types' stems, else by path segment)
        Map<String, List<Endpoint>> byDomain = new LinkedHashMap<>();
        for (Endpoint e : inventory.endpoints()) {
            byDomain.computeIfAbsent(endpointDomain(e, domains), k -> new ArrayList<>()).add(e);
        }

        Map<String, String> files = new LinkedHashMap<>();
        byDomain.forEach((domain, endpoints) -> {
            String path = domainToPath.computeIfAbsent(domain,
                    d -> "frontend/src/services/" + d + "Service.ts");
            files.put(path, emitFile(endpoints, typeToPath));
        });
        log.info("[TsSdkGenerator] Emitting {} service file(s) covering {} endpoints",
                files.size(), inventory.endpoints().size());
        return files;
    }

    // ── Emission ──────────────────────────────────────────────────────────

    private static String emitFile(List<Endpoint> endpoints, Map<String, String> typeToPath) {
        // imports: each referenced wire type from its type file — request, response, AND param
        // types. Path/query params typed as enums (e.g. ReservationStatus) appear in the
        // function signature via emitFunction but were previously never added to imports, producing
        // TS2304 "Cannot find name 'ReservationStatus'" on the derived (fenced) service file that
        // ErrorFixAgent cannot edit.
        Map<String, Set<String>> importsByDomain = new LinkedHashMap<>();
        for (Endpoint e : endpoints) {
            for (String t : new String[]{e.requestType(), e.responseType()}) {
                if (t != null && typeToPath.containsKey(t)) {
                    String domain = TsTypeGenerator.domainOfPath(typeToPath.get(t));
                    importsByDomain.computeIfAbsent(domain, k -> new TreeSet<>()).add(t);
                }
            }
            // Also import enum param types used in path/query param signatures
            for (Param p : e.pathParams()) {
                String ts = TsTypeGenerator.mapType(p.javaType(), typeToPath.keySet());
                if (typeToPath.containsKey(ts)) {
                    String domain = TsTypeGenerator.domainOfPath(typeToPath.get(ts));
                    importsByDomain.computeIfAbsent(domain, k -> new TreeSet<>()).add(ts);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("// GENERATED from the backend API contract — do not edit by hand.\n");
        sb.append("// One function per endpoint; paths and types are ground truth.\n\n");
        sb.append("import apiClient from '@/api/client';\n");
        importsByDomain.forEach((domain, names) -> sb.append("import type { ")
                .append(String.join(", ", names)).append(" } from '@/types/").append(domain).append("';\n"));
        sb.append("\n");

        // Public endpoints claim plain function names; colliding admin handlers get the
        // 'admin' prefix. Order-dependent naming once gave the ADMIN route the plain name —
        // a public page calling it would 403 for anonymous visitors.
        List<Endpoint> ordered = new ArrayList<>(endpoints);
        ordered.sort(java.util.Comparator.comparing(e -> e.path().contains("/admin")));

        Set<String> usedNames = new LinkedHashSet<>();
        StringBuilder fns = new StringBuilder();
        for (Endpoint e : ordered) {
            fns.append(emitFunction(e, usedNames, typeToPath.keySet()));
        }
        sb.append(fns);
        return sb.toString();
    }

    private static String emitFunction(Endpoint e, Set<String> usedNames, Set<String> knownTypes) {
        String fnName = uniqueName(e, usedNames);

        List<String> args = new ArrayList<>();
        for (Param p : e.pathParams()) {
            args.add(p.name() + ": " + TsTypeGenerator.mapType(p.javaType(), knownTypes));
        }
        if (e.requestType() != null) {
            String reqTs = knownTypes.contains(e.requestType()) ? e.requestType() : "unknown";
            args.add("request: " + reqTs);
        }

        String responseTs = e.responseType() == null ? "void"
                : (knownTypes.contains(e.responseType()) ? e.responseType() : "unknown")
                  + (e.responseIsList() ? "[]" : "");

        // /x/{id} → template literal `/x/${id}`
        String url = e.path().replaceAll("\\{(\\w+)}", "\\${$1}");
        String urlLiteral = url.contains("${") ? "`" + url + "`" : "'" + url + "'";

        String axiosMethod = e.httpMethod().toLowerCase();
        String bodyArg = e.requestType() != null ? ", request" : "";

        StringBuilder sb = new StringBuilder();
        sb.append("export const ").append(fnName).append(" = async (")
          .append(String.join(", ", args)).append("): Promise<").append(responseTs).append("> => {\n");
        if ("void".equals(responseTs)) {
            sb.append("  await apiClient.").append(axiosMethod).append("<void>(")
              .append(urlLiteral).append(bodyArg).append(");\n");
        } else {
            sb.append("  const response = await apiClient.").append(axiosMethod)
              .append("<").append(responseTs).append(">(").append(urlLiteral).append(bodyArg).append(");\n");
            sb.append("  return response.data;\n");
        }
        sb.append("};\n\n");
        return sb.toString();
    }

    /** Handler names are usually unique per controller but can collide across a domain. */
    private static String uniqueName(Endpoint e, Set<String> used) {
        String name = e.handlerName();
        if (used.add(name)) return name;
        String prefixed = e.path().contains("/admin") ? "admin" + capitalize(name) : name + "V2";
        if (used.add(prefixed)) return prefixed;
        int i = 2;
        while (!used.add(prefixed + i)) i++;
        return prefixed + i;
    }

    // ── Domain assignment ─────────────────────────────────────────────────

    static String domainOfServicePath(String path) {
        String base = path.substring(path.lastIndexOf('/') + 1).replaceAll("\\.tsx?$", "");
        return base.replaceAll("Service$", "");
    }

    static String endpointDomain(Endpoint e, List<String> domains) {
        // Prefer the wire types' stems — they name the business concept
        for (String t : new String[]{e.responseType(), e.requestType()}) {
            if (t != null) {
                String d = TsTypeGenerator.assignDomain(TsTypeGenerator.stemTokens(t), domains);
                if (domains.contains(d)) return d;
            }
        }
        // Fall back to the first meaningful path segment after the API prefix
        String p = e.path().replaceFirst("^/api/v\\d+", "").replaceFirst("^/admin", "");
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.startsWith("{")) continue;
            String d = TsTypeGenerator.assignDomain(List.of(singular(seg.toLowerCase())), domains);
            return d;
        }
        return "api";
    }

    /**
     * Plural → singular for a path segment; covers the noun shapes API domains actually use.
     *
     * The naive "strip a trailing s" this replaces turned /api/v1/classes into the domain
     * "classe", so derivation emitted classeService.ts — a filename no planner and no model
     * would ever guess. On vikram-s-fitness-studio the plan put those functions in
     * bookingService, derivation put them in classeService, and the hook importing from the
     * former hit nine TS2305s that ended with the fix agent inventing endpoint paths.
     * Landing on "class" lets the planned and derived names agree.
     */
    private static String singular(String s) {
        if (s.length() < 3 || !s.endsWith("s")) return s;
        // Already singular despite the trailing s: class, address, status, analysis
        if (s.endsWith("ss") || s.endsWith("us") || s.endsWith("is")) return s;
        // categories → category, properties → property, amenities → amenity
        if (s.endsWith("ies")) return s.substring(0, s.length() - 3) + "y";
        // classes → class, dishes → dish, branches → branch, boxes → box
        if (s.endsWith("sses") || s.endsWith("shes") || s.endsWith("ches")
                || s.endsWith("xes") || s.endsWith("zes")) {
            return s.substring(0, s.length() - 2);
        }
        return s.substring(0, s.length() - 1);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
