package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Build-time drift detector for the two hand-authored foundation contract cards
 * ({@code backend/FOUNDATION_CONTRACT.md} / {@code frontend/FOUNDATION_CONTRACT.md}) — the
 * card-integrity half of {@code docs/foundation-feature-manifest-plan.md} §8.
 *
 * <p>The generator binds to those cards VERBATIM, but they are hand-authored (not auto-extracted), so
 * a fenced source file whose card entry is stale ships wrong ground truth to every generated project —
 * surfacing downstream as a generated-code compile failure that looks like a codegen bug. This check
 * makes the drift LOUD at build time instead: it runs after the foundation is cloned and warns when
 * a card is absent/empty, or when a fenced DATA type ({@code model/} or {@code dto/}) exists in the
 * foundation source but has no matching symbol in {@link FoundationSymbolRegistry} (parsed from the
 * cards).
 *
 * <p><b>Warnings only — never fails the run.</b> The registry/{@link FoundationContractCard} already
 * degrade gracefully when the cards are absent; this only surfaces the drift so it is fixed at the
 * source (see {@code webapp-foundation/CLAUDE.md}'s same-commit card rule). It scans only data-shape
 * packages because the cards carry SHAPES — services/controllers/configs are fenced by name but are
 * not shape-bearing, so cross-checking them here would only produce noise.
 */
@Slf4j
public final class FoundationCardIntegrity {

    private static final String BACKEND_CARD  = "backend/FOUNDATION_CONTRACT.md";
    private static final String FRONTEND_CARD = "frontend/FOUNDATION_CONTRACT.md";
    private static final String BACKEND_SRC   = "backend/src/main/java";

    private FoundationCardIntegrity() {}

    /** Findings for logging + testing: missing cards and each stale-card data type ({@code Name (relPath)}). */
    public record Report(boolean backendCardPresent, boolean frontendCardPresent,
                         boolean registryEmpty, List<String> staleDataTypes) {
        public boolean hasDrift() {
            return !backendCardPresent || !frontendCardPresent || registryEmpty || !staleDataTypes.isEmpty();
        }
    }

    /**
     * Scans the workspace and logs any drift LOUDLY; returns the findings so the check is testable.
     *
     * @param workspace the cloned foundation/project workspace root
     * @param manifest  the declared foundation feature set (its fenced-backend names bound the scan)
     * @param registry  the symbol registry parsed from the cards (the "what the cards actually say")
     */
    public static Report check(Path workspace, FoundationManifest manifest, FoundationSymbolRegistry registry) {
        if (workspace == null) return new Report(false, false, true, List.of());

        boolean backendCard  = Files.isRegularFile(workspace.resolve(BACKEND_CARD));
        boolean frontendCard = Files.isRegularFile(workspace.resolve(FRONTEND_CARD));
        if (!backendCard) {
            log.warn("[FoundationCardIntegrity] {} is ABSENT — the generator has no backend ground truth; "
                    + "generated code will guess fenced shapes. Restore it in webapp-foundation.", BACKEND_CARD);
        }
        if (!frontendCard) {
            log.warn("[FoundationCardIntegrity] {} is ABSENT — the generator has no frontend ground truth; "
                    + "generated code will guess fenced shapes. Restore it in webapp-foundation.", FRONTEND_CARD);
        }
        if (registry.isEmpty()) {
            log.warn("[FoundationCardIntegrity] No fenced symbols parsed from the contract cards — cards "
                    + "absent/empty/unparseable. Skipping the source↔card cross-check.");
            return new Report(backendCard, frontendCard, true, List.of());
        }

        // Cross-check: a fenced DATA type present in the source but missing from its card is drift.
        List<String> drift = new ArrayList<>();
        Path javaRoot = workspace.resolve(BACKEND_SRC);
        if (Files.isDirectory(javaRoot)) {
            var fencedBackend = manifest.fencedBackendNames();
            try (Stream<Path> walk = Files.walk(javaRoot)) {
                for (Path p : walk.filter(Files::isRegularFile)
                        .filter(f -> f.toString().endsWith(".java")).toList()) {
                    String parent = p.getParent() != null && p.getParent().getFileName() != null
                            ? p.getParent().getFileName().toString() : "";
                    if (!parent.equals("model") && !parent.equals("dto")) continue;   // shape-bearing dirs only
                    String name = p.getFileName().toString().replace(".java", "");
                    if (fencedBackend.contains(name) && !registry.isFenced(name)) {
                        drift.add(name + " (" + javaRoot.relativize(p) + ")");
                    }
                }
            } catch (IOException e) {
                log.warn("[FoundationCardIntegrity] Could not scan {} for card drift: {}", javaRoot, e.getMessage());
            }
        }

        if (!drift.isEmpty()) {
            log.warn("[FoundationCardIntegrity] STALE CARD — {} fenced data type(s) exist in the foundation "
                    + "source but have NO matching symbol in {}: {}. The card ships wrong ground truth to every "
                    + "generated project. Fix it in webapp-foundation IN THE SAME COMMIT as the source change "
                    + "(see webapp-foundation/CLAUDE.md).", drift.size(), BACKEND_CARD, drift);
        } else {
            log.info("[FoundationCardIntegrity] Contract cards present; {} fenced symbol(s) parsed, no data-type "
                    + "drift detected.", registry.size());
        }
        return new Report(backendCard, frontendCard, false, drift);
    }
}
