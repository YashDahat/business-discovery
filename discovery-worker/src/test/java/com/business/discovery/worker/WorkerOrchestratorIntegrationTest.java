package com.business.discovery.worker;

import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.model.ArchitectBrief;
import com.business.discovery.worker.model.BusinessEntity;
import com.business.discovery.worker.model.ContainerTask;
import com.business.discovery.worker.model.GeneratedFile;
import com.business.discovery.worker.model.GeneratedFile.FileStatus;
import com.business.discovery.worker.orchestrator.WorkerOrchestrator;
import com.business.discovery.worker.repository.ArchitectBriefRepository;
import com.business.discovery.worker.repository.BusinessEntityRepository;
import com.business.discovery.worker.repository.ContainerTaskRepository;
import com.business.discovery.worker.repository.GeneratedFileRepository;
import com.business.discovery.worker.service.BuildToolService;
import com.business.discovery.worker.service.GitHubApiService;
import com.business.discovery.worker.service.GitService;
import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FeatureSpec;
import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.ProjectDependencies;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration test for the full 14-node worker pipeline.
 * Git, GitHub API, LLM, and build tools are mocked; DB and filesystem run for real.
 * Prereq: local Postgres with business_discovery_test DB.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/business_discovery_test",
        "spring.datasource.username=amardahat",
        "spring.datasource.password=94227",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "worker.task.id=",
        "worker.task.brief-id=",
        "worker.task.business-id=",
        "worker.task.attempt-number=1",
        "worker.github.token=test-token",
        "worker.github.owner=test-owner",
        "worker.github.branch=feature/shree-cafe-attempt-1",
        "worker.github.repo-url=",
        "worker.llm.anthropic.api-key=test-key",
        // No docker daemon in unit-test environments — SmokeTestNode must no-op
        "worker.smoke.enabled=false",
})
@ActiveProfiles("test")
class WorkerOrchestratorIntegrationTest {

