package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.ContractRecord;
import com.business.discovery.worker.service.llm.FeatureSpec;
import com.business.discovery.worker.service.llm.FileContract;
import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.PublicFunction;
import com.business.discovery.worker.service.llm.PublicVariable;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Planning-time contract reconciliation (Pro, one call per feature, after enrichment). Reconciles the
 * public interface of every file in a feature — model/DTO fields, service/repo/controller method
 * signatures, and component props — into one GROUND TRUTH so every consumer binds to exactly what its
 * producer exposes. Runs BEFORE any code is generated; the reconciled contract is written into the
 * spec so BOTH the backend and frontend generators bind to it.
 *
 * <p>Why here and not per-generator: this is the same producer/consumer drift on both sides
 * (service↔repo↔DTO on the back, page↔props↔DTO on the front) — the exact cascade that sank
 * worker-1dd3c0d5. Only planning has the whole-feature view across all layers before generation.
 *
 * <p>Write-back target is {@code fileRole} — the field BOTH generators read into the file prompt
 * ({@code file_content.txt}); {@code public_functions}/{@code public_variables} are kept in sync for
 * the frontend {@link PlannedComponentPropsCard} and the peer summaries. Best-effort: a per-feature
 * failure logs and keeps the planned interface rather than aborting the run.
 */
