package com.business.discovery.worker.service;

import com.business.discovery.worker.service.llm.ApiEndpoint;
import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.util.ApiInventory;
import com.business.discovery.worker.util.ArchitectureJsonUtil;
import com.business.discovery.worker.util.SeededCredentialFinder;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    /** Ships on the branch (docs/ is not gitignored), so a failing run leaves its worklist behind. */
    static final String FLOW_REPORT = "docs/SMOKE_REPORT.md";

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

    /**
     * Whether a broken user journey fails the run. Default false: measure first, enforce
     * once the deterministic fixes for what it finds are in place — a strict gate today
     * would burn three regeneration retries on defects the fix loop cannot repair
     * (a security-matcher typo is not a compile error), and the report is the point.
     */
    @Value("${worker.smoke.flows-strict:false}")
    private boolean flowsStrict;

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

        // Flows run LAST and always run: they are the only probe of the live product, and
        // their report is worth having even when an earlier gate is already unhappy.
        results.add(flowsGate(baseUrl, workspace));
        return new GateReport(results);
    }

    // ── Flows gate ─────────────────────────────────────────────────────────

    /** One probed user journey. */
    public record FlowResult(String flow, boolean passed, String detail) {}

    /**
     * Exercises the live product the way a visitor and an owner would: fetches every
     * public endpoint anonymously, logs in with the credentials the seeder actually
     * planted, and reads the admin surface with that token.
     *
     * Every earlier gate is blind to what this catches. Circuit-house attempt 2 compiled
     * clean on both sides, booted, served the SPA — and still shipped a 403 on its own
     * public menu (SecurityConfig permitted /api/v1/menus/**, the controller served
     * /api/v1/menu/**) and an ordering endpoint that rejected every possible request.
     * Note the api-data gate cannot catch either: it counts 401/403 as "reachable".
     *
     * Writes docs/SMOKE_REPORT.md unconditionally — on a passing run it is the evidence
     * the site works; on a failing one it is the worklist.
     *
     * Enforcement is deliberately separated from measurement via worker.smoke.flows-strict.
     * While the deterministic fixes for the defects it finds are still being built, a
     * failing flow should not burn three regeneration retries that cannot possibly fix it;
     * the run completes, the report ships, and the worklist is harvested. Flip to true to
     * make broken journeys block the PR.
     */
    private GateResult flowsGate(String baseUrl, Path workspace) {
        List<FlowResult> flows = new ArrayList<>();

        flows.addAll(publicContentFlows(baseUrl, workspace));
        String token = loginFlow(baseUrl, workspace, flows);
        if (token != null) flows.addAll(adminReadFlows(baseUrl, workspace, token));
        flows.addAll(orderWriteFlows(baseUrl, workspace));

        writeFlowReport(workspace, baseUrl, flows);

        List<FlowResult> broken = flows.stream().filter(f -> !f.passed()).toList();
        if (broken.isEmpty()) {
            return new GateResult("flows", true, flows.size() + " journeys passed");
        }

        String detail = broken.size() + " of " + flows.size() + " journeys broken: "
                + broken.stream().limit(6)
                        .map(f -> f.flow() + " (" + f.detail() + ")")
                        .collect(java.util.stream.Collectors.joining("; "))
                + " — full list in " + FLOW_REPORT;

        if (!flowsStrict) {
            log.warn("[ComposeLaunch] FLOWS GATE (advisory — worker.smoke.flows-strict=false): {}", detail);
            return new GateResult("flows", true, "ADVISORY — " + detail);
        }
        return new GateResult("flows", false, detail);
    }

    /** Public endpoints must serve anonymous visitors. A 401/403 here is a security-matcher defect. */
    private List<FlowResult> publicContentFlows(String baseUrl, Path workspace) {
        List<FlowResult> out = new ArrayList<>();
        for (String path : collectGetEndpoints(workspace)) {
            if (path.contains("/admin")) continue;
            String flow = "public GET " + path;
            try {
                HttpResponse<String> r = get(baseUrl + path);
                int sc = r.statusCode();
                if (sc == 401 || sc == 403) {
                    out.add(new FlowResult(flow, false, sc
                            + " — a visitor cannot reach this; the security matcher does not "
                            + "permit the path the controller actually serves"));
                } else if (sc >= 500) {
                    out.add(new FlowResult(flow, false, sc + " server error"));
                } else if (sc >= 400) {
                    out.add(new FlowResult(flow, false, String.valueOf(sc)));
                } else {
                    out.add(new FlowResult(flow, true, String.valueOf(sc)));
                }
            } catch (Exception e) {
                out.add(new FlowResult(flow, false, "request failed: " + e.getMessage()));
            }
        }
        return out;
    }

    /** @return bearer token, or null when nobody can log in. */
    private String loginFlow(String baseUrl, Path workspace, List<FlowResult> flows) {
        Path backendSrc = workspace.resolve("backend/src/main/java");
        Path props = workspace.resolve("backend/src/main/resources/application.properties");
        List<SeededCredentialFinder.Credential> candidates = SeededCredentialFinder.find(backendSrc, props);

        if (candidates.isEmpty()) {
            flows.add(new FlowResult("admin login", false,
                    "no seeded credentials found in backend source — nobody can administer this site"));
            return null;
        }

        String loginPath = loginPath(workspace);
        List<String> idFields = loginIdentifierFields(workspace);
        List<String> tried = new ArrayList<>();
        for (SeededCredentialFinder.Credential c : candidates) {
            try {
                HttpResponse<String> r = postJson(baseUrl + loginPath,
                        loginBody(idFields, c.identifier(), c.password()));
                String token = extractToken(r.body());
                if (r.statusCode() == 200 && token != null) {
                    flows.add(new FlowResult("admin login", true,
                            "200 as " + c.identifier() + " (seeded by " + c.source() + ")"));
                    return token;
                }
                tried.add(c.identifier() + "/" + c.password() + "→" + r.statusCode());
            } catch (Exception e) {
                tried.add(c.identifier() + "→error");
            }
        }
        flows.add(new FlowResult("admin login", false,
                "every seeded credential rejected at " + loginPath + ": " + String.join(", ", tried)));
        return null;
    }

    /** The owner's surface: admin reads must answer with the token the login just issued. */
    private List<FlowResult> adminReadFlows(String baseUrl, Path workspace, String token) {
        List<FlowResult> out = new ArrayList<>();
        for (String path : collectGetEndpoints(workspace)) {
            if (!path.contains("/admin")) continue;
            String flow = "admin GET " + path;
            try {
                HttpResponse<String> r = getWithToken(baseUrl + path, token);
                int sc = r.statusCode();
                if (sc == 403 || sc == 401) {
                    out.add(new FlowResult(flow, false, sc
                            + " — logged-in admin is denied; role authority mismatch or matcher gap"));
                } else if (sc >= 500) {
                    out.add(new FlowResult(flow, false, sc + " server error"));
                } else if (sc >= 400) {
                    out.add(new FlowResult(flow, false, String.valueOf(sc)));
                } else {
                    out.add(new FlowResult(flow, true, String.valueOf(sc)));
                }
            } catch (Exception e) {
                out.add(new FlowResult(flow, false, "request failed: " + e.getMessage()));
            }
        }
        return out;
    }

    private void writeFlowReport(Path workspace, String baseUrl, List<FlowResult> flows) {
        long passed = flows.stream().filter(FlowResult::passed).count();
        StringBuilder sb = new StringBuilder();
        sb.append("# Smoke Flow Report\n\n")
          .append("Probed live at `").append(baseUrl).append("` — ")
          .append(passed).append(" of ").append(flows.size()).append(" journeys working.\n\n")
          .append("These are runtime journeys, not compilation. Everything below compiled cleanly.\n\n");

        List<FlowResult> broken = flows.stream().filter(f -> !f.passed()).toList();
        if (!broken.isEmpty()) {
            sb.append("## Broken (").append(broken.size()).append(")\n\n");
            broken.forEach(f -> sb.append("- **").append(f.flow()).append("** — ")
                                  .append(f.detail()).append("\n"));
            sb.append("\n");
        }
        sb.append("## Working (").append(passed).append(")\n\n");
        flows.stream().filter(FlowResult::passed)
             .forEach(f -> sb.append("- ").append(f.flow()).append(" — ").append(f.detail()).append("\n"));

        try {
            Path out = workspace.resolve(FLOW_REPORT);
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString());
            log.info("[ComposeLaunch] Flow report written to {} ({} of {} journeys working)",
                    FLOW_REPORT, passed, flows.size());
        } catch (IOException e) {
            log.warn("[ComposeLaunch] Could not write {}: {}", FLOW_REPORT, e.getMessage());
        }
    }

    /** The spec's own auth path, so this is not hardcoded to one project's conventions. */
    private String loginPath(Path workspace) {
        try {
            if (ArchitectureJsonUtil.exists(workspace)) {
                ArchitectureSpec spec = ArchitectureJsonUtil.read(workspace);
                if (spec.getFiles() != null) {
                    for (FileSpec file : spec.getFiles()) {
                        if (file.getApiEndpoints() == null) continue;
                        for (ApiEndpoint ep : file.getApiEndpoints()) {
                            if (ep.getPath() == null || ep.getMethod() == null) continue;
                            if ("POST".equalsIgnoreCase(ep.getMethod())
                                    && ep.getPath().toLowerCase().contains("login")) {
                                return ep.getPath().trim();
                            }
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // fall through to the convention
        }
        return "/api/v1/auth/login";
    }

    // ── Order write journey ────────────────────────────────────────────────

    /**
     * Probes the storefront's actual WRITE path — the one thing public GETs and admin logins never
     * touch, and where circuit-house shipped a 500 (OrderItem inserted with a null order_id). Finds a
     * public POST create endpoint whose request carries a nested collection (the cascade-order shape),
     * synthesizes a valid body from the request DTO's fields (with a real product id from a public
     * list), posts it, and FAILS only on 5xx — a 4xx means the endpoint works and merely rejected our
     * synthetic input, which is not the defect we are catching.
     */
    private List<FlowResult> orderWriteFlows(String baseUrl, Path workspace) {
        List<FlowResult> out = new ArrayList<>();
        ApiInventory inv;
        try {
            inv = ApiInventory.extract(workspace.resolve("backend/src/main/java"));
        } catch (Exception e) {
            return out; // no inventory — skip rather than false-fail
        }
        Map<String, ApiInventory.TypeDef> types = inv.types();
        for (ApiInventory.Endpoint ep : inv.endpoints()) {
            if (!"POST".equalsIgnoreCase(ep.httpMethod())) continue;
            String path = ep.path();
            if (path == null || path.contains("/admin") || path.contains("/auth") || path.contains("/payment")) {
                continue;
            }
            ApiInventory.TypeDef def = ep.requestType() == null ? null : types.get(ep.requestType());
            if (def == null || def.fields() == null) continue;
            if (def.fields().stream().noneMatch(f -> isCollectionType(f.javaType()))) continue; // cascade shape only

            String realId = firstPublicItemId(baseUrl, workspace);
            String body = synthesizeJson(def, types, realId, 0);
            String flow = "place order POST " + path;
            try {
                HttpResponse<String> r = postJson(baseUrl + path, body);
                if (r.statusCode() >= 500) {
                    out.add(new FlowResult(flow, false, r.statusCode()
                            + " server error — the write/persist path is broken: " + firstLine(r.body())));
                } else {
                    out.add(new FlowResult(flow, true, String.valueOf(r.statusCode())));
                }
            } catch (Exception e) {
                out.add(new FlowResult(flow, false, "request failed: " + e.getMessage()));
            }
            break; // one representative write journey is enough
        }
        return out;
    }

    /** First numeric "id" from any public list response — a real product id for the order items. */
    private String firstPublicItemId(String baseUrl, Path workspace) {
        Pattern idNum = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
        for (String p : collectGetEndpoints(workspace)) {
            if (p.contains("/admin")) continue;
            try {
                Matcher m = idNum.matcher(get(baseUrl + p).body());
                if (m.find()) return m.group(1);
            } catch (Exception ignored) {
                // try the next endpoint
            }
        }
        return null;
    }

    private String synthesizeJson(ApiInventory.TypeDef def, Map<String, ApiInventory.TypeDef> types,
                                  String realId, int depth) {
        if (depth > 4 || def.fields() == null) return "null";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (ApiInventory.Field f : def.fields()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(f.name()).append("\":").append(fieldValue(f, types, realId, depth));
        }
        return sb.append("}").toString();
    }

    private String fieldValue(ApiInventory.Field f, Map<String, ApiInventory.TypeDef> types,
                              String realId, int depth) {
        String type = f.javaType() == null ? "" : f.javaType();
        if (isCollectionType(type)) {
            String elem = collectionElement(type);
            ApiInventory.TypeDef ed = types.get(elem);
            return "[" + (ed != null ? synthesizeJson(ed, types, realId, depth + 1)
                                      : scalarValue(elem, "", realId)) + "]";
        }
        ApiInventory.TypeDef nested = types.get(type);
        if (nested != null) {
            if (nested.isEnum()) {
                return nested.enumConstants().isEmpty() ? "null" : "\"" + nested.enumConstants().get(0) + "\"";
            }
            return synthesizeJson(nested, types, realId, depth + 1);
        }
        return scalarValue(type, f.name().toLowerCase(), realId);
    }

    private String scalarValue(String type, String fieldNameLower, String realId) {
        String t = type == null ? "" : type.toLowerCase();
        boolean numeric = t.matches(".*(int|long|short|byte|bigdecimal|double|float|number).*");
        if (fieldNameLower.endsWith("id") && realId != null) return realId; // real product id (menuItemId, ...)
        if (t.contains("bool")) return "true";
        if (numeric) return "1";
        if (fieldNameLower.contains("email")) return "\"test@example.com\"";
        if (fieldNameLower.contains("phone")) return "\"1234567890\"";
        if (t.contains("uuid")) return "\"00000000-0000-0000-0000-000000000001\"";
        if (t.matches(".*(localdate|localdatetime|instant|date|time).*")) return "\"2030-01-01T10:00:00\"";
        return "\"Test\"";
    }

    private static boolean isCollectionType(String t) {
        if (t == null) return false;
        return t.startsWith("List<") || t.startsWith("Set<") || t.startsWith("Collection<") || t.endsWith("[]");
    }

    private static String collectionElement(String t) {
        int lt = t.indexOf('<'), gt = t.lastIndexOf('>');
        if (lt >= 0 && gt > lt) return t.substring(lt + 1, gt).trim();
        if (t.endsWith("[]")) return t.substring(0, t.length() - 2).trim();
        return "Object";
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        String line = s.lines().findFirst().orElse("");
        return line.length() > 160 ? line.substring(0, 160) : line;
    }

    private static final Pattern DTO_STRING_FIELD = Pattern.compile("private\\s+String\\s+(\\w+)");
    private static final Set<String> PASSWORD_FIELDS =
            Set.of("password", "pass", "pwd", "passwd", "secret");

    /**
     * The identifier field(s) the login DTO actually declares — circuit-house's AuthRequest
     * took {@code username}, but the probe hardcoded {@code {"email": …}} and could never log
     * in. Reads the login request DTO's String fields (minus the password), so we send the
     * identifier under the key the backend binds. Falls back to trying both common names.
     */
    List<String> loginIdentifierFields(Path workspace) {
        Path backendSrc = workspace.resolve("backend/src/main/java");
        if (Files.exists(backendSrc)) {
            try (Stream<Path> s = Files.walk(backendSrc)) {
                LinkedHashSet<String> fields = new LinkedHashSet<>();
                s.filter(p -> p.toString().endsWith(".java"))
                 .filter(ComposeLaunchService::looksLikeLoginDto)
                 .forEach(p -> {
                     try {
                         Matcher m = DTO_STRING_FIELD.matcher(Files.readString(p));
                         while (m.find()) {
                             if (!PASSWORD_FIELDS.contains(m.group(1).toLowerCase())) fields.add(m.group(1));
                         }
                     } catch (IOException ignored) {
                     }
                 });
                if (!fields.isEmpty()) return new ArrayList<>(fields);
            } catch (IOException ignored) {
                // fall through to the convention
            }
        }
        return List.of("username", "email");
    }

    private static boolean looksLikeLoginDto(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        boolean roleName = n.contains("auth") || n.contains("login")
                || n.contains("signin") || n.contains("credential");
        return roleName && (n.contains("request") || n.contains("dto"));
    }

    /** Sends the identifier under every candidate field, so whichever the DTO binds, wins. */
    static String loginBody(List<String> idFields, String identifier, String password) {
        StringBuilder sb = new StringBuilder("{");
        for (String f : idFields) {
            sb.append('"').append(f).append("\":\"").append(identifier).append("\",");
        }
        sb.append("\"password\":\"").append(password).append("\"}");
        return sb.toString();
    }

    /** Tolerates token / accessToken / jwt / jwtToken — the field name varies per run. */
    static String extractToken(String body) {
        if (body == null) return null;
        Matcher m = Pattern.compile(
                "\"(?:token|accessToken|access_token|jwt|jwtToken)\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private HttpResponse<String> postJson(String url, String json)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithToken(String url, String token)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
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