    private static final String REPO_URL = "https://github.com/test-owner/shree-cafe";
    private static final String PR_URL   = "https://github.com/test-owner/shree-cafe/pull/1";
    static final Path WORKSPACE = Path.of(System.getProperty("java.io.tmpdir"), "worker-oit");

    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry registry) {
        registry.add("worker.workspace.dir", WORKSPACE::toString);
    }

    // ── Mocked external services ──────────────────────────────────────────
    @MockitoBean("geminiPro")   LlmGeneratorService geminiProLlm;
    @MockitoBean("geminiFlash") LlmGeneratorService geminiFlashLlm;
    @MockitoBean("claudeSonnet") LlmGeneratorService claudeSonnetLlm;
    @MockitoBean GitService gitService;
    @MockitoBean GitHubApiService gitHubApiService;
    @MockitoBean BuildToolService buildToolService;

    // ── Real Spring beans ─────────────────────────────────────────────────
    @Autowired WorkerOrchestrator orchestrator;
    @Autowired WorkerContext ctx;
    @Autowired ArchitectBriefRepository briefRepo;
    @Autowired BusinessEntityRepository businessRepo;
    @Autowired ContainerTaskRepository taskRepo;
    @Autowired GeneratedFileRepository fileRepo;

    // Generated per-test IDs (Hibernate assigns them; we inject into WorkerContext)
    private UUID taskId;
    private UUID briefId;
    private UUID businessId;

    @BeforeEach
    void setUp() throws IOException {
        // Reset WorkerContext runtime fields — it is a singleton, carries state between tests
        ctx.setGithubRepoUrl("");
        ctx.setGithubPrUrl(null);
        ctx.setBrief(null);
        ctx.setBusiness(null);
        ctx.setTask(null);
        ctx.setFileManifest(new ArrayList<>());
        ctx.setBriefCtx(null);
        ctx.setProjectHistory(null);

        // Fresh workspace with pre-scaffolded structure so ProjectPlanningNode
        // skips CLI scaffold (Spring Initializr + Vite) during tests
        deleteRecursive(WORKSPACE);
        Files.createDirectories(WORKSPACE.resolve("backend/src/main/java/com/shreecafe/controller"));
        Files.createDirectories(WORKSPACE.resolve("backend/src/main/java/com/shreecafe/model"));
        Files.createDirectories(WORKSPACE.resolve("backend/src/main/java/com/shreecafe/service"));
        Files.createDirectories(WORKSPACE.resolve("backend/src/main/java/com/shreecafe/repository"));
        Files.createDirectories(WORKSPACE.resolve("backend/src/main/resources/static"));
        Files.createDirectories(WORKSPACE.resolve("frontend/src/api"));
        Files.createDirectories(WORKSPACE.resolve("frontend/src/pages"));
        Files.createDirectories(WORKSPACE.resolve("frontend/public"));
        Files.writeString(WORKSPACE.resolve("backend/pom.xml"), "<project/>");

        // Persist test rows — let Hibernate generate UUIDs to avoid merge-vs-insert conflicts
        ArchitectBrief brief = briefRepo.saveAndFlush(ArchitectBrief.builder()
                .runId(UUID.randomUUID())
                .businessCategory("Restaurant")
                .location("Pune")
                .mustHaveFeatures(List.of("Menu", "Reservations", "Contact"))
                .recommendedTechStack(Map.of("frontend", "React 19", "backend", "Spring Boot 3"))
                .websiteType(ArchitectBrief.WebsiteType.INFORMATIONAL)
                .build());
        briefId = brief.getId();

        BusinessEntity business = businessRepo.saveAndFlush(BusinessEntity.builder()
                .runId(UUID.randomUUID())
                .title("Shree Cafe")
                .category("Restaurant")
                .build());
        businessId = business.getId();

        ContainerTask task = taskRepo.saveAndFlush(ContainerTask.builder()
                .briefId(briefId)
                .businessId(businessId)
                .runId(UUID.randomUUID())
                .taskDescription(Map.of("test", "integration"))
                .status(ContainerTask.ContainerTaskStatus.RUNNING)
                .build());
        taskId = task.getId();

        // Inject generated IDs into WorkerContext (initial @Value was blank)
        ReflectionTestUtils.setField(ctx, "taskIdStr", taskId.toString());
        ReflectionTestUtils.setField(ctx, "briefIdStr", briefId.toString());
        ReflectionTestUtils.setField(ctx, "businessIdStr", businessId.toString());

        // LLM stubs — each bean is wired to its specific node role
        String backendPath   = "backend/src/main/java/com/shreecafe/controller/HomeController.java";
        String frontendPath  = "frontend/src/App.tsx";
        String instruction   = "// feature instruction from Pro";

        ArchitectureSpec spec = ArchitectureSpec.builder()
                .generatedAt("2026-05-30T10:00:00")
                .businessName("Shree Cafe")
                .basePackage("com.shreecafe")
                .projectDependencies(ProjectDependencies.builder()
                        .springBootStarters(List.of("web", "data-jpa", "postgresql", "lombok", "validation", "actuator"))
                        .npmPackages(List.of("@tanstack/react-query", "axios", "react-router-dom"))
                        .build())
                .files(List.of(
                        FileSpec.builder()
                                .fileName("HomeController.java")
                                .filePath(backendPath)
                                .fileType("BACKEND").layer("CONTROLLER").status("PLANNED")
                                .featureName("home-backend")
                                .fileRole("Exposes GET /api/home endpoint")
                                .description("Home REST controller").build(),
                        FileSpec.builder()
                                .fileName("App.tsx")
                                .filePath(frontendPath)
                                .fileType("FRONTEND").layer("PAGE").status("PLANNED")
                                .featureName("home-frontend")
                                .fileRole("Root React component with router")
                                .description("Root React component").build()
                ))
                .features(List.of(
                        FeatureSpec.builder()
                                .featureName("home-backend")
                                .featureType("BACKEND")
                                .featureInstruction(instruction)
                                .changeRequired(true)
                                .filePaths(List.of(backendPath))
                                .build(),
                        FeatureSpec.builder()
                                .featureName("home-frontend")
                                .featureType("FRONTEND")
                                .featureInstruction(instruction)
                                .changeRequired(true)
                                .filePaths(List.of(frontendPath))
                                .build()
                ))
                .build();
        when(geminiProLlm.generateArchitectureSpec(any(), any())).thenReturn(spec);

        // featureInstruction already set → allFeaturesHaveInstructions = true → enrichment loop skipped

        // App.tsx must carry router wiring or FrontendValidationNode's blank-SPA gate fails —
        // reflect what correct generation produces; every other file is a stub.
        org.mockito.stubbing.Answer<String> generatedAnswer = inv -> {
            String path = inv.getArgument(0);
            if (path != null && path.endsWith("App.tsx")) {
                return "import { BrowserRouter, Routes, Route } from 'react-router-dom'\n"
                     + "export default function App() { return <BrowserRouter><Routes>"
                     + "<Route path=\"/\" element={<div/>} /></Routes></BrowserRouter> }\n";
            }
            return "// generated";
        };
        // Stub both overloads — Infra calls the 4-arg form, backend/frontend the 6-arg form
        // (shared contract section + feature context).
        when(geminiFlashLlm.generateFileContent(anyString(), anyString(), anyMap(), any()))
                .thenAnswer(generatedAnswer);
        when(geminiFlashLlm.generateFileContent(anyString(), anyString(), anyMap(), any(), any(), any()))
                .thenAnswer(generatedAnswer);

        // GitHub stubs
        when(gitHubApiService.createRepo(any(), any())).thenReturn(REPO_URL);
        when(gitHubApiService.createPullRequest(any(), any(), any(), any())).thenReturn(PR_URL);

        // Spec compliance: compliant by default so Stage 2 passes without real Pro calls
        when(geminiProLlm.checkSpecCompliance(any(), any(), any()))
                .thenReturn(com.business.discovery.worker.service.llm.ComplianceResult.ok());

        // Build tools: succeed so validation nodes pass without real builds
        BuildToolService.BuildResult ok = new BuildToolService.BuildResult(0, "");
        when(buildToolService.runMvnCompile(any())).thenReturn(ok);
        when(buildToolService.runNpmInstall(any())).thenReturn(ok);
        when(buildToolService.runNpmBuild(any())).thenReturn(ok);
        when(buildToolService.runTscCheck(any())).thenReturn(ok);
        when(buildToolService.runEslintFix(any())).thenReturn(ok);
        when(buildToolService.runEslintCycleCheck(any())).thenReturn(ok);
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteRecursive(WORKSPACE);
        fileRepo.deleteAll();
        if (taskId != null) taskRepo.deleteById(taskId);
        if (briefId != null) briefRepo.deleteById(briefId);
        if (businessId != null) businessRepo.deleteById(businessId);
    }

    @Test
    void allNodesExecute_generatedFilesInDb_projectHistoryOnDisk() throws IOException {
        orchestrator.run();

        // DB: 1 BACKEND + 1 FRONTEND GeneratedFile row (GENERATED — no spec-compliance node is
        // wired into this pipeline yet, so SPEC_COMPLIANT is never assigned) plus the derived
        // api/client.ts row ApiArtifactGeneratorNode always emits
        List<GeneratedFile> files = fileRepo.findByTaskId(taskId);
        assertThat(files).hasSize(3);
        assertThat(files)
                .extracting(GeneratedFile::getFilePath, GeneratedFile::getStatus)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                "backend/src/main/java/com/shreecafe/controller/HomeController.java",
                                FileStatus.GENERATED),
                        org.assertj.core.groups.Tuple.tuple(
                                "frontend/src/App.tsx", FileStatus.GENERATED),
                        org.assertj.core.groups.Tuple.tuple(
                                "frontend/src/api/client.ts", FileStatus.VALIDATED));
        assertThat(WORKSPACE.resolve("frontend/src/api/client.ts")).exists();

        // Filesystem: docs/PROJECT_HISTORY.md written with attempt section
        Path history = WORKSPACE.resolve("docs/PROJECT_HISTORY.md");
        assertThat(history).exists();
        String historyContent = Files.readString(history);
        assertThat(historyContent)
                .contains("Attempt 1")
                .contains("Shree Cafe");

        // Filesystem: README.md and CI workflow written
        assertThat(WORKSPACE.resolve("README.md")).exists();
        assertThat(WORKSPACE.resolve(".github/workflows/ci.yml")).exists();
    }

    @Test
    void workerUpdatesGithubUrls_masterCanReadThemBack() {
        orchestrator.run();

        // Each findById here starts a fresh transaction with a clean L1 cache,
        // so it always reads the committed values written by the nodes.
        ContainerTask updated = taskRepo.findById(taskId).orElseThrow();
        assertThat(updated.getGithubRepoUrl()).isEqualTo(REPO_URL);
        assertThat(updated.getGithubPrUrl()).isEqualTo(PR_URL);
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }
}
