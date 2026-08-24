package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.PublicFunction;
import com.business.discovery.worker.service.llm.PublicVariable;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The reconciled interface of every backend class, injected into the backend generation prompt as one
 * dedicated ground-truth section — the backend twin of {@link PlannedComponentPropsCard}.
 *
 * <p>Why it exists: the reconciled contract (from {@code ContractReconciler}) already reaches backend
 * generation via each file's {@code fileRole}, but only for the file BEING generated — a service has
 * no view of the DTO fields or repository methods it must call. This card surfaces the whole feature's
 * backend interfaces up front so a consumer binds to exactly what its producer exposes (the
 * service↔repo↔DTO cascade that sank worker-1dd3c0d5). Rendered from the spec's reconciled
 * public_variables (fields) + public_functions (method signatures); appended to the cacheable system
 * prompt so it rides the prefix cache, byte-identical per run.
 */
@Slf4j
public final class BackendContractCard {

    private final Map<String, String> byClass;   // simple class name -> interface

    private BackendContractCard(Map<String, String> byClass) {
        this.byClass = byClass;
    }

    public boolean isEmpty()  { return byClass.isEmpty(); }
    public int classCount()   { return byClass.size(); }

    public static BackendContractCard build(List<FileSpec> files) {
        Map<String, String> byClass = new TreeMap<>();
        if (files == null) return new BackendContractCard(byClass);

        for (FileSpec f : files) {
            if (!isBackend(f)) continue;
            String iface = renderInterface(f);
            if (iface.isBlank()) continue;
            byClass.put(className(f.getFilePath()), iface);
        }
        return new BackendContractCard(byClass);
    }

    public String toPromptSection() {
        if (byClass.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("── RECONCILED BACKEND CONTRACTS (ground truth — when you CALL or IMPLEMENT one of these "
                + "classes, use EXACTLY this interface; never invent a field, method, or signature) ──\n");
        byClass.forEach((cls, iface) -> sb.append(cls).append(": ").append(iface).append('\n'));
        sb.append("──────────────────────────────────────────────────────────────────────────────");
        return sb.toString();
    }

    // ── rendering ──────────────────────────────────────────────────────────────

    /** Fields (from public_variables) and/or method signatures (from public_functions). */
    private static String renderInterface(FileSpec f) {
        StringBuilder sb = new StringBuilder();

        List<PublicVariable> vars = f.getPublicVariables();
        if (vars != null && !vars.isEmpty()) {
            List<String> fields = new ArrayList<>();
            for (PublicVariable v : vars) {
                if (v.getName() == null || v.getName().isBlank()) continue;
                fields.add(v.getName() + ": " + (v.getType() == null ? "unknown" : v.getType()));
            }
            if (!fields.isEmpty()) sb.append("{ ").append(String.join("; ", fields)).append(" }");
        }

        List<PublicFunction> fns = f.getPublicFunctions();
        if (fns != null && !fns.isEmpty()) {
            List<String> methods = new ArrayList<>();
            for (PublicFunction pf : fns) {
                String m = renderMethod(pf);
                if (!m.isBlank()) methods.add(m);
            }
            if (!methods.isEmpty()) {
                if (sb.length() > 0) sb.append("  ");
                sb.append("methods: ").append(String.join("; ", methods));
            }
        }
        return sb.toString();
    }

    /**
     * Renders one method. ContractReconciler stores the full signature as a single parameter
     * containing '('; enrichment stores {@code name} + param list + returnType. Handle both.
     */
    private static String renderMethod(PublicFunction pf) {
        List<String> params = pf.getParameters() == null ? List.of() : pf.getParameters();
        if (params.size() == 1 && params.get(0).contains("(")) return params.get(0).trim();
        if (pf.getName() == null || pf.getName().isBlank()) return "";
        String args = String.join(", ", params);
        String ret = (pf.getReturnType() != null && !pf.getReturnType().isBlank())
                ? ": " + pf.getReturnType() : "";
        return pf.getName() + "(" + args + ")" + ret;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static boolean isBackend(FileSpec f) {
        if (f == null || f.getFilePath() == null) return false;
        String p = f.getFilePath().replace('\\', '/');
        return "BACKEND".equalsIgnoreCase(f.getFileType()) || p.endsWith(".java");
    }

    private static String className(String path) {
        String p = path.replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1).replaceFirst("\\.java$", "");
    }
}
