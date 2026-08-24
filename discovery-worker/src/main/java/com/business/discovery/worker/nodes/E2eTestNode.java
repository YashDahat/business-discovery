package com.business.discovery.worker.nodes;

import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.service.ComposeLaunchService;
import com.business.discovery.worker.service.ComposeLaunchService.LaunchResult;
import com.business.discovery.worker.service.ComposeLaunchService.LaunchSpec;
import com.business.discovery.worker.service.PlaywrightRunnerService;
import com.business.discovery.worker.service.PlaywrightRunnerService.RunResult;
import com.business.discovery.worker.service.PlaywrightRunnerService.SpecOutcome;
import com.business.discovery.worker.service.PlaywrightSpecGenerator;
import com.business.discovery.worker.util.GhcrImageRef;
import com.business.discovery.worker.util.SlugUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Browser-level e2e gate (ADVISORY in v1). After {@link SmokeTestNode} proves the site boots and
 * serves, this drives real user journeys in Chromium — one generated spec per feature — and
 * reports what is broken at the UI layer: the class SmokeTest's HTTP flows cannot see (the
 * checkout query-vs-route param mismatch, a missing mobile hamburger nav, the auth
 * refresh-logout race). Failures are clustered by root-cause signal so a single fix resolves
 * many flows.
 *
 * <p>v1 is measure-only and NEVER fails the run (mirrors {@code worker.smoke.flows-strict}).
 * Chromium is baked into the worker image; specs run in-process against the live stack on the
 * shared network. Phase 2 wires the clusters into {@code ErrorFixAgent} and flips
 * {@code worker.e2e.strict} to enforce.
 */
@Component
@Order(15)
@Slf4j
public class E2eTestNode implements WorkerNode {

    static final String REPORT = "docs/E2E_REPORT.md";

    private static final Pattern LOCATOR = Pattern.compile("getByTestId\\(['\"]([^'\"]+)['\"]\\)");
    private static final Pattern STATUS = Pattern.compile("\\b([45]\\d\\d)\\b");

    private final PlaywrightSpecGenerator specGenerator;
    private final PlaywrightRunnerService runner;
    private final ComposeLaunchService compose;

    @Value("${worker.e2e.enabled:true}")
    private boolean enabled;

    public E2eTestNode(PlaywrightSpecGenerator specGenerator, PlaywrightRunnerService runner,
                       ComposeLaunchService compose) {
        this.specGenerator = specGenerator;
        this.runner = runner;
        this.compose = compose;
    }

    @Override
    public void execute(WorkerContext ctx) {
        if (!enabled) {
            log.info("[E2eTestNode] Disabled via worker.e2e.enabled=false — skipping");
            return;
        }

        Path workspace = ctx.getWorkspaceDir();

        int specs = specGenerator.generateSpecs(ctx);
        if (specs == 0) {
            log.info("[E2eTestNode] No e2e specs generated — nothing to run");
            return;
        }

        String projectName = "e2e-" + shortId(ctx);
        String imageRef = GhcrImageRef.build(ctx.getGithubOwner(), ctx.getGithubRepoUrl(),
                SlugUtil.toSlug(ctx.getBusiness().getTitle()), "attempt-" + ctx.getAttemptNumber());

        log.info("[E2eTestNode] Launching e2e stack '{}' and running {} spec(s)", projectName, specs);
        try {
            LaunchResult launch = compose.launch(workspace, new LaunchSpec(projectName, imageRef, null));
            if (!launch.success()) {
                writeReport(workspace, null, "docker compose up --build failed:\n" + tail(launch.output(), 2000));
                log.warn("[E2eTestNode] e2e stack failed to launch (advisory) — see {}", REPORT);
                return;
            }

            RunResult result = runner.run(workspace, launch.baseUrl());
            writeReport(workspace, result, null);

            if (!result.ran()) {
                log.warn("[E2eTestNode] Playwright did not run (advisory). Tail:\n{}", result.rawError());
            } else {
                log.info("[E2eTestNode] e2e: {} of {} journeys passed{}",
                        result.passed(), result.total(),
                        result.failed() > 0 ? " — " + result.failed() + " broken (see " + REPORT + ")" : "");
            }
        } finally {
            compose.down(workspace, projectName, true);
        }
    }

