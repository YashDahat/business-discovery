package com.business.discovery.worker.util;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FileEntry;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The deterministic gate that keeps the route registry honest, run as the pre-build pass
 * in FrontendValidationNode. Supersedes AppRouteSynthesizer, which only rebuilt App.tsx
 * when it found NO router at all — it saw a single &lt;Route&gt; in circuit-house, assumed
 * the model had done its job, and walked away from six orphaned pages.
 *
 * Three checks:
 *  1. Manifest → disk: every manifest entry resolves to a real page file. A page that a
 *     route points at but nobody built stops being a silent defect and becomes a failed
 *     build (typically a GENERATION_FAILED page — the retry regenerates it).
 *  2. Disk → manifest: every default-exporting pages/*.tsx appears in the manifest. A
 *     page the ErrorFixAgent created mid-fix is APPENDED rather than failed — hard-failing
 *     would make the agent's own repairs kill the build.
 *  3. App.tsx is re-emitted from the reconciled manifest, idempotently — which also heals
 *     any agent damage from the previous attempt.
 */
@Slf4j
public final class RouteManifestReconciler {

    private static final Pattern PAGE_DEFAULT_EXPORT =
            Pattern.compile("export\\s+default\\s+(?:function\\s+)?([A-Z][A-Za-z0-9_]*)");

    private RouteManifestReconciler() {}

    /** Returns true if the registry was reconciled; false only when there are no pages anywhere. */
    public static boolean reconcile(Path workspace) {
        Path frontendSrc = workspace.resolve("frontend/src");
        RouteManifest manifest = manifestFromPlan(workspace);
        boolean fromDisk = false;
        if (manifest == null || manifest.isEmpty()) {
            // A partial-plan update run (a page-less change) used to bail here, leaving whatever
            // App.tsx the previous attempt left behind — the drop-the-cart-route defect. Rebuild
            // the table from the pages that actually exist on disk instead.
            try {
                manifest = manifestFromDisk(frontendSrc);
            } catch (IOException e) {
                throw new WorkerException(FailureType.INFRA,
                        "Route manifest disk scan failed: " + e.getMessage(), e);
            }
            fromDisk = true;
        }
        if (manifest.isEmpty()) {
            log.warn("[RouteManifestReconciler] No pages in the plan or on disk — nothing to reconcile");
            return false;
        }

        try {
            // 1. Manifest → disk: hard gate. Only meaningful for a plan-derived manifest; a
            //    disk-scanned one names only pages that already exist by construction.
            if (!fromDisk) {
                List<String> missing = new ArrayList<>();
                for (RouteManifest.Entry e : manifest.entries()) {
                    Path page = frontendSrc.resolve(e.importPath().substring(2) + ".tsx");
                    if (!Files.exists(page)) missing.add(e.page() + " (" + e.path() + ")");
                }
                if (!missing.isEmpty()) {
                    throw new WorkerException(FailureType.CODE,
                            "Route manifest names " + missing.size() + " page(s) that do not exist on disk — "
                                    + "generation left them behind (GENERATION_FAILED?): "
                                    + String.join(", ", missing));
                }
            }

            // 2. Disk → manifest: append what generation didn't plan for.
            List<RouteManifest.Entry> extra = scanUnmanifestedPages(frontendSrc, manifest);
            if (!extra.isEmpty()) {
                manifest = manifest.withAdditional(extra);
                extra.forEach(e -> log.warn("[RouteManifestReconciler] Page {} exists on disk but not "
                        + "in the manifest — appending route {}", e.page(), e.path()));
            }

            // 3. Re-emit the derived files from the reconciled manifest; the App.tsx shell is
            //    frozen (write-if-missing) so its provider tree survives partial-plan runs.
            RouteManifestGenerator.Flags flags = RouteManifestGenerator.Flags.fromDisk(frontendSrc);
            Files.writeString(frontendSrc.resolve("routes.ts"),
                    RouteManifestGenerator.emitRoutesTs(manifest));
            Files.writeString(frontendSrc.resolve("AppRoutes.tsx"),
                    RouteManifestGenerator.emitAppRoutes(manifest, flags));
            Files.writeString(frontendSrc.resolve("AppProviders.tsx"),
                    RouteManifestGenerator.emitAppProviders(flags));
            RouteManifestGenerator.ensureAppShell(frontendSrc, flags);
            log.info("[RouteManifestReconciler] Reconciled — {} routes ({} appended from disk, "
                    + "{}), AppRoutes/AppProviders re-derived", manifest.entries().size(), extra.size(),
                    fromDisk ? "manifest rebuilt from disk" : "manifest from plan");
            return true;
        } catch (IOException e) {
            throw new WorkerException(FailureType.INFRA,
                    "Route manifest reconciliation failed: " + e.getMessage(), e);
        }
    }

    private static RouteManifest manifestFromPlan(Path workspace) {
        if (!ArchitectureJsonUtil.exists(workspace)) return null;
        try {
            ArchitectureSpec spec = ArchitectureJsonUtil.read(workspace);
            if (spec.getFiles() == null) return null;
            List<FileEntry> frontendFiles = spec.getFiles().stream()
                    .filter(f -> f.getFilePath() != null)
                    .filter(f -> "FRONTEND".equalsIgnoreCase(f.getFileType()))
                    .map(f -> new FileEntry(f.getFilePath(), FileType.FRONTEND, f.getDescription()))
                    .toList();
            return RouteManifest.fromSpec(frontendFiles);
        } catch (IOException e) {
            log.warn("[RouteManifestReconciler] Could not read ARCHITECTURE.json: {}", e.getMessage());
            return null;
        }
    }

    /** Builds a manifest purely from the default-exporting pages on disk — the partial-plan fallback. */
    private static RouteManifest manifestFromDisk(Path frontendSrc) throws IOException {
        Path pagesDir = frontendSrc.resolve("pages");
        if (!Files.isDirectory(pagesDir)) return RouteManifest.fromPages(List.of());
        List<RouteManifest.Entry> entries = new ArrayList<>();
        try (Stream<Path> s = Files.walk(pagesDir)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".tsx")).sorted().toList()) {
                if (!PAGE_DEFAULT_EXPORT.matcher(Files.readString(f)).find()) continue;
                String rel = frontendSrc.relativize(f).toString().replace('\\', '/');
                RouteManifest.Entry entry = RouteManifest.fromPagePath(rel);
                if (entry != null) entries.add(entry);
            }
        }
        return RouteManifest.fromPages(entries);
    }

    private static List<RouteManifest.Entry> scanUnmanifestedPages(Path frontendSrc,
                                                                   RouteManifest manifest) throws IOException {
        Path pagesDir = frontendSrc.resolve("pages");
        if (!Files.isDirectory(pagesDir)) return List.of();
        List<RouteManifest.Entry> extra = new ArrayList<>();
        try (Stream<Path> s = Files.walk(pagesDir)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".tsx")).sorted().toList()) {
                // Default-export imports are name-agnostic, so the file name is a safe identity —
                // but a file with no default export at all cannot be routed.
                if (!PAGE_DEFAULT_EXPORT.matcher(Files.readString(f)).find()) continue;
                String rel = frontendSrc.relativize(f).toString().replace('\\', '/');
                RouteManifest.Entry entry = RouteManifest.fromPagePath(rel);
                if (entry != null && !manifest.containsPage(entry.page())) extra.add(entry);
            }
        }
        return extra;
    }
}
