package com.business.discovery.worker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the generated Playwright specs in-process from the worker (Chromium is baked into the
 * worker image) against the live smoke stack, and parses Playwright's JSON report into per-spec
 * outcomes. Zero LLM. The worker is on the shared docker network, so {@code baseUrl} is the same
 * {@code http://<smoke-project>:8080} alias {@code ComposeLaunchService.baseUrl()} already uses.
 *
 * <p>Resolution: {@code @playwright/test} is installed into {@code frontend/node_modules} at
 * scaffold time (FrontendGeneratorNode), and the browser binaries live in the worker image's
 * Playwright cache — both pinned to the same version, so {@code npx playwright test} from the
 * frontend dir resolves the local runner and the cached Chromium.
 */
@Service
@Slf4j
public class PlaywrightRunnerService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REPORT_FILE = "e2e-results.json"; // matches CANONICAL_PLAYWRIGHT_CONFIG

    @Value("${worker.e2e.run-timeout-minutes:8}")
    private int runTimeoutMinutes;

    /** One spec's result. */
    public record SpecOutcome(String title, String file, boolean passed, String error) {}

    /**
     * @param ran     true if Playwright executed and produced a report (false = it never ran —
     *                missing browsers, no specs, launch error; see rawError).
     * @param rawError process output tail, populated when {@code ran} is false.
     */
    public record RunResult(boolean ran, int passed, int failed, List<SpecOutcome> outcomes, String rawError) {
        public int total() { return passed + failed; }
        public List<SpecOutcome> broken() { return outcomes.stream().filter(o -> !o.passed()).toList(); }
    }

    public RunResult run(Path workspace, String baseUrl) {
        Path frontend = workspace.resolve("frontend");
        Path report = frontend.resolve(REPORT_FILE);
        try {
            Files.deleteIfExists(report); // never parse a stale report from a prior run
        } catch (IOException ignored) {
        }

        ProcessResult pr = exec(frontend, List.of("npx", "playwright", "test"), baseUrl);

        if (!Files.exists(report)) {
            log.warn("[PlaywrightRunner] No {} produced — Playwright did not run to completion", REPORT_FILE);
            return new RunResult(false, 0, 0, List.of(), tail(pr.output(), 4000));
        }
        try {
            return parse(Files.readString(report));
        } catch (Exception e) {
            log.warn("[PlaywrightRunner] Could not parse {}: {}", REPORT_FILE, e.getMessage());
            return new RunResult(false, 0, 0, List.of(), tail(pr.output(), 4000));
        }
    }

    // ── JSON report parsing ──────────────────────────────────────────────────

    private RunResult parse(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        List<SpecOutcome> outcomes = new ArrayList<>();
        JsonNode suites = root.path("suites");
        if (suites.isArray()) {
            for (JsonNode suite : suites) collectSpecs(suite, outcomes);
        }
        int passed = (int) outcomes.stream().filter(SpecOutcome::passed).count();
        int failed = outcomes.size() - passed;
        return new RunResult(true, passed, failed, outcomes, null);
    }

    /** A suite holds specs and/or nested suites (describe blocks) — recurse both. */
    private void collectSpecs(JsonNode suite, List<SpecOutcome> out) {
        JsonNode specs = suite.path("specs");
        if (specs.isArray()) {
            for (JsonNode spec : specs) {
                String title = spec.path("title").asText("");
                String file = spec.path("file").asText(suite.path("file").asText(""));
                boolean ok = spec.path("ok").asBoolean(true);
                out.add(new SpecOutcome(title, file, ok, ok ? null : firstError(spec)));
            }
        }
        JsonNode nested = suite.path("suites");
        if (nested.isArray()) {
            for (JsonNode child : nested) collectSpecs(child, out);
        }
    }

    /** First error message across the spec's test results (Playwright nests errors[].message). */
    private String firstError(JsonNode spec) {
        for (JsonNode test : spec.path("tests")) {
            for (JsonNode result : test.path("results")) {
                for (JsonNode err : result.path("errors")) {
                    String m = err.path("message").asText("");
                    if (!m.isBlank()) return oneLine(m);
                }
                String single = result.path("error").path("message").asText("");
                if (!single.isBlank()) return oneLine(single);
                String status = result.path("status").asText("");
                if (status.equals("timedOut")) return "timed out";
            }
        }
        return "failed (no error message in report)";
    }

    private static String oneLine(String s) {
        String cleaned = s.replaceAll("\\u001B\\[[;\\d]*m", "").trim(); // strip ANSI colour codes
        String first = cleaned.lines().findFirst().orElse(cleaned);
        return first.length() > 300 ? first.substring(0, 300) : first;
    }

    // ── Process helper ───────────────────────────────────────────────────────

    private record ProcessResult(int exitCode, String output) {}

    private ProcessResult exec(Path workDir, List<String> cmd, String baseUrl) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.directory(workDir.toFile());
            pb.environment().put("BASE_URL", baseUrl);
            pb.environment().put("CI", "1"); // non-interactive; no HTML report auto-open
            log.info("[PlaywrightRunner] Running: {} (BASE_URL={})", String.join(" ", cmd), baseUrl);

            Process proc = pb.start();
            StringBuilder buf = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (var in = proc.getInputStream()) {
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = in.read(chunk)) != -1) buf.append(new String(chunk, 0, n));
                } catch (IOException ignored) {
                }
            });
            reader.start();
            boolean finished = proc.waitFor(runTimeoutMinutes, TimeUnit.MINUTES);
            if (!finished) {
                proc.destroyForcibly();
                reader.join(2000);
                return new ProcessResult(124, buf + "\n[playwright timed out after " + runTimeoutMinutes + "m]");
            }
            reader.join(5000);
            return new ProcessResult(proc.exitValue(), buf.toString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new ProcessResult(-1, "playwright exec error: " + e.getMessage());
        }
    }

    private static String tail(String s, int maxChars) {
        if (s == null) return "";
        return s.length() <= maxChars ? s : "..." + s.substring(s.length() - maxChars);
    }
}