@Slf4j
public final class ContractReconciler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> FIELD_LAYERS  = Set.of("MODEL", "ENTITY", "DTO", "TYPE", "ENUM");
    private static final Set<String> METHOD_LAYERS = Set.of("SERVICE", "REPOSITORY", "CONTROLLER");

    private ContractReconciler() {}

    /** @return number of files whose contract was updated across all features. */
    public static int reconcile(ArchitectureSpec spec, LlmGeneratorService proLlm) {
        return reconcile(spec, proLlm, "", "");
    }

    /**
     * As above, but feeds the fenced FOUNDATION contract into every reconcile call so the reconciled
     * signatures bind to the platform spine (and adopt the {@code Integer userId} identity convention)
     * instead of drifting into UUID/email user links. Backend/SHARED features get the backend contract,
     * FRONTEND features the frontend one.
     *
     * @return number of files whose contract was updated across all features.
     */
    public static int reconcile(ArchitectureSpec spec, LlmGeneratorService proLlm,
                                String backendFoundation, String frontendFoundation) {
        return reconcile(spec, proLlm, backendFoundation, frontendFoundation, new ArrayList<>());
    }

    /**
     * As above, but also appends one {@link ContractRecord} per reconciled file to {@code records}
     * (planned interface → reconciled interface) for the docs/CONTRACTS.json observability artifact.
     * Does not change what generation consumes — that still comes from the spec fields written here.
     *
     * @return number of files whose contract was updated across all features.
     */
    public static int reconcile(ArchitectureSpec spec, LlmGeneratorService proLlm,
                                String backendFoundation, String frontendFoundation,
                                List<ContractRecord> records) {
        if (spec == null || spec.getFeatures() == null || proLlm == null) return 0;

        Map<String, FileSpec> byPath = new LinkedHashMap<>();
        for (FileSpec f : spec.getFiles() == null ? List.<FileSpec>of() : spec.getFiles()) {
            if (f.getFilePath() != null) byPath.put(normalize(f.getFilePath()), f);
        }

        int updated = 0;
        for (FeatureSpec feature : spec.getFeatures()) {
            if ("INFRA".equalsIgnoreCase(feature.getFeatureType())) continue;

            List<FileSpec> files = reconcilableFiles(feature, byPath);
            // Nothing to reconcile with fewer than two interfaces — no producer/consumer boundary.
            if (files.size() < 2) continue;

            String filesJson;
            String peerJson;
            try {
                filesJson = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(describe(files));
                peerJson  = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(peerContracts(files, byPath));
            } catch (Exception e) {
                log.warn("[ContractReconciler] Could not serialize feature '{}' — skipping: {}",
                        feature.getFeatureName(), e.getMessage());
                continue;
            }

            String foundation = "FRONTEND".equalsIgnoreCase(feature.getFeatureType())
                    ? frontendFoundation : backendFoundation;

            List<FileContract> contracts;
            try {
                contracts = proLlm.reconcileContracts(
                        feature.getFeatureName(), feature.getFeatureInstruction(), filesJson, peerJson,
                        foundation);
            } catch (RuntimeException e) {
                log.warn("[ContractReconciler] Reconcile failed for feature '{}' — keeping planned interfaces: {}",
                        feature.getFeatureName(), e.getMessage());
                continue;
            }

            for (FileContract c : contracts) {
                FileSpec target = resolve(byPath, c.module());
                if (target == null) continue;
                String planned = currentInterface(target);   // before overwrite (enrichment/planned)
                applyContract(target, c);
                if (records != null) {
                    records.add(ContractRecord.builder()
                            .featureName(feature.getFeatureName())
                            .module(target.getFilePath())
                            .kind(contractKind(target).name())
                            .plannedInterface(planned)
                            .reconciledInterface(currentInterface(target))   // after overwrite
                            .build());
                }
                updated++;
            }
            log.info("[ContractReconciler] Feature '{}': reconciled {} file contract(s)",
                    feature.getFeatureName(), contracts.size());
        }
        return updated;
    }

    /**
     * Writes the reconciled interface into fileRole (read by BOTH generators) and mirrors it into the
     * structured fields (frontend props card + peer summaries). fileRole is the ground-truth delivery;
     * the structured mirror keeps the card and peer summaries consistent.
     */
    private static void applyContract(FileSpec target, FileContract c) {
        String rendered = render(c);
        if (rendered.isBlank()) return;

        // Mark the file as reconciled — its interface is now authoritative (cross-checked against
        // producers/consumers + foundation), distinct from a merely-planned interface.
        target.setContractReconciled(true);

        String role = target.getFileRole() == null ? "" : target.getFileRole().trim();
        String marker = "RECONCILED CONTRACT (ground truth — implement EXACTLY this interface): ";
        // Idempotent: strip any prior reconciled block before re-appending (checkpointed retries).
        int prior = role.indexOf(marker);
        if (prior >= 0) role = role.substring(0, prior).trim();
        target.setFileRole((role.isEmpty() ? "" : role + "\n\n") + marker + rendered);

        if (!c.members().isEmpty()) {
            if (isComponentOrPage(target.getFilePath())) {
                // props → public_functions (one fn, params are the prop fields) so the card reads them
                List<String> params = new ArrayList<>();
                c.members().forEach(m -> params.add(m.asMember()));
                target.setPublicFunctions(new ArrayList<>(List.of(PublicFunction.builder()
                        .name(baseName(target.getFilePath())).parameters(params)
                        .returnType("JSX.Element").description("Reconciled prop contract").build())));
            } else {
                // fields → public_variables (models/DTOs/types/enums)
                List<PublicVariable> vars = new ArrayList<>();
                c.members().forEach(m -> vars.add(new PublicVariable(m.name(), m.type(), null)));
                target.setPublicVariables(vars);
            }
        }
        if (!c.methods().isEmpty()) {
            List<PublicFunction> fns = new ArrayList<>();
            c.methods().forEach(m -> fns.add(PublicFunction.builder()
                    .name(methodName(m.signature())).parameters(List.of(m.signature()))
                    .description("Reconciled method contract").build()));
            target.setPublicFunctions(fns);
        }
    }

    /** Human/LLM-readable one-line interface for the fileRole ground-truth block. */
    private static String render(FileContract c) {
        StringBuilder sb = new StringBuilder();
        if (!c.members().isEmpty()) {
            List<String> ms = new ArrayList<>();
            c.members().forEach(m -> ms.add(m.asMember()));
            sb.append("{ ").append(String.join("; ", ms)).append(" }");
        }
        if (!c.methods().isEmpty()) {
            if (sb.length() > 0) sb.append("  ");
            List<String> ss = new ArrayList<>();
            c.methods().forEach(m -> ss.add(m.signature()));
            sb.append("methods: ").append(String.join("; ", ss));
        }
        return sb.toString();
    }

    // ── feature file selection + description ──────────────────────────────────

    private static List<FileSpec> reconcilableFiles(FeatureSpec feature, Map<String, FileSpec> byPath) {
        List<FileSpec> out = new ArrayList<>();
        if (feature.getFilePaths() == null) return out;
        for (String path : feature.getFilePaths()) {
            FileSpec f = byPath.get(normalize(path));
            if (f != null && isReconcilable(f)) out.add(f);
        }
        return out;
    }

    private static boolean isReconcilable(FileSpec f) {
        return contractKind(f) != Kind.NONE;
    }

    private enum Kind { FIELDS, METHODS, PROPS, NONE }

    private static Kind contractKind(FileSpec f) {
        if (isComponentOrPage(f.getFilePath())) return Kind.PROPS;
        String layer = f.getLayer() == null ? "" : f.getLayer().toUpperCase();
        if (FIELD_LAYERS.contains(layer))  return Kind.FIELDS;
        if (METHOD_LAYERS.contains(layer)) return Kind.METHODS;
        return Kind.NONE;
    }

    private static List<Map<String, Object>> describe(List<FileSpec> files) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FileSpec f : files) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("module", f.getFilePath());
            row.put("layer", f.getLayer() == null ? "" : f.getLayer());
            row.put("kind", contractKind(f).name());
            row.put("role", f.getFileRole() == null ? "" : f.getFileRole());
            row.put("currentInterface", currentInterface(f));
            rows.add(row);
        }
        return rows;
    }

    /** Read-only interfaces of files (in ANY feature) that these files reference — bind, don't redefine. */
    private static List<Map<String, Object>> peerContracts(List<FileSpec> files, Map<String, FileSpec> byPath) {
        Set<String> own = new LinkedHashSet<>();
        for (FileSpec f : files) own.add(normalize(f.getFilePath()));

        Set<String> referenced = new LinkedHashSet<>();
        for (FileSpec f : files) {
            if (f.getImportsFrom() == null) continue;
            for (String imp : f.getImportsFrom()) referenced.add(normalize(imp));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String ref : referenced) {
            if (own.contains(ref)) continue;                 // same-feature files are in filesJson already
            FileSpec peer = byPath.get(ref);
            if (peer == null || !isReconcilable(peer)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("module", peer.getFilePath());
            row.put("kind", contractKind(peer).name());
            row.put("currentInterface", currentInterface(peer));
            rows.add(row);
        }
        return rows;
    }

    private static String currentInterface(FileSpec f) {
        Kind kind = contractKind(f);
        if (kind == Kind.FIELDS && f.getPublicVariables() != null) {
            List<String> fs = new ArrayList<>();
            f.getPublicVariables().forEach(v -> fs.add(v.getName() + ": " + v.getType()));
            return String.join("; ", fs);
        }
        if ((kind == Kind.METHODS || kind == Kind.PROPS) && f.getPublicFunctions() != null) {
            List<String> ps = new ArrayList<>();
            f.getPublicFunctions().forEach(pf -> ps.add(pf.getParameters() == null ? pf.getName()
                    : pf.getName() + "(" + String.join(", ", pf.getParameters()) + ")"));
            return String.join("; ", ps);
        }
        return "";
    }

    // ── resolution + helpers ──────────────────────────────────────────────────

    private static FileSpec resolve(Map<String, FileSpec> byPath, String module) {
        if (module == null) return null;
        String m = normalize(module);
        FileSpec direct = byPath.get(m);
        if (direct != null) return direct;
        if (m.startsWith("@/")) {
            String asPath = "frontend/src/" + m.substring(2);
            FileSpec bySrc = byPath.get(asPath);
            if (bySrc != null) return bySrc;
            for (Map.Entry<String, FileSpec> e : byPath.entrySet()) {
                if (stripExt(e.getKey()).equals(asPath)) return e.getValue();
            }
        }
        return null;
    }

    static boolean isComponentOrPage(String path) {
        if (path == null) return false;
        String p = path.replace('\\', '/');
        if (!p.contains("frontend/src/")) return false;
        if (p.contains("/components/ui/")) return false;
        return p.contains("/components/") || p.contains("/pages/");
    }

    /** Best-effort method name from a full signature (the token before "("). */
    private static String methodName(String signature) {
        int paren = signature.indexOf('(');
        String head = paren >= 0 ? signature.substring(0, paren) : signature;
        int ws = head.lastIndexOf(' ');
        return (ws >= 0 ? head.substring(ws + 1) : head).trim();
    }

    private static String baseName(String path) {
        String p = path.replace('\\', '/');
        return stripExt(p.substring(p.lastIndexOf('/') + 1));
    }

    private static String stripExt(String s) { return s.replaceFirst("\\.(tsx?|jsx?|java)$", ""); }

    private static String normalize(String path) { return path == null ? "" : path.replace('\\', '/'); }
}
