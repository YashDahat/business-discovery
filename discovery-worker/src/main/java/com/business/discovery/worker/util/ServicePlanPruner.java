package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FeatureSpec;
import com.business.discovery.worker.service.llm.FileSpec;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Strips planned frontend service files that derivation did not claim.
 *
 * Every real endpoint is in ApiInventory and emitted into the derived SDK, so a service
 * the planner asks for beyond the derived set is NECESSARILY either a duplicate of a
 * derived file or a call to an endpoint that does not exist (circuit-house: seven
 * admin{Domain}Service.ts entries, none claimed by derivation, all written by Flash with
 * unprefixed paths → 31 contract mismatches). There is no legitimate service for an LLM
 * to write — services/local/ is the escape hatch for non-API modules.
 *
 * Runs in ApiArtifactGeneratorNode, the only point where the claimed set is known
 * (planning happens before the backend exists). Must scrub the stripped paths from
 * FeatureSpec.filePaths and every other file's importsFrom/dependsOn too —
 * ArchitectureJsonUtil.removeFile does not, and dangling references confuse
 * change-targeting and dependency loading on later runs.
 */
@Slf4j
public final class ServicePlanPruner {

    private ServicePlanPruner() {}

    /** Returns the stripped paths (empty when nothing was pruned). */
    public static List<String> prune(Path workspace, Set<String> claimedServicePaths) {
        if (!ArchitectureJsonUtil.exists(workspace)) return List.of();
        try {
            ArchitectureSpec spec = ArchitectureJsonUtil.read(workspace);
            if (spec.getFiles() == null) return List.of();

            List<String> stripped = spec.getFiles().stream()
                    .map(FileSpec::getFilePath)
                    .filter(ServicePlanPruner::isPrunableServicePath)
                    .filter(p -> !claimedServicePaths.contains(p))
                    .toList();
            if (stripped.isEmpty()) return List.of();

            spec.setFiles(spec.getFiles().stream()
                    .filter(f -> !stripped.contains(f.getFilePath()))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));

            for (FileSpec f : spec.getFiles()) {
                f.setImportsFrom(without(f.getImportsFrom(), stripped));
                f.setDependsOn(without(f.getDependsOn(), stripped));
            }
            if (spec.getFeatures() != null) {
                for (FeatureSpec feature : spec.getFeatures()) {
                    feature.setFilePaths(without(feature.getFilePaths(), stripped));
                }
            }

            ArchitectureJsonUtil.write(workspace, spec);
            stripped.forEach(p -> log.warn("[ServicePlanPruner] stripped {} — planned service "
                    + "not claimed by derivation (duplicate or phantom endpoint)", p));
            return stripped;
        } catch (IOException e) {
            log.warn("[ServicePlanPruner] Could not prune ARCHITECTURE.json: {}", e.getMessage());
            return List.of();
        }
    }

    private static boolean isPrunableServicePath(String path) {
        return path != null
                && path.startsWith("frontend/src/services/")
                && !path.startsWith("frontend/src/services/local/")
                && path.endsWith(".ts");
    }

    private static List<String> without(List<String> list, List<String> remove) {
        if (list == null || list.isEmpty()) return list;
        List<String> out = new ArrayList<>(list);
        out.removeAll(remove);
        return out;
    }
}
