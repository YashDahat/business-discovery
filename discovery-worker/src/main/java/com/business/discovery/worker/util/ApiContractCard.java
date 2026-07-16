package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The API contract, read back off disk and handed to EVERY frontend generation call.
 *
 * Sibling of {@link UiComponentInventory}: same "measured reality over model memory"
 * pattern, applied to the wire contract instead of the UI kit. By the time
 * FrontendGeneratorNode runs, ApiArtifactGeneratorNode has already derived the types and
 * the typed SDK from the compile-validated backend — the ground truth exists on disk. The
 * only reason a page ever invented a field was that nothing put that truth in its prompt.
 *
 * Why this does not read ARCHITECTURE.json's imports_from: the planner is the least
 * reliable link in the chain (circuit-house 2026-07-12: 31 of 81 frontend entries came
 * back with imports_from = []), and OrderDetailsView — a component whose whole job is
 * rendering an order — was one of the blanks. It duly invented order.orderDate,
 * order.items and order.paymentInfo against a derived OrderDto that declares orderTime,
 * orderItems and no payment field at all: 64 of the run's build errors. Context that is
 * contingent on the planner remembering to ask for it is not ground truth. The filesystem
 * is.
 *
 * Byte-identical across every call in a run, so it sits at the front of the prompt and
 * rides the provider's prefix cache rather than being re-billed per file.
 */
@Slf4j
public final class ApiContractCard {

    /** Prompt-section key. Named so it reads as law, not as a hint. */
    public static final String PROMPT_KEY =
            "API CONTRACT (ground truth — derived from the compiled backend, NOT editable)";

    private static final Pattern EXPORTED_FN = Pattern.compile(
            "^export\\s+const\\s+(\\w+)\\s*=\\s*async\\s*(\\([^)]*\\))\\s*:\\s*([^=]+?)\\s*=>",
            Pattern.MULTILINE);
    private static final Pattern ROUTE_CALL = Pattern.compile(
            "apiClient\\.(get|post|put|patch|delete)\\s*<[^>]*>\\s*\\(\\s*[`'\"]([^`'\"]+)");

    private final Map<String, String> typeFiles;      // path → verbatim content
    private final Map<String, List<String>> sdk;      // path → exported signatures
    private final List<String> routes;                // "GET /api/v1/admin/orders"

    private ApiContractCard(Map<String, String> typeFiles,
                            Map<String, List<String>> sdk,
                            List<String> routes) {
        this.typeFiles = typeFiles;
        this.sdk = sdk;
        this.routes = routes;
    }

    public boolean isEmpty() { return typeFiles.isEmpty() && sdk.isEmpty(); }

    public int typeFileCount() { return typeFiles.size(); }

    public int routeCount() { return routes.size(); }

    /**
     * Reads only files carrying the derived marker. An LLM-written service that happens to
     * live in services/ is NOT contract — including it would launder a hallucination into
     * ground truth (circuit-house shipped adminOrderService.ts calling PATCH /admin/orders,
     * against a backend serving PUT /api/v1/admin/orders).
     */
    public static ApiContractCard build(Path workspace) {
        Map<String, String> types = readDerived(workspace.resolve("frontend/src/types"));
        Map<String, String> services = readDerived(workspace.resolve("frontend/src/services"));

        Map<String, List<String>> sdk = new LinkedHashMap<>();
        List<String> routes = new ArrayList<>();
        services.forEach((path, content) -> {
            List<String> signatures = new ArrayList<>();
            Matcher fn = EXPORTED_FN.matcher(content);
            while (fn.find()) {
                signatures.add(fn.group(1) + fn.group(2) + ": " + fn.group(3).trim());
            }
            if (!signatures.isEmpty()) sdk.put(path, signatures);

            Matcher route = ROUTE_CALL.matcher(content);
            while (route.find()) {
                String r = route.group(1).toUpperCase() + " " + route.group(2);
                if (!routes.contains(r)) routes.add(r);
            }
        });
        return new ApiContractCard(types, sdk, routes);
    }

    private static Map<String, String> readDerived(Path dir) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".ts"))
                 .sorted()
                 .forEach(p -> {
                     try {
                         String content = Files.readString(p);
                         if (content.startsWith("// GENERATED from the backend API contract")) {
                             out.put(p.getFileName().toString(), content);
                         }
                     } catch (IOException ignored) {
                         // unreadable file — omit rather than fail generation
                     }
                 });
        } catch (IOException e) {
            log.warn("[ApiContractCard] Could not list {}: {}", dir, e.getMessage());
        }
        return out;
    }

    /**
     * Types verbatim (a field list is the whole point — paraphrasing loses it), SDK as
     * signatures only, routes as a flat table. Circuit-house scale: ~200 lines of types
     * plus 28 routes ≈ 3-4K tokens, ~$0.0006 per Flash call.
     */
    public String toPromptSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("These types and functions ALREADY EXIST and are generated from the backend.\n")
          .append("They are the ONLY shape the API has. Rules:\n")
          .append("  - Use these exact field names. A field not declared below DOES NOT EXIST.\n")
          .append("  - Fields typed `| null` ARE nullable — guard them (value ?? fallback) rather\n")
          .append("    than assuming they are set.\n")
          .append("  - Call the backend ONLY through the SDK functions below. Never hand-write a\n")
          .append("    fetch/axios call, never re-declare these types, never create a parallel\n")
          .append("    service — the paths below already carry the correct /api/v1 prefix.\n\n");

        sb.append("── WIRE TYPES (frontend/src/types/) ──\n\n");
        typeFiles.forEach((name, content) ->
                sb.append("// ").append(name).append("\n").append(content).append("\n"));

        if (!sdk.isEmpty()) {
            sb.append("── API SDK (frontend/src/services/) — import these, do not reimplement ──\n\n");
            sdk.forEach((name, signatures) -> {
                sb.append("// ").append(name).append("\n");
                signatures.forEach(s -> sb.append("export const ").append(s).append("\n"));
                sb.append("\n");
            });
        }

        if (!routes.isEmpty()) {
            sb.append("── BACKEND ROUTES (already wired by the SDK above) ──\n");
            routes.forEach(r -> sb.append("  ").append(r).append("\n"));
        }
        return sb.toString();
    }
}
