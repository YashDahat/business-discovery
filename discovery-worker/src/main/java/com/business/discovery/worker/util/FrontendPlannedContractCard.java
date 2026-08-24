package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.FileSpec;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The planned/reconciled interface of every frontend NON-component module (hooks, services, types,
 * contexts, lib), injected into frontend generation as one dedicated ground-truth section — the
 * frontend twin of {@link BackendContractCard}.
 *
 * <p>Why it exists: the whole-feature reconciled interfaces reached BACKEND generation up front via
 * {@link BackendContractCard}, but the frontend had no equivalent — component <em>props</em> came from
 * PlannedComponentPropsCard and hook/service/type interfaces only appeared via a disk scan of files
 * ALREADY generated in prior layers. So a file calling a same-layer (not-yet-generated) hook or
 * service had no plan-based view of its signature and invented one. This card surfaces those
 * interfaces from the plan, byte-identical per run, so it rides the cached system-prompt prefix.
 *
 * <p>Components/pages are deliberately excluded — their contract is props, owned by
 * PlannedComponentPropsCard. Backend (.java) files are excluded — owned by {@link BackendContractCard}.
 * Rendering reuses {@link FileContractCard#renderInterfaceOnly(FileSpec)} for consistency.
 */
@Slf4j
public final class FrontendPlannedContractCard {

    private final Map<String, String> byModule;   // import alias (@/hooks/useMenu) -> interface

    private FrontendPlannedContractCard(Map<String, String> byModule) {
        this.byModule = byModule;
    }

    public boolean isEmpty()  { return byModule.isEmpty(); }
    public int moduleCount()  { return byModule.size(); }

    public static FrontendPlannedContractCard build(List<FileSpec> files) {
        Map<String, String> byModule = new TreeMap<>();
        if (files == null) return new FrontendPlannedContractCard(byModule);

        for (FileSpec f : files) {
            if (!isFrontendNonComponent(f)) continue;
            String iface = FileContractCard.renderInterfaceOnly(f);
            if (iface.isBlank()) continue;
            byModule.put(moduleName(f.getFilePath()), iface);
        }
        return new FrontendPlannedContractCard(byModule);
    }

    public String toPromptSection() {
        if (byModule.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("── PLANNED FRONTEND MODULE CONTRACTS (hooks/services/types — when you CALL or IMPORT "
                + "one of these, use EXACTLY this interface; never invent a field, function, or signature) ──\n");
        byModule.forEach((mod, iface) -> sb.append(mod).append(": ").append(iface).append('\n'));
        sb.append("──────────────────────────────────────────────────────────────────────────────");
        return sb.toString();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** A frontend .ts/.tsx under src/ that is NOT a component/page (those are props, owned elsewhere). */
    private static boolean isFrontendNonComponent(FileSpec f) {
        if (f == null || f.getFilePath() == null) return false;
        String p = f.getFilePath().replace('\\', '/');
        if (!p.contains("frontend/src/")) return false;
        if (!(p.endsWith(".ts") || p.endsWith(".tsx"))) return false;
        if (p.contains("/components/") || p.contains("/pages/")) return false;
        return true;
    }

    /** frontend/src/hooks/useMenu.ts -> @/hooks/useMenu (the form generators import by). */
    private static String moduleName(String path) {
        String p = path.replace('\\', '/');
        int i = p.indexOf("frontend/src/");
        String rel = i >= 0 ? p.substring(i + "frontend/src/".length()) : p;
        return "@/" + rel.replaceFirst("\\.(tsx?|jsx?)$", "");
    }
}