    // ── Report + clustering ──────────────────────────────────────────────────

    private void writeReport(Path workspace, RunResult result, String launchError) {
        StringBuilder sb = new StringBuilder();
        sb.append("# E2E Flow Report\n\n")
          .append("Browser journeys (Playwright/Chromium) against the live stack — ")
          .append(LocalDateTime.now()).append(".\n")
          .append("These are runtime UI journeys: everything here already compiled and booted.\n\n");

        if (launchError != null) {
            sb.append("## Could not run\n\n```\n").append(launchError).append("\n```\n");
            write(workspace, sb.toString());
            return;
        }
        if (result == null || !result.ran()) {
            sb.append("## Playwright did not run\n\n```\n")
              .append(result == null ? "" : result.rawError()).append("\n```\n");
            write(workspace, sb.toString());
            return;
        }

        sb.append("**").append(result.passed()).append(" of ").append(result.total())
          .append(" journeys passed.**\n\n");

        List<SpecOutcome> broken = result.broken();
        if (!broken.isEmpty()) {
            Map<String, List<SpecOutcome>> clusters = new LinkedHashMap<>();
            for (SpecOutcome o : broken) {
                clusters.computeIfAbsent(clusterKey(o.error()), k -> new ArrayList<>()).add(o);
            }
            sb.append("## Failure clusters (fix the cause once → many flows recover)\n\n");
            clusters.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                    .forEach(en -> {
                        sb.append("### ").append(en.getKey()).append("  (")
                          .append(en.getValue().size()).append(")\n");
                        en.getValue().forEach(o -> sb.append("- **").append(o.title()).append("** (")
                                .append(o.file()).append(") — ").append(o.error()).append("\n"));
                        sb.append("\n");
                    });
        }

        sb.append("## Passing (").append(result.passed()).append(")\n\n");
        result.outcomes().stream().filter(SpecOutcome::passed)
              .forEach(o -> sb.append("- ").append(o.title()).append(" (").append(o.file()).append(")\n"));

        write(workspace, sb.toString());
    }

    /**
     * A stable signature for a failing journey so distinct flows that share a root cause land in
     * one cluster: a blocked/absent testid, an HTTP status, a not-visible element, a timeout, or
     * a navigation mismatch — else a specifics-stripped prefix of the message.
     */
    static String clusterKey(String error) {
        if (error == null || error.isBlank()) return "unknown failure";
        Matcher loc = LOCATOR.matcher(error);
        if (loc.find()) return "selector not found/actionable: " + loc.group(1);
        Matcher st = STATUS.matcher(error);
        if (st.find()) return "http " + st.group(1);
        String lower = error.toLowerCase();
        if (lower.contains("tobevisible") || lower.contains("not visible") || lower.contains("waiting for")) {
            return "element never became visible";
        }
        if (lower.contains("tohaveurl") || lower.contains("expected url")) return "navigation/URL mismatch";
        if (lower.contains("timeout") || lower.contains("timed out")) return "timeout";
        String norm = error.replaceAll("['\"][^'\"]*['\"]", "'…'").replaceAll("\\d+", "N").trim();
        return norm.length() > 60 ? norm.substring(0, 60) + "…" : norm;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void write(Path workspace, String content) {
        try {
            Path out = workspace.resolve(REPORT);
            Files.createDirectories(out.getParent());
            Files.writeString(out, content);
            log.info("[E2eTestNode] Wrote {}", REPORT);
        } catch (IOException e) {
            log.warn("[E2eTestNode] Could not write {}: {}", REPORT, e.getMessage());
        }
    }

    private static String shortId(WorkerContext ctx) {
        String taskId = ctx.getTaskIdStr();
        return (taskId != null && taskId.length() >= 8) ? taskId.substring(0, 8) : "local";
    }

    private static String tail(String s, int maxChars) {
        if (s == null) return "";
        return s.length() <= maxChars ? s : "..." + s.substring(s.length() - maxChars);
    }
}
