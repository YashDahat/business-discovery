package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.ApiEndpoint;
import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.PublicFunction;
import com.business.discovery.worker.service.llm.PublicVariable;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders ONE file's OWN declared spec from ARCHITECTURE.json into the per-file "exact contract" block
 * that rides the generation prompt alongside its {@code fileRole}: purpose ({@code description}),
 * fields ({@code public_variables}), method signatures ({@code public_functions}), and REST endpoints
 * ({@code api_endpoints}, incl. access tier).
 *
 * <p>Why this exists: the whole-feature cards ({@link BackendContractCard}, PlannedComponentPropsCard)
 * describe the interfaces of OTHER files so a consumer binds to them; none tells the file being
 * generated "this is exactly what YOU must expose." Backend files only got their own interface
 * incidentally (buried in the whole-feature card, framed for calling), and frontend hooks/services/types
 * got nothing but a generic role — so signatures/fields/endpoints were invented (the OrderDto/OrderService
 * Integer↔UUID drift). This block states the file's own contract prominently, per file, for both sides.
 *
 * <p>Deliberately NOT excluded from {@link BackendContractCard}: that card is a run-constant appended to
 * the cached system prompt (byte-identical every call → rides Gemini's prefix cache). Per-file exclusion
 * of the current class would break that, for a tiny saving — so the small overlap is kept on purpose.
 */
public final class FileContractCard {

    private FileContractCard() {}

    /**
     * Returns {@code fileRole} followed by the file's exact-contract block. When the spec carries no
     * description/fields/methods/endpoints, returns {@code fileRole} unchanged (never null). The
     * header states whether the contract is RECONCILED (authoritative — cross-checked by
     * ContractReconciler) or merely PLANNED (best-available, not yet reconciled).
     */
    public static String render(FileSpec spec, String fileRole) {
        String role = fileRole == null ? "" : fileRole.trim();
        if (spec == null) return role;

        List<String> lines = new ArrayList<>();

        String desc = spec.getDescription();
        if (desc != null && !desc.isBlank()) lines.add("- Purpose: " + desc.trim());

        String fields = renderFields(spec.getPublicVariables());
        if (!fields.isEmpty()) lines.add("- Fields: " + fields);

        String methods = renderMethods(spec.getPublicFunctions());
        if (!methods.isEmpty()) lines.add("- Methods: " + methods);

        String endpoints = renderEndpoints(spec.getApiEndpoints());
        if (!endpoints.isEmpty()) lines.add("- Endpoints: " + endpoints);

        if (lines.isEmpty()) return role;

        boolean reconciled = Boolean.TRUE.equals(spec.getContractReconciled());
        String header = reconciled
                ? "THIS FILE'S EXACT CONTRACT (RECONCILED ground truth — implement EXACTLY; do not invent, rename, or add):"
                : "THIS FILE'S PLANNED CONTRACT (not yet reconciled — implement to this; do not invent or rename):";

        StringBuilder sb = new StringBuilder();
        if (!role.isEmpty()) sb.append(role).append("\n\n");
        sb.append(header).append("\n");
        sb.append(String.join("\n", lines));
        return sb.toString();
    }

    /**
     * One-line interface (fields + method signatures + endpoints) for a dependency, no header/role —
     * used to stamp the AUTHORITATIVE reconciled interface onto a dependency's raw body so the body
     * can't silently override it. Returns "" when the spec carries no interface.
     */
    public static String renderInterfaceOnly(FileSpec spec) {
        if (spec == null) return "";
        List<String> parts = new ArrayList<>();
        String fields = renderFields(spec.getPublicVariables());
        if (!fields.isEmpty()) parts.add("{ " + fields + " }");
        String methods = renderMethods(spec.getPublicFunctions());
        if (!methods.isEmpty()) parts.add("methods: " + methods);
        String endpoints = renderEndpoints(spec.getApiEndpoints());
        if (!endpoints.isEmpty()) parts.add("endpoints: " + endpoints);
        return String.join("  ", parts);
    }

    private static String renderFields(List<PublicVariable> vars) {
        if (vars == null || vars.isEmpty()) return "";
        List<String> out = new ArrayList<>();
        for (PublicVariable v : vars) {
            if (v == null || v.getName() == null || v.getName().isBlank()) continue;
            String type = (v.getType() == null || v.getType().isBlank()) ? "unknown" : v.getType().trim();
            out.add(v.getName().trim() + ": " + type);
        }
        return String.join("; ", out);
    }

    private static String renderMethods(List<PublicFunction> fns) {
        if (fns == null || fns.isEmpty()) return "";
        List<String> out = new ArrayList<>();
        for (PublicFunction pf : fns) {
            String m = renderMethod(pf);
            if (!m.isBlank()) out.add(m);
        }
        return String.join("; ", out);
    }

    /**
     * Renders one method. ContractReconciler stores the full signature as a single param containing
     * '('; enrichment stores {@code name} + param list + returnType. Handle both (mirrors
     * {@link BackendContractCard}).
     */
    private static String renderMethod(PublicFunction pf) {
        if (pf == null) return "";
        List<String> params = pf.getParameters() == null ? List.of() : pf.getParameters();
        if (params.size() == 1 && params.get(0) != null && params.get(0).contains("(")) {
            return params.get(0).trim();
        }
        if (pf.getName() == null || pf.getName().isBlank()) return "";
        String args = String.join(", ", params);
        String ret = (pf.getReturnType() != null && !pf.getReturnType().isBlank())
                ? ": " + pf.getReturnType() : "";
        return pf.getName() + "(" + args + ")" + ret;
    }

    private static String renderEndpoints(List<ApiEndpoint> eps) {
        if (eps == null || eps.isEmpty()) return "";
        List<String> out = new ArrayList<>();
        for (ApiEndpoint e : eps) {
            if (e == null || e.getPath() == null || e.getPath().isBlank()) continue;
            StringBuilder s = new StringBuilder();
            if (e.getMethod() != null && !e.getMethod().isBlank()) s.append(e.getMethod().trim()).append(' ');
            s.append(e.getPath().trim());
            if (e.getRequestBody() != null && !e.getRequestBody().isBlank()) {
                s.append(" (body: ").append(e.getRequestBody().trim()).append(')');
            }
            if (e.getResponseBody() != null && !e.getResponseBody().isBlank()) {
                s.append(" → ").append(e.getResponseBody().trim());
            }
            if (e.getAccess() != null && !e.getAccess().isBlank()) {
                s.append(" [").append(e.getAccess().trim()).append(']');
            }
            out.add(s.toString());
        }
        return String.join("; ", out);
    }
}
