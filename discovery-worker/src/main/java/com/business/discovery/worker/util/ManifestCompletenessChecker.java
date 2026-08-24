package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FeatureSpec;
import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.PublicFunction;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Enforcement Point A (plan time) of the ARCHITECTURE.json completeness invariant — the mechanical
 * detection half. Finds frontend modules that are <em>referenced</em> (by a file's structured
 * {@code imports_from} OR by an {@code @/…} path named in prose) but <em>provided</em> by neither a
 * planned {@code files[]} entry nor a foundation file already on disk.
 *
 * <p>Why prose, not just {@code imports_from}: enrichment leaves {@code imports_from} ~27% empty and
 * omits shared wrappers, so the existing {@code validateCrossReferences} (imports_from-only) missed
 * {@code AdminLayout} entirely — it lived only in the feature prose ("wrapped in {@code <AdminLayout>}
 * from {@code @/components/AdminLayout}"). Why on-disk: the foundation ships {@code SiteLayout} etc.,
 * which are NOT in {@code files[]}; without checking disk we would wrongly flag and re-generate them.
 *
 * <p>Detection is mechanical and pure; deciding what to do with a miss (proper spec vs bare stub) is
 * the caller's / the LLM 2nd-pass's job. See {@code docs/architecture-json-completeness-plan.md} §3, §8.
 */
@Slf4j
public final class ManifestCompletenessChecker {

    private static final String FRONTEND_ROOT = "frontend/src/";
    private static final String BACKEND_ROOT = "backend/";
    private static final String BACKEND_SRC = "backend/src/main/java";

    /** {@code @/foo/Bar} alias references in prose. Extension excluded — prose rarely carries one. */
    private static final Pattern ALIAS_REF = Pattern.compile("@/[A-Za-z0-9_/-]+");
    /** A prose ref is trusted only when its final segment names a component (PascalCase) or a hook. */
    private static final Pattern COMPONENT_OR_HOOK = Pattern.compile("[A-Z][A-Za-z0-9]*|use[A-Z][A-Za-z0-9]*");

    /** A bare {@code useXxx} hook symbol named in prose (no {@code @/} path). */
    private static final Pattern HOOK_SYMBOL = Pattern.compile("\\buse[A-Z][A-Za-z0-9]*\\b");
    /** {@code export [default] [async] function|const useXxx} declaration on disk. */
    private static final Pattern ON_DISK_HOOK_DECL = Pattern.compile(
            "export\\s+(?:default\\s+)?(?:async\\s+)?(?:function|const)\\s+(use[A-Z][A-Za-z0-9]*)");
    /** {@code export { useXxx, ... }} re-export on disk. */
    private static final Pattern ON_DISK_REEXPORT = Pattern.compile("export\\s*\\{([^}]*)}");

    private ManifestCompletenessChecker() {}

    /** A referenced-but-absent frontend module, with the files/features that referenced it. */
    public record MissingRef(String importPath, Set<String> referencedBy) {}

    /** A hook symbol named in enrichment prose that no service backs, no file declares, and the foundation does not ship. */
    public record DanglingHook(String hookName, Set<String> referencedBy) {}

    /**
     * @param spec      the (enriched) architecture spec
     * @param workspace the worker workspace; its {@code frontend/src} is scanned for foundation files
     * @return referenced frontend modules with no planned entry and no file on disk (path carries an extension)
     */
    public static List<MissingRef> findMissingFrontend(ArchitectureSpec spec, Path workspace) {
        Set<String> provided = new TreeSet<>();

        // Provided (1): planned frontend files[].
        for (FileSpec f : nz(spec.getFiles())) {
            String p = f.getFilePath();
            if (p != null && p.startsWith(FRONTEND_ROOT)) provided.add(noExt(p));
        }
        // Provided (2): foundation files already on disk (SiteLayout, ui/*, cart spine, …).
        Path fsrc = workspace.resolve(FRONTEND_ROOT);
        if (Files.exists(fsrc)) {
            try (Stream<Path> s = Files.walk(fsrc)) {
                s.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".ts") || p.toString().endsWith(".tsx"))
                 .forEach(p -> provided.add(noExt(workspace.relativize(p).toString().replace('\\', '/'))));
            } catch (IOException e) {
                log.warn("[ManifestCompletenessChecker] Could not scan {}: {}", fsrc, e.getMessage());
            }
        }

        // Referenced → who referenced it (structured imports_from + prose).
        Map<String, Set<String>> referenced = new LinkedHashMap<>();
        for (FileSpec f : nz(spec.getFiles())) {
            String owner = f.getFilePath() != null ? f.getFilePath() : f.getFileName();
            for (String imp : nz(f.getImportsFrom())) {          // structured — trusted as-is
                addRef(referenced, normalize(imp), owner);
            }
            for (String ref : proseRefs(f.getDescription())) {   // file-level prose
                addRef(referenced, ref, owner);
            }
        }
        for (FeatureSpec ft : nz(spec.getFeatures())) {          // feature-level prose (where AdminLayout lived)
            for (String ref : proseRefs(ft.getFeatureInstruction())) {
                addRef(referenced, ref, "feature:" + ft.getFeatureName());
            }
        }

        List<MissingRef> missing = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : referenced.entrySet()) {
            String pathNoExt = e.getKey();
            if (pathNoExt == null || !pathNoExt.startsWith(FRONTEND_ROOT)) continue;
            if (provided.contains(pathNoExt)) continue;
            missing.add(new MissingRef(withExt(pathNoExt), new TreeSet<>(e.getValue())));
        }
        return missing;
    }

    /**
     * Backend completeness: finds {@code backend/} Java classes referenced via {@code imports_from}
     * that resolve to neither a planned {@code files[]} entry nor a foundation {@code .java} file on
     * disk. Path-based only — Java refs carry no {@code @/} alias and backend {@code imports_from} is
     * well-populated with real paths, so no prose scan is needed. On-disk awareness prevents stubbing
     * foundation classes (auth spine, config).
     *
     * <p>Does NOT catch classes invented only at generation time (e.g. a DTO the generator writes into
     * code but the plan never mentioned — {@code OrderItemResponse}). Those never appear in the manifest
     * and are a gen-time concern (javac "cannot find symbol"), not plan-time.
     */
    public static List<MissingRef> findMissingBackend(ArchitectureSpec spec, Path workspace) {
        Set<String> provided = new TreeSet<>();
        for (FileSpec f : nz(spec.getFiles())) {
            String p = f.getFilePath();
            if (p != null && p.startsWith(BACKEND_ROOT)) provided.add(p);
        }
        Path bsrc = workspace.resolve(BACKEND_SRC);
        if (Files.exists(bsrc)) {
            try (Stream<Path> s = Files.walk(bsrc)) {
                s.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".java"))
                 .forEach(p -> provided.add(workspace.relativize(p).toString().replace('\\', '/')));
            } catch (IOException e) {
                log.warn("[ManifestCompletenessChecker] Could not scan {}: {}", bsrc, e.getMessage());
            }
        }

        Map<String, Set<String>> referenced = new LinkedHashMap<>();
        for (FileSpec f : nz(spec.getFiles())) {
            String owner = f.getFilePath() != null ? f.getFilePath() : f.getFileName();
            for (String imp : nz(f.getImportsFrom())) {
                if (imp != null && imp.trim().startsWith(BACKEND_ROOT)) addRef(referenced, imp.trim(), owner);
            }
        }

        List<MissingRef> missing = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : referenced.entrySet()) {
            if (!provided.contains(e.getKey())) {
                missing.add(new MissingRef(e.getKey(), new TreeSet<>(e.getValue())));
            }
        }
        return missing;
    }

    /**
     * Hook-symbol completeness: finds {@code useXxx} hooks NAMED IN ENRICHMENT PROSE (a file's
     * {@code file_role}/{@code description} or a feature's {@code feature_instruction}) that nothing
     * will provide — the abs-fitness defect, where the brief told {@code ClassForm} to consume
     * {@code useCreateGymClass}/{@code useUpdateGymClass} but no file declared them (ARCHITECTURE.json
     * exporter set = NONE) → TS2724 → 30 wasted fix rounds.
     *
     * <p>Written for the target state (CRUD hooks are DERIVED by the mechanical generator, §3 of the
     * design doc): a hook is PROVIDED when
     * <ol>
     *   <li>a backend handler/service method forward-maps to it via {@link HookNaming} (the generator
     *       will emit it) — so a service-backed hook like {@code useCreateGymClass} is NOT flagged; or</li>
     *   <li>some file declares it as a {@code public_function} (a composite, LLM-authored hook); or</li>
     *   <li>the foundation already ships it on disk ({@code useAuth}, {@code useCart}, {@code useCheckout}).</li>
     * </ol>
     * Anything else is a capability the enrichment invented with no backing — a genuine dangling hook.
     * Complements (does not duplicate) {@link #findMissingFrontend}, which is path-level; this is
     * symbol-level. Detection only — the caller decides whether to warn, repair the prose, or fail.
     */
    public static List<DanglingHook> findDanglingHooks(ArchitectureSpec spec, Path workspace) {
        Set<String> provided = new TreeSet<>();

        for (FileSpec f : nz(spec.getFiles())) {
            // (1) hooks the generator will emit from every backend handler/service method.
            if (f.getFilePath() != null && f.getFilePath().startsWith(BACKEND_ROOT)) {
                for (PublicFunction pf : nz(f.getPublicFunctions())) {
                    if (pf != null && pf.getName() != null) {
                        String h = HookNaming.hookFor(pf.getName());
                        if (h != null) provided.add(h);
                    }
                }
            }
            // (2) composite / already-planned hooks declared as a public_function.
            for (PublicFunction pf : nz(f.getPublicFunctions())) {
                if (pf != null && pf.getName() != null && HOOK_SYMBOL.matcher(pf.getName().trim()).matches()) {
                    provided.add(pf.getName().trim());
                }
            }
        }
        // (3) foundation hooks already on disk (pre-cloned before planning).
        provided.addAll(scanOnDiskHooks(workspace.resolve(FRONTEND_ROOT)));

        // Referenced hook symbols in prose → who named them.
        Map<String, Set<String>> referenced = new LinkedHashMap<>();
        for (FileSpec f : nz(spec.getFiles())) {
            String owner = f.getFilePath() != null ? f.getFilePath() : f.getFileName();
            for (String sym : hookSymbols(f.getFileRole())) addRef(referenced, sym, owner);
            for (String sym : hookSymbols(f.getDescription())) addRef(referenced, sym, owner);
        }
        for (FeatureSpec ft : nz(spec.getFeatures())) {
            for (String sym : hookSymbols(ft.getFeatureInstruction())) {
                addRef(referenced, sym, "feature:" + ft.getFeatureName());
            }
        }

        List<DanglingHook> dangling = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : referenced.entrySet()) {
            if (!provided.contains(e.getKey())) {
                dangling.add(new DanglingHook(e.getKey(), new TreeSet<>(e.getValue())));
            }
        }
        return dangling;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** All {@code useXxx} symbols named in a prose blob. */
    private static List<String> hookSymbols(String prose) {
        List<String> out = new ArrayList<>();
        if (prose == null || prose.isBlank()) return out;
        Matcher m = HOOK_SYMBOL.matcher(prose);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** Scans {@code frontend/src} for exported {@code useXxx} hook symbols (declarations + re-exports). */
    private static Set<String> scanOnDiskHooks(Path fsrc) {
        Set<String> found = new TreeSet<>();
        if (!Files.exists(fsrc)) return found;
        try (Stream<Path> s = Files.walk(fsrc)) {
            s.filter(Files::isRegularFile)
             .filter(p -> p.toString().endsWith(".ts") || p.toString().endsWith(".tsx"))
             .forEach(p -> {
                 try {
                     String content = Files.readString(p);
                     Matcher d = ON_DISK_HOOK_DECL.matcher(content);
                     while (d.find()) found.add(d.group(1));
                     Matcher r = ON_DISK_REEXPORT.matcher(content);
                     while (r.find()) {
                         for (String part : r.group(1).split(",")) {
                             String sym = part.trim().split("\\s+")[0].trim();
                             if (HOOK_SYMBOL.matcher(sym).matches()) found.add(sym);
                         }
                     }
                 } catch (IOException ignored) { /* unreadable file → skip */ }
             });
        } catch (IOException e) {
            log.warn("[ManifestCompletenessChecker] Could not scan hooks under {}: {}", fsrc, e.getMessage());
        }
        return found;
    }

    /** Normalizes a structured import specifier to a workspace-relative, extension-less path, or null if non-local. */
    static String normalize(String spec) {
        if (spec == null) return null;
        String s = spec.trim();
        if (s.startsWith("@/")) return noExt(FRONTEND_ROOT + s.substring(2));
        if (s.startsWith(FRONTEND_ROOT)) return noExt(s);
        return null; // node_modules, relative, or backend — out of scope for the frontend pass
    }

    /** Extracts trusted {@code @/…} references (component/hook final segment) from a prose blob. */
    private static List<String> proseRefs(String prose) {
        List<String> out = new ArrayList<>();
        if (prose == null || prose.isBlank()) return out;
        Matcher m = ALIAS_REF.matcher(prose);
        while (m.find()) {
            String norm = normalize(m.group());          // @/x → frontend/src/x (no ext)
            if (norm == null) continue;
            String last = norm.substring(norm.lastIndexOf('/') + 1);
            if (COMPONENT_OR_HOOK.matcher(last).matches()) out.add(norm);
        }
        return out;
    }

    private static void addRef(Map<String, Set<String>> map, String path, String owner) {
        if (path == null) return;
        map.computeIfAbsent(path, k -> new TreeSet<>()).add(owner);
    }

    private static String noExt(String p) {
        return p.replace('\\', '/').replaceFirst("\\.(tsx?|jsx?)$", "");
    }

    /** Re-attaches an extension for a synthesized path: .ts for hooks/services, .tsx otherwise. */
    private static String withExt(String pathNoExt) {
        String last = pathNoExt.substring(pathNoExt.lastIndexOf('/') + 1);
        boolean tsLeaf = last.matches("use[A-Z].*")
                || last.matches(".*(Service|Client|Api|Store|Utils|Helpers|Types|Config)$");
        return pathNoExt + (tsLeaf ? ".ts" : ".tsx");
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }
}
