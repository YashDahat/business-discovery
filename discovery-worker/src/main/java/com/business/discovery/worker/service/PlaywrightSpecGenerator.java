package com.business.discovery.worker.service;

import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FeatureSpec;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.business.discovery.worker.util.ArchitectureJsonUtil;
import com.business.discovery.worker.util.SeededCredentialFinder;
import com.business.discovery.worker.util.SlugUtil;
import com.business.discovery.worker.util.TestIdInventory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Authors ONE Playwright spec per frontend feature into {@code frontend/e2e/<feature>.spec.ts} —
 * and nothing else. All Playwright setup (playwright.config.ts + the @playwright/test dev
 * dependency) is deterministic scaffold written by {@code FrontendGeneratorNode}; this generator
 * writes only test content, exactly the way the frontend split already works (LLM writes content,
 * scaffold writes config).
 *
 * <p>Context handed to the spec LLM per feature: the feature's flow instruction, the route
 * manifest (routes.ts), the harvested data-testid selector inventory, and the seeded login
 * credentials. Backend-only features (no frontend files) are skipped — they have no browser flow.
 */
@Service
@Slf4j
public class PlaywrightSpecGenerator {

    private static final int SELECTOR_CONTEXT_MAX_CHARS = 8000;

    private final LlmGeneratorService llm;

    public PlaywrightSpecGenerator(@Qualifier("geminiFlash") LlmGeneratorService llm) {
        this.llm = llm;
    }

    /** @return number of spec files written. Best-effort — a single feature failing does not abort. */
    public int generateSpecs(WorkerContext ctx) {
        Path workspace = ctx.getWorkspaceDir();
        if (!ArchitectureJsonUtil.exists(workspace)) {
            log.warn("[PlaywrightSpecGenerator] No ARCHITECTURE.json — cannot derive features, skipping");
            return 0;
        }

        ArchitectureSpec spec;
        try {
            spec = ArchitectureJsonUtil.read(workspace);
        } catch (IOException e) {
            log.warn("[PlaywrightSpecGenerator] Could not read ARCHITECTURE.json: {}", e.getMessage());
            return 0;
        }
        List<FeatureSpec> features = spec.getFeatures();
        if (features == null || features.isEmpty()) {
            log.warn("[PlaywrightSpecGenerator] No features in plan — no specs to generate");
            return 0;
        }

        String routes = readRoutes(workspace);
        String selectors = formatSelectors(TestIdInventory.writeJson(workspace));
        String credentials = formatCredentials(workspace);

        Path e2eDir = workspace.resolve("frontend/e2e");
        try {
            Files.createDirectories(e2eDir);
        } catch (IOException e) {
            log.warn("[PlaywrightSpecGenerator] Could not create frontend/e2e: {}", e.getMessage());
            return 0;
        }

        int written = 0;
        for (FeatureSpec feature : features) {
            if (!hasFrontendFiles(feature)) continue; // backend-only feature — no browser journey
            String name = feature.getFeatureDisplayName() != null
                    ? feature.getFeatureDisplayName() : feature.getFeatureName();
            try {
                String content = llm.generateE2eSpec(name, buildInstruction(feature),
                        routes, selectors, credentials);
                if (content == null || content.isBlank()) {
                    log.warn("[PlaywrightSpecGenerator] Empty spec for feature '{}' — skipping", name);
                    continue;
                }
                Path out = e2eDir.resolve(SlugUtil.toSlug(feature.getFeatureName()) + ".spec.ts");
                Files.writeString(out, content.endsWith("\n") ? content : content + "\n");
                written++;
                log.info("[PlaywrightSpecGenerator] Wrote e2e spec for feature '{}' → {}",
                        name, workspace.relativize(out));
            } catch (Exception e) {
                log.warn("[PlaywrightSpecGenerator] Spec generation failed for feature '{}': {}",
                        name, e.getMessage());
            }
        }
        log.info("[PlaywrightSpecGenerator] Generated {} e2e spec(s)", written);
        return written;
    }

    // ── Context builders ─────────────────────────────────────────────────────

    private static boolean hasFrontendFiles(FeatureSpec f) {
        List<String> paths = f.getFilePaths();
        return paths != null && paths.stream().anyMatch(p -> p != null && p.replace('\\', '/').contains("frontend/"));
    }

    private static String buildInstruction(FeatureSpec f) {
        StringBuilder sb = new StringBuilder();
        if (f.getFeatureInstruction() != null && !f.getFeatureInstruction().isBlank()) {
            sb.append(f.getFeatureInstruction().trim()).append("\n\n");
        }
        List<String> paths = f.getFilePaths();
        if (paths != null && !paths.isEmpty()) {
            sb.append("Frontend files in this feature (pages/components under test):\n");
            paths.stream()
                 .filter(p -> p != null && p.replace('\\', '/').contains("frontend/"))
                 .forEach(p -> sb.append("  - ").append(p).append('\n'));
        }
        return sb.toString().trim();
    }

    private String readRoutes(Path workspace) {
        Path routes = workspace.resolve("frontend/src/routes.ts");
        try {
            if (Files.exists(routes)) return Files.readString(routes);
        } catch (IOException e) {
            log.warn("[PlaywrightSpecGenerator] Could not read routes.ts: {}", e.getMessage());
        }
        return "(routes.ts not found — use only paths you can infer from the feature)";
    }

    private static String formatSelectors(Map<String, Set<String>> inventory) {
        if (inventory == null || inventory.isEmpty()) {
            return "(no data-testid selectors found in the frontend)";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Set<String>> e : inventory.entrySet()) {
            if (sb.length() > SELECTOR_CONTEXT_MAX_CHARS) {
                sb.append("... (selector list truncated)\n");
                break;
            }
            sb.append(e.getKey()).append(": ").append(String.join(", ", e.getValue())).append('\n');
        }
        return sb.toString();
    }

    private String formatCredentials(Path workspace) {
        List<SeededCredentialFinder.Credential> creds = SeededCredentialFinder.find(
                workspace.resolve("backend/src/main/java"),
                workspace.resolve("backend/src/main/resources/application.properties"));
        if (creds == null || creds.isEmpty()) return "(none seeded)";
        StringBuilder sb = new StringBuilder();
        for (SeededCredentialFinder.Credential c : creds) {
            sb.append("identifier=").append(c.identifier())
              .append("  password=").append(c.password())
              .append("  (from ").append(c.source()).append(")\n");
        }
        return sb.toString();
    }
}
