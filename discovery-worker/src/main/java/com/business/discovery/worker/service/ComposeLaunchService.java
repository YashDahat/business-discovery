package com.business.discovery.worker.service;

import com.business.discovery.worker.service.llm.ApiEndpoint;
import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.util.ArchitectureJsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Launches a generated project's docker-compose stack and asserts it actually works.
 *
 * Shared by two lifecycles:
 *  - SmokeTestNode:   launch → gates → always down (ephemeral, pre-commit)
 *  - Demo (Phase 4):  the same image, published to GHCR by ImagePublishNode, is pulled
 *                     and run by the master — this service is what proves it demoable first.
 *
 * All operations are mechanical — ProcessBuilder + HTTP polling, zero LLM calls.
 * Talks to the Docker daemon through DOCKER_HOST (docker-proxy on Mac/EC2 masters);
 * the smoke stack runs as sibling containers attached to the shared network.
 */
@Service
@Slf4j
public class ComposeLaunchService {

    private static final int COMPOSE_UP_TIMEOUT_MINUTES = 10;
    private static final Pattern ASSET_JS = Pattern.compile("/assets/[A-Za-z0-9._-]+\\.js");
    private static final Pattern PATH_PARAM = Pattern.compile("\\{[^}]+}");

    /** Docker daemon endpoint, e.g. tcp://docker-proxy:2375. Blank = local socket (dev). */
    @Value("${worker.docker.host:}")
    private String dockerHost;

    /** Docker network the worker itself runs on; the smoke app joins it so gates can reach it by alias. */
    @Value("${worker.docker.network:shared-network}")
    private String sharedNetwork;

    /** True when the worker runs inside a container (reach app via network alias); false on a dev host (published port). */
    @Value("${worker.smoke.in-container:true}")
    private boolean inContainer;

    @Value("${worker.smoke.boot-timeout-seconds:120}")
    private int bootTimeoutSeconds;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    // ── Public API ─────────────────────────────────────────────────────────

    public record LaunchSpec(String projectName, String imageRef, Integer hostPort) {}

    public record LaunchResult(boolean success, String output, String baseUrl) {}

    public record GateResult(String gate, boolean passed, String detail) {}

    public record GateReport(List<GateResult> results) {
        public boolean passed() {
            return results.stream().allMatch(GateResult::passed);
        }
        public GateResult firstFailure() {
            return results.stream().filter(r -> !r.passed()).findFirst().orElse(null);
        }
        public String summary() {
            StringBuilder sb = new StringBuilder();
            for (GateResult r : results) {
                sb.append(r.passed() ? "PASS" : "FAIL").append(" ").append(r.gate())
                  .append(" — ").append(r.detail()).append("\n");
            }
            return sb.toString();
        }
    }

    /**
     * Builds and starts the stack. The override file tags the built app image as
     * spec.imageRef so a passing stack leaves behind a publishable image.
     */
    public LaunchResult launch(Path workspace, LaunchSpec spec) {
        try {
            prepareEnvFile(workspace);
            Path override = writeOverride(workspace, spec);

            List<String> cmd = List.of("docker", "compose",
                    "-p", spec.projectName(),
                    "-f", "docker-compose.yml",
                    "-f", workspace.relativize(override).toString(),
                    "up", "--build", "-d");

            ProcessResult result = run(workspace, cmd, COMPOSE_UP_TIMEOUT_MINUTES);
            if (!result.success()) {
                return new LaunchResult(false, tail(result.output(), 4000), null);
            }
            return new LaunchResult(true, "", baseUrl(spec));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new LaunchResult(false, "launch error: " + e.getMessage(), null);
        }
    }

    /**
     * The three deterministic gates. Order matters — each assumes the previous passed.
     */
    public GateReport runGates(String baseUrl, Path workspace) {
        List<GateResult> results = new ArrayList<>();

        GateResult boot = bootGate(baseUrl);
        results.add(boot);
        if (!boot.passed()) return new GateReport(results);

        GateResult frontend = frontendGate(baseUrl);
        results.add(frontend);
        if (!frontend.passed()) return new GateReport(results);

        results.add(apiDataGate(baseUrl, workspace));
        return new GateReport(results);
    }

    public String collectLogs(Path workspace, String projectName) {
        return collectLogs(workspace, projectName, 200);
    }

    public String collectLogs(Path workspace, String projectName, int tailLines) {
        try {
            ProcessResult result = run(workspace, List.of("docker", "compose",
                    "-p", projectName, "logs", "--no-color", "--tail", String.valueOf(tailLines)), 2);
            return result.output();
        } catch (Exception e) {
            return "could not collect logs: " + e.getMessage();
        }
    }

    /** Best-effort teardown — never throws. Callers invoke from finally blocks. */
    public void down(Path workspace, String projectName, boolean removeVolumes) {
        try {
            List<String> cmd = new ArrayList<>(List.of("docker", "compose", "-p", projectName, "down", "--remove-orphans"));
            if (removeVolumes) cmd.add("-v");
            ProcessResult result = run(workspace, cmd, 3);
            if (!result.success()) {
                log.warn("[ComposeLaunch] compose down failed for {}: {}", projectName, tail(result.output(), 500));
            }
        } catch (Exception e) {
            log.warn("[ComposeLaunch] compose down error for {}: {}", projectName, e.getMessage());
        }
    }

