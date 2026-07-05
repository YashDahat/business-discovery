package com.business.discovery.worker.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiContractCheckerTest {

    @TempDir
    Path workspace;

    private Path frontendSrc;
    private Path backendSrcJava;

    @BeforeEach
    void setUp() throws Exception {
        frontendSrc = workspace.resolve("frontend/src");
        backendSrcJava = workspace.resolve("backend/src/main/java");
        Files.createDirectories(frontendSrc.resolve("api"));
        Files.createDirectories(frontendSrc.resolve("services"));
        Files.createDirectories(backendSrcJava.resolve("com/x/controller"));
    }

    private void client(String baseUrl) throws Exception {
        Files.writeString(frontendSrc.resolve("api/client.ts"),
                "const apiClient = axios.create({ baseURL: '" + baseUrl + "' });");
    }

    private void service(String name, String body) throws Exception {
        Files.writeString(frontendSrc.resolve("services/" + name), body);
    }

    private void controller(String name, String body) throws Exception {
        Files.writeString(backendSrcJava.resolve("com/x/controller/" + name), body);
    }

    @Test
    void fixesPrefixDoublingByEmptyingBaseUrl() throws Exception {
        client("/api/v1");
        service("trainerService.ts",
                "apiClient.get<Trainer[]>('/api/v1/trainers');\napiClient.post('/api/v1/leads');");

        boolean fixed = ApiContractChecker.fixPrefixDoubling(frontendSrc);

        assertThat(fixed).isTrue();
        assertThat(Files.readString(frontendSrc.resolve("api/client.ts"))).contains("baseURL: ''");
    }

    @Test
    void doesNotTouchBaseUrlWhenNoDoubling() throws Exception {
        client("");
        service("s.ts", "apiClient.get('/api/v1/trainers');");

        assertThat(ApiContractChecker.fixPrefixDoubling(frontendSrc)).isFalse();
    }

    @Test
    void reportsMethodMismatchAndMissingPath() throws Exception {
        client("");
        // frontend GETs /leads/trial; backend only exposes it under /admin (GET) and public POST
        service("leadService.ts",
                "apiClient.get<Lead[]>('/api/v1/leads/trial');\n"
                + "apiClient.get<Trainer[]>('/api/v1/trainers');");
        controller("AdminController.java",
                "@RequestMapping(\"/api/v1/admin\") class AdminController {"
                + " @GetMapping(\"/leads/trial\") x; @GetMapping(\"/trainers\") y; }");
        controller("PublicController.java",
                "@RequestMapping(\"/api/v1\") class PublicController { @PostMapping(\"/leads/trial\") z; }");

        List<String> mismatches = ApiContractChecker.report(frontendSrc, backendSrcJava);

        // GET /api/v1/trainers has no match (backend trainers is under /admin) → reported
        assertThat(mismatches).anyMatch(m -> m.contains("GET /api/v1/trainers"));
        // GET /api/v1/leads/trial: path exists only as POST at /api/v1 and GET at /admin → mismatch
        assertThat(mismatches).anyMatch(m -> m.contains("GET /api/v1/leads/trial"));
        assertThat(Files.exists(workspace.resolve("docs/API_CONTRACT_REPORT.md"))).isTrue();
    }

    @Test
    void noMismatchWhenContractsAlign() throws Exception {
        client("");
        service("s.ts", "apiClient.get('/api/v1/admin/trainers');\napiClient.put('/api/v1/admin/trainers/${id}');");
        controller("AdminController.java",
                "@RequestMapping(\"/api/v1/admin\") class C {"
                + " @GetMapping(\"/trainers\") a; @PutMapping(\"/trainers/{id}\") b; }");

        assertThat(ApiContractChecker.report(frontendSrc, backendSrcJava)).isEmpty();
    }

    @Test
    void normalizeWildcardsPathParams() {
        assertThat(ApiContractChecker.normalize("/api/v1/x/${id}")).isEqualTo("/api/v1/x/*");
        assertThat(ApiContractChecker.normalize("/api/v1/x/{id}")).isEqualTo("/api/v1/x/*");
        assertThat(ApiContractChecker.segmentsMatch("/a/*/c", "/a/9/c")).isTrue();
        assertThat(ApiContractChecker.segmentsMatch("/a/b", "/a/b/c")).isFalse();
    }
}
