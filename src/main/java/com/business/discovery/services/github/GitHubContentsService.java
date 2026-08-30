package com.business.discovery.services.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Master-side GitHub REST client used by the Cline MCP repo tools ({@code /internal/mcp/repo/**}).
 *
 * Operates entirely through the GitHub Contents + Git-refs APIs — no local clone. Every write becomes
 * a commit on a working branch; nothing touches the default branch except an explicit pull request.
 * This is the surgical-edit counterpart to the worker's full-generation clone
 * ({@code discovery-worker/.../GitWorkspaceNode}); the worker's {@code GitHubApiService} can't be shared
 * (separate Maven module, duplicated models), so the create-repo / open-PR logic is ported here.
 *
 * Auth is the same PAT the rest of the platform uses ({@code worker.github.token} + owner), so the token
 * never leaves Spring Boot — Cline only reaches these tools via the two-layer-auth MCP endpoints.
 */
@Slf4j
@Service
public class GitHubContentsService {

    private static final String API_BASE = "https://api.github.com";

    @Value("${worker.github.token:}")
    private String githubToken;

    @Value("${worker.github.owner:}")
    private String owner;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Thrown for any non-success GitHub response; carries the HTTP status so callers can map it. */
    public static class GitHubApiException extends RuntimeException {
        private final int status;
        public GitHubApiException(int status, String message) {
            super(message);
            this.status = status;
        }
        public int status() { return status; }
    }

    /** A file's decoded content plus the blob sha needed to update it. */
    public record FileContent(String path, String sha, String content) {}

    // ── Repo lifecycle ────────────────────────────────────────────────────

    /** Create a public repo (auto-initialised). If it already exists (422), returns the existing URL. */
    public String createRepo(String repoName, String description) {
        ObjectNode body = mapper.createObjectNode();
        body.put("name", repoName);
        body.put("description", description == null ? "" : description);
        body.put("private", false);
        body.put("auto_init", true);

        HttpResponse<String> resp = send("POST", "/user/repos", body);
        int status = resp.statusCode();
        if (status == 422) {
            return htmlUrl(get("/repos/" + owner + "/" + repoName), "existing repo");
        }
        if (status != 201) {
            throw new GitHubApiException(status, "createRepo failed HTTP " + status + ": " + resp.body());
        }
        return htmlUrl(resp, "createRepo");
    }

    public boolean repoExists(String repoName) {
        return get("/repos/" + owner + "/" + repoName).statusCode() == 200;
    }

    public String getDefaultBranch(String repoName) {
        HttpResponse<String> resp = get("/repos/" + owner + "/" + repoName);
        if (resp.statusCode() != 200) {
            throw new GitHubApiException(resp.statusCode(),
                    "getDefaultBranch failed HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return parse(resp.body()).path("default_branch").asText("main");
    }

    // ── Read ──────────────────────────────────────────────────────────────

    /** Raw Contents-API JSON for a path (array for a directory, object for a file). */
    public String listFiles(String repoName, String path, String ref) {
        HttpResponse<String> resp = get(contentsPath(repoName, path, ref));
        if (resp.statusCode() != 200) {
            throw new GitHubApiException(resp.statusCode(),
                    "listFiles '" + path + "' failed HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    public FileContent readFile(String repoName, String path, String ref) {
        HttpResponse<String> resp = get(contentsPath(repoName, path, ref));
        if (resp.statusCode() == 404) {
            throw new GitHubApiException(404, "File not found: " + path);
        }
        if (resp.statusCode() != 200) {
            throw new GitHubApiException(resp.statusCode(),
                    "readFile '" + path + "' failed HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode node = parse(resp.body());
        if (node.isArray()) {
            throw new GitHubApiException(400, "Path is a directory, not a file: " + path);
        }
        String encoded = node.path("content").asText("").replaceAll("\\s", "");
        String decoded = encoded.isEmpty() ? ""
                : new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        return new FileContent(path, node.path("sha").asText(null), decoded);
    }

    // ── Write ─────────────────────────────────────────────────────────────

    /**
     * Commit {@code content} to {@code branch} (creating the branch off the default branch if it does
     * not yet exist). Creates the file if absent, otherwise updates it. Returns the commit's html_url.
     */
    public String writeFile(String repoName, String path, String content, String message, String branch) {
        ensureBranch(repoName, getDefaultBranch(repoName), branch);

        // Existing blob sha on this branch is required to update; absent → create.
        String existingSha = null;
        HttpResponse<String> existing = get(contentsPath(repoName, path, branch));
        if (existing.statusCode() == 200) {
            JsonNode n = parse(existing.body());
            if (!n.isArray()) {
                existingSha = n.path("sha").asText(null);
            }
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("message", (message == null || message.isBlank())
                ? "Update " + path + " via Cline" : message);
        body.put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        body.put("branch", branch);
        if (existingSha != null) {
            body.put("sha", existingSha);
        }

        HttpResponse<String> resp = send("PUT", "/repos/" + owner + "/" + repoName
                + "/contents/" + encodePath(path), body);
        if (resp.statusCode() != 200 && resp.statusCode() != 201) {
            throw new GitHubApiException(resp.statusCode(),
                    "writeFile '" + path + "' failed HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return parse(resp.body()).path("commit").path("html_url").asText("");
    }

    /** Create {@code branch} off {@code base} if it does not already exist. No-op if it does. */
    public void ensureBranch(String repoName, String base, String branch) {
        if (get("/repos/" + owner + "/" + repoName + "/branches/" + enc(branch)).statusCode() == 200) {
            return;
        }
        HttpResponse<String> ref = get("/repos/" + owner + "/" + repoName + "/git/ref/heads/" + enc(base));
        if (ref.statusCode() != 200) {
            throw new GitHubApiException(ref.statusCode(),
                    "ensureBranch: base branch '" + base + "' not found HTTP " + ref.statusCode());
        }
        String sha = parse(ref.body()).path("object").path("sha").asText(null);

        ObjectNode body = mapper.createObjectNode();
        body.put("ref", "refs/heads/" + branch);
        body.put("sha", sha);
        HttpResponse<String> resp = send("POST", "/repos/" + owner + "/" + repoName + "/git/refs", body);
        if (resp.statusCode() != 201) {
            throw new GitHubApiException(resp.statusCode(),
                    "ensureBranch create '" + branch + "' failed HTTP " + resp.statusCode() + ": " + resp.body());
        }
        log.info("[GitHubContents] Created branch '{}' from '{}' on {}", branch, base, repoName);
    }

    /** Open a PR {@code head} → {@code base}. If one already exists (422), returns the existing URL. */
    public String openPullRequest(String repoName, String head, String base, String title, String body) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("title", (title == null || title.isBlank()) ? "Changes via Cline" : title);
        payload.put("body", body == null ? "" : body);
        payload.put("head", head);
        payload.put("base", base);

        HttpResponse<String> resp = send("POST", "/repos/" + owner + "/" + repoName + "/pulls", payload);
        if (resp.statusCode() == 422) {
            HttpResponse<String> list = get("/repos/" + owner + "/" + repoName
                    + "/pulls?head=" + enc(owner + ":" + head) + "&state=open");
            JsonNode arr = parse(list.body());
            if (arr.isArray() && arr.size() > 0) {
                return arr.get(0).path("html_url").asText("");
            }
            throw new GitHubApiException(422, "PR exists but could not be located for head " + head);
        }
        if (resp.statusCode() != 201) {
            throw new GitHubApiException(resp.statusCode(),
                    "openPullRequest failed HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return htmlUrl(resp, "openPullRequest");
    }

    // ── HTTP + helpers ────────────────────────────────────────────────────

    private String contentsPath(String repoName, String path, String ref) {
        String p = "/repos/" + owner + "/" + repoName + "/contents/" + encodePath(path);
        return ref == null || ref.isBlank() ? p : p + "?ref=" + enc(ref);
    }

    private String htmlUrl(HttpResponse<String> resp, String op) {
        String url = parse(resp.body()).path("html_url").asText(null);
        if (url == null) {
            throw new GitHubApiException(resp.statusCode(), op + ": response had no html_url");
        }
        return url;
    }

    private HttpResponse<String> get(String path) {
        return exchange(request(path).GET().build(), "GET " + path);
    }

    private HttpResponse<String> send(String method, String path, ObjectNode body) {
        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest req = request(path)
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json))
                    .build();
            return exchange(req, method + " " + path);
        } catch (GitHubApiException e) {
            throw e;
        } catch (Exception e) {
            throw new GitHubApiException(502, "GitHub " + method + " " + path + " failed: " + e.getMessage());
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + path))
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .timeout(Duration.ofSeconds(30));
    }

    private HttpResponse<String> exchange(HttpRequest req, String label) {
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                throw new GitHubApiException(resp.statusCode(),
                        "GitHub auth failed (" + label + ") — check GITHUB_TOKEN. HTTP " + resp.statusCode());
            }
            return resp;
        } catch (GitHubApiException e) {
            throw e;
        } catch (Exception e) {
            throw new GitHubApiException(502, "GitHub request failed (" + label + "): " + e.getMessage());
        }
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new GitHubApiException(502, "Failed to parse GitHub response: " + e.getMessage());
        }
    }

    /** Encode a repo path, preserving '/' separators. */
    private static String encodePath(String path) {
        if (path == null || path.isBlank()) return "";
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(enc(parts[i]));
        }
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