    // ── Gates ──────────────────────────────────────────────────────────────

    private GateResult bootGate(String baseUrl) {
        long deadline = System.currentTimeMillis() + bootTimeoutSeconds * 1000L;
        String lastError = "no response";
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> resp = get(baseUrl + "/actuator/health");
                if (resp.statusCode() == 200) {
                    return new GateResult("boot", true, "/actuator/health 200");
                }
                lastError = "/actuator/health returned " + resp.statusCode();
            } catch (Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            sleep(5000);
        }
        return new GateResult("boot", false,
                "app not healthy within " + bootTimeoutSeconds + "s — last: " + lastError);
    }

    private GateResult frontendGate(String baseUrl) {
        try {
            HttpResponse<String> root = get(baseUrl + "/");
            if (root.statusCode() != 200) {
                return new GateResult("frontend", false, "GET / returned " + root.statusCode()
                        + " — SPA not served (check SecurityConfig permits static assets)");
            }
            String body = root.body();
            if (!body.contains("id=\"root\"")) {
                return new GateResult("frontend", false,
                        "GET / 200 but no <div id=\"root\"> — index.html is not the SPA shell");
            }
            Matcher m = ASSET_JS.matcher(body);
            if (!m.find()) {
                return new GateResult("frontend", false,
                        "index.html references no /assets/*.js bundle — frontend build not baked into the jar");
            }
            String bundlePath = m.group();
            HttpResponse<String> bundle = get(baseUrl + bundlePath);
            if (bundle.statusCode() != 200) {
                return new GateResult("frontend", false,
                        "JS bundle " + bundlePath + " returned " + bundle.statusCode());
            }
            return new GateResult("frontend", true, "SPA shell + bundle " + bundlePath + " served");
        } catch (Exception e) {
            return new GateResult("frontend", false, "frontend check error: " + e.getMessage());
        }
    }

    /**
     * At least one parameterless GET API endpoint must return 200 with non-empty JSON —
     * proof the API layer is wired AND DataSeeder populated the database.
     * 401/403 responses count as "endpoint exists" but not as data.
     */
    private GateResult apiDataGate(String baseUrl, Path workspace) {
        Set<String> paths = collectGetEndpoints(workspace);
        if (paths.isEmpty()) {
            return new GateResult("api-data", true, "no parameterless GET endpoints in spec — gate skipped");
        }

        List<String> attempts = new ArrayList<>();
        boolean anyReachable = false;
        for (String path : paths) {
            try {
                HttpResponse<String> resp = get(baseUrl + path);
                int sc = resp.statusCode();
                if (sc == 401 || sc == 403) {
                    anyReachable = true;
                    attempts.add(path + "→" + sc);
                    continue;
                }
                if (sc == 200) {
                    anyReachable = true;
                    String body = resp.body() == null ? "" : resp.body().trim();
                    boolean hasData = body.length() > 2 && (body.startsWith("[") || body.startsWith("{"))
                            && !body.equals("[]") && !body.equals("{}");
                    if (hasData) {
                        return new GateResult("api-data", true, "GET " + path + " → 200 with data");
                    }
                    attempts.add(path + "→200 empty");
                    continue;
                }
                attempts.add(path + "→" + sc);
            } catch (Exception e) {
                attempts.add(path + "→error");
            }
        }

        String detail = String.join(", ", attempts.subList(0, Math.min(attempts.size(), 8)));
        if (!anyReachable) {
            return new GateResult("api-data", false, "no API endpoint reachable: " + detail);
        }
        return new GateResult("api-data", false,
                "no public endpoint returned data (DataSeeder likely didn't run or seeded nothing): " + detail);
    }

    private Set<String> collectGetEndpoints(Path workspace) {
        Set<String> paths = new LinkedHashSet<>();
        try {
            if (!ArchitectureJsonUtil.exists(workspace)) return paths;
            ArchitectureSpec spec = ArchitectureJsonUtil.read(workspace);
            if (spec.getFiles() == null) return paths;
            for (FileSpec file : spec.getFiles()) {
                if (file.getApiEndpoints() == null) continue;
                for (ApiEndpoint ep : file.getApiEndpoints()) {
                    if (ep.getPath() == null || ep.getMethod() == null) continue;
                    if (!"GET".equalsIgnoreCase(ep.getMethod().trim())) continue;
                    String path = ep.getPath().trim();
                    // strip any inline query documentation ("?category={category}")
                    int q = path.indexOf('?');
                    if (q >= 0) path = path.substring(0, q);
                    if (PATH_PARAM.matcher(path).find()) continue; // needs an id we don't have
                    if (!path.startsWith("/")) path = "/" + path;
                    paths.add(path);
                }
            }
        } catch (IOException e) {
            log.warn("[ComposeLaunch] Could not read ARCHITECTURE.json for API gate: {}", e.getMessage());
        }
        return paths;
    }

    // ── .env preparation ───────────────────────────────────────────────────

    /**
     * Ensures a usable .env exists: derives it from .env.example when absent and
     * fills blank values with demo placeholders. JWT_SECRET gets a real random key
     * (JJWT rejects short keys at runtime — a placeholder string would fail the boot gate
     * for the wrong reason).
     */
    void prepareEnvFile(Path workspace) throws IOException {
        Path envFile = workspace.resolve(".env");
        Path example = workspace.resolve(".env.example");

        List<String> lines;
        if (Files.exists(envFile)) {
            lines = Files.readAllLines(envFile);
        } else if (Files.exists(example)) {
            lines = Files.readAllLines(example);
        } else {
            log.warn("[ComposeLaunch] No .env or .env.example in workspace — compose may fail");
            return;
        }

        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                out.add(line);
                continue;
            }
            int eq = trimmed.indexOf('=');
            String key = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();

            if (key.equals("JWT_SECRET") && value.length() < 44) {
                out.add(key + "=" + randomBase64Secret());
            } else if (value.isEmpty()) {
                out.add(key + "=demo-placeholder");
            } else {
                out.add(line);
            }
        }
        Files.writeString(envFile, String.join("\n", out) + "\n");
        log.info("[ComposeLaunch] .env prepared ({} keys)", out.size());
    }

    private static String randomBase64Secret() {
        byte[] bytes = new byte[64];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    // ── Compose override ───────────────────────────────────────────────────

    /**
     * Writes the ephemeral override that adapts the client's committed compose file
     * to this run: tags the built image, strips the host port publish (or replaces it
     * with an allocated one on dev hosts), and attaches the app to the shared network
     * under the project-name alias so the worker can reach it.
     *
     * Note: `ports: !reset` needs compose >= 2.24 (list values merge by union otherwise).
     * The db service keeps an explicit `default` network so app<->db connectivity survives
     * the app gaining an explicit networks list.
     */
    Path writeOverride(Path workspace, LaunchSpec spec) throws IOException {
        Path dir = workspace.resolve(".smoke");
        Files.createDirectories(dir);

        // YAML merge tags: !reset REMOVES the attribute (strip host publish);
        // !override REPLACES the list (compose merges lists by union otherwise).
        String ports = spec.hostPort() != null
                ? "ports: !override [\"" + spec.hostPort() + ":8080\"]"
                : "ports: !reset []";

        String imageLine = spec.imageRef() != null && !spec.imageRef().isBlank()
                ? "    image: " + spec.imageRef() + "\n"
                : "";

        // In-container (production): join the shared network so the worker reaches the app
        // by its project-name alias. On a dev host that network may not exist — the app
        // stays on the compose default network and is reached via the published port.
        String content;
        if (inContainer) {
            content = """
                    services:
                      app:
                    %s    %s
                        networks:
                          default: {}
                          smoke_external:
                            aliases:
                              - %s
                      db:
                        networks:
                          default: {}
                    networks:
                      smoke_external:
                        name: %s
                        external: true
                    """.formatted(imageLine, ports, spec.projectName(), sharedNetwork);
        } else {
            content = """
                    services:
                      app:
                    %s    %s
                    """.formatted(imageLine, ports);
        }

        Path override = dir.resolve("docker-compose.override.yml");
        Files.writeString(override, content);
        return override;
    }

    private String baseUrl(LaunchSpec spec) {
        if (inContainer) {
            return "http://" + spec.projectName() + ":8080";
        }
        int port = spec.hostPort() != null ? spec.hostPort() : 8080;
        return "http://localhost:" + port;
    }

    // ── Process helpers ────────────────────────────────────────────────────

    record ProcessResult(int exitCode, String output) {
        boolean success() { return exitCode == 0; }
    }

    private ProcessResult run(Path workDir, List<String> cmd, int timeoutMinutes)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(workDir.toFile());
        if (dockerHost != null && !dockerHost.isBlank()) {
            pb.environment().put("DOCKER_HOST", dockerHost);
        }

        log.info("[ComposeLaunch] Running: {}", String.join(" ", cmd));
        Process proc = pb.start();
        // Read output on a separate thread so a chatty build can't fill the pipe and deadlock waitFor
        StringBuilder buf = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (var in = proc.getInputStream()) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) != -1) buf.append(new String(chunk, 0, n));
            } catch (IOException ignored) {}
        });
        reader.start();

        boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            proc.destroyForcibly();
            reader.join(2000);
            return new ProcessResult(124, buf + "\n[timed out after " + timeoutMinutes + "m]");
        }
        reader.join(5000);
        return new ProcessResult(proc.exitValue(), buf.toString());
    }

    private HttpResponse<String> get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String tail(String s, int maxChars) {
        if (s == null) return "";
        return s.length() <= maxChars ? s : "..." + s.substring(s.length() - maxChars);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
