package com.business.discovery.worker.service;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
@Slf4j
public class GitHubApiService {

    private static final String API_BASE = "https://api.github.com";

    @Value("${worker.github.token:}")
    private String githubToken;

    @Value("${worker.github.owner:}")
    private String owner;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public String createRepo(String repoName, String description) {
        ObjectNode body = mapper.createObjectNode();
        body.put("name", repoName);
        body.put("description", description);
        body.put("private", false);
        body.put("auto_init", true);

        HttpResponse<String> resp = post("/user/repos", body);
        int status = resp.statusCode();

        if (status == 401 || status == 403) {
            throw new WorkerException(FailureType.CONFIG_AUTH,
                    "GitHub auth failed creating repo — check GITHUB_TOKEN. HTTP " + status);
        }
        if (status == 422) {
            // Repo already exists — find and return the existing URL
            return findExistingRepoUrl(repoName);
        }
        if (status != 201) {
            throw new WorkerException(FailureType.INFRA,
                    "GitHub createRepo failed HTTP " + status + ": " + resp.body());
        }

        try {
            return mapper.readTree(resp.body()).path("html_url").asText();
        } catch (Exception e) {
            throw new WorkerException(FailureType.INFRA, "Failed to parse createRepo response", e);
        }
    }

    public String createPullRequest(String repoName, String branch,
                                    String title, String body) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("title", title);
        payload.put("body", body);
        payload.put("head", branch);
        payload.put("base", "main");

        String path = "/repos/" + owner + "/" + repoName + "/pulls";
        HttpResponse<String> resp = post(path, payload);
        int status = resp.statusCode();

        if (status == 401 || status == 403) {
            throw new WorkerException(FailureType.CONFIG_AUTH,
                    "GitHub auth failed creating PR. HTTP " + status);
        }
        if (status == 422) {
            // PR already exists — find existing URL
            return findExistingPrUrl(repoName, branch);
        }
        if (status != 201) {
            throw new WorkerException(FailureType.INFRA,
                    "GitHub createPullRequest failed HTTP " + status + ": " + resp.body());
        }

        try {
            return mapper.readTree(resp.body()).path("html_url").asText();
        } catch (Exception e) {
            throw new WorkerException(FailureType.INFRA, "Failed to parse createPR response", e);
        }
    }

    public boolean repoExists(String repoName) {
        HttpResponse<String> resp = get("/repos/" + owner + "/" + repoName);
        return resp.statusCode() == 200;
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private String findExistingRepoUrl(String repoName) {
        HttpResponse<String> resp = get("/repos/" + owner + "/" + repoName);
        if (resp.statusCode() != 200) {
            throw new WorkerException(FailureType.INFRA,
                    "Repo already exists but couldn't locate it: " + repoName);
        }
        try {
            return mapper.readTree(resp.body()).path("html_url").asText();
        } catch (Exception e) {
            throw new WorkerException(FailureType.INFRA, "Failed to parse existing repo response", e);
        }
    }

    private String findExistingPrUrl(String repoName, String branch) {
        HttpResponse<String> resp = get(
                "/repos/" + owner + "/" + repoName + "/pulls?head=" + owner + ":" + branch + "&state=open");
        if (resp.statusCode() != 200) {
            throw new WorkerException(FailureType.INFRA,
                    "Could not find existing PR for branch " + branch);
        }
        try {
            JsonNode arr = mapper.readTree(resp.body());
            if (arr.isArray() && arr.size() > 0) {
                return arr.get(0).path("html_url").asText();
            }
            throw new WorkerException(FailureType.INFRA,
                    "PR already exists but couldn't locate it for branch " + branch);
        } catch (WorkerException e) {
            throw e;
        } catch (Exception e) {
            throw new WorkerException(FailureType.INFRA, "Failed to parse PR list response", e);
        }
    }

    private HttpResponse<String> post(String path, ObjectNode body) {
        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + path))
                    .header("Authorization", "Bearer " + githubToken)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new WorkerException(FailureType.INFRA,
                    "GitHub API POST " + path + " failed: " + e.getMessage(), e);
        }
    }

    private HttpResponse<String> get(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + path))
                    .header("Authorization", "Bearer " + githubToken)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new WorkerException(FailureType.INFRA,
                    "GitHub API GET " + path + " failed: " + e.getMessage(), e);
        }
    }
}
