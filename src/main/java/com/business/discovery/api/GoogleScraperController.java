package com.business.discovery.api;

import com.business.discovery.dto.GoogleMapsScraperJobRequest;
import com.business.discovery.model.ArchitectBrief;
import com.business.discovery.model.BusinessEntity;
import com.business.discovery.model.ContainerTask;
import com.business.discovery.repository.ArchitectBriefRepository;
import com.business.discovery.repository.BusinessEntityRepository;
import com.business.discovery.repository.ContainerTaskRepository;
import com.business.discovery.services.container.DockerContainerService;
import com.business.discovery.services.scraper.GoogleMapsScraperService;
import com.business.discovery.services.scraper.ScraperContainerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/scraper")
@RequiredArgsConstructor
public class GoogleScraperController {

    private final GoogleMapsScraperService scraperService;
    private final ScraperContainerService scraperContainerService;
    private final DockerContainerService dockerContainerService;
    private final BusinessEntityRepository businessEntityRepository;
    private final ArchitectBriefRepository architectBriefRepository;
    private final ContainerTaskRepository containerTaskRepository;

    // ─── Scraper container lifecycle ──────────────────────────────────────────

    @GetMapping("/container/status")
    public ResponseEntity<Map<String, String>> getContainerStatus() {
        var status = scraperContainerService.getStatus();
        return ResponseEntity.ok(Map.of(
                "state", status.state().name(),
                "containerId", status.containerId() != null ? status.containerId() : ""
        ));
    }

    @PostMapping("/container/start")
    public ResponseEntity<Map<String, String>> startContainer() {
        log.info("Scraper container start requested");
        try {
            scraperContainerService.start();
            return ResponseEntity.ok(Map.of(
                    "state", "RUNNING",
                    "message", "Scraper container started"
            ));
        } catch (Exception e) {
            log.error("Failed to start scraper container: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "state", "ERROR",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/container/stop")
    public ResponseEntity<Map<String, String>> stopContainer() {
        log.info("Scraper container stop requested");
        try {
            scraperContainerService.stop();
            return ResponseEntity.ok(Map.of(
                    "state", "STOPPED",
                    "message", "Scraper container stopped and removed"
            ));
        } catch (Exception e) {
            log.error("Failed to stop scraper container: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "state", "ERROR",
                    "message", e.getMessage()
            ));
        }
    }

    @GetMapping("/container/logs")
    public ResponseEntity<Map<String, String>> getContainerLogs() {
        var status = scraperContainerService.getStatus();
        if (status.state() == ScraperContainerService.ContainerState.NOT_FOUND) {
            return ResponseEntity.ok(Map.of("logs", "", "state", "NOT_FOUND"));
        }
        String logs = dockerContainerService.getContainerLogs(status.containerId());
        return ResponseEntity.ok(Map.of(
                "logs", logs != null ? logs : "",
                "state", status.state().name()
        ));
    }

    // Submit job + wait + persist — full pipeline
    @PostMapping("/scrape")
    public ResponseEntity<ScrapeResponse> scrape(@RequestBody ScrapeRequest request) {
        log.info("Scrape request received — keyword: {}", request.keyword());
        GoogleMapsScraperJobRequest scrapperRequest = scraperService.toJobRequest(request);
        List<BusinessEntity> businesses = scraperService.scrapeAndPersist(
                scrapperRequest, null
        );

        return ResponseEntity.ok(new ScrapeResponse(
                businesses.size(),
                "Successfully scraped and persisted " + businesses.size() + " businesses",
                businesses
        ));
    }

    // Just submit the job — returns job ID immediately without waiting
    @PostMapping("/jobs")
    public ResponseEntity<JobResponse> submitJob(@RequestBody ScrapeRequest request) {
        log.info("Job submission request — keyword: {}", request.keyword());
        GoogleMapsScraperJobRequest scraperJobRequest = scraperService.toJobRequest(request);
        String jobId = scraperService.submitJob(scraperJobRequest);
        return ResponseEntity.accepted().body(new JobResponse(jobId, "pending"));
    }

    // Check job status
    @GetMapping("/jobs/{jobId}/status")
    public ResponseEntity<JobResponse> getStatus(@PathVariable String jobId) {
        String status = scraperService.getJobStatus(jobId);
        return ResponseEntity.ok(new JobResponse(jobId, status));
    }

    // Download and persist results for a completed job
    @PostMapping("/jobs/{jobId}/persist")
    public ResponseEntity<ScrapeResponse> persistResults(@PathVariable String jobId) {
        log.info("Persist request for job: {}", jobId);
        List<BusinessEntity> businesses = scraperService.downloadAndPersist(jobId, null);
        return ResponseEntity.ok(new ScrapeResponse(
                businesses.size(),
                "Persisted " + businesses.size() + " businesses for job " + jobId,
                businesses
        ));
    }

    // List businesses with optional search/category/tier/runId filters + enriched status fields
    @GetMapping("/businesses")
    public ResponseEntity<List<BusinessSummaryResponse>> getAllBusinesses(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tier,
            @RequestParam(required = false) UUID runId) {

        List<BusinessEntity> businesses = runId != null
                ? businessEntityRepository.findByRunId(runId)
                : businessEntityRepository.findAllByOrderByCreatedAtDesc();

        if (search != null && !search.isBlank()) {
            String lower = search.toLowerCase();
            businesses = businesses.stream()
                    .filter(b -> b.getTitle() != null && b.getTitle().toLowerCase().contains(lower))
                    .toList();
        }
        if (category != null && !category.isBlank()) {
            businesses = businesses.stream()
                    .filter(b -> category.equalsIgnoreCase(b.getCategory()))
                    .toList();
        }
        if (tier != null && !tier.isBlank()) {
            businesses = businesses.stream()
                    .filter(b -> tier.equalsIgnoreCase(b.getBusinessTier()))
                    .toList();
        }

        List<BusinessSummaryResponse> result = businesses.stream()
                .map(this::toSummary)
                .toList();

        return ResponseEntity.ok(result);
    }

    // Get full business detail by ID
    @GetMapping("/businesses/{id}")
    public ResponseEntity<BusinessDetailResponse> getBusinessById(@PathVariable UUID id) {
        return businessEntityRepository.findById(id)
                .map(b -> {
                    Optional<ArchitectBrief> brief = architectBriefRepository.findByBusinessId(id);
                    List<ContainerTask> tasks = containerTaskRepository.findByBusinessId(id);
                    Optional<ContainerTask> latestTask = tasks.stream()
                            .max(java.util.Comparator.comparing(ContainerTask::getCreatedAt));
                    return ResponseEntity.ok(new BusinessDetailResponse(
                            b,
                            brief.orElse(null),
                            latestTask.orElse(null),
                            deriveOpsStatus(brief, latestTask),
                            deriveScopeProgress(b, brief, latestTask)
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // Get businesses by category — convenience endpoint
    @GetMapping("/businesses/category/{category}")
    public ResponseEntity<List<BusinessSummaryResponse>> getByCategory(@PathVariable String category) {
        List<BusinessSummaryResponse> result = businessEntityRepository.findByCategory(category)
                .stream().map(this::toSummary).toList();
        return ResponseEntity.ok(result);
    }

    private BusinessSummaryResponse toSummary(BusinessEntity b) {
        Optional<ArchitectBrief> brief = architectBriefRepository.findByBusinessId(b.getId());
        List<ContainerTask> tasks = containerTaskRepository.findByBusinessId(b.getId());

        Optional<ContainerTask> latestTask = tasks.stream()
                .max(java.util.Comparator.comparing(ContainerTask::getCreatedAt));

        String opsStatus = deriveOpsStatus(brief, latestTask);
        String scopeProgress = deriveScopeProgress(b, brief, latestTask);

        return new BusinessSummaryResponse(
                b.getId(),
                b.getTitle(),
                b.getCategory(),
                b.getAddress(),
                b.getPhone(),
                b.getRating(),
                b.getReviewCount(),
                b.getRevenueEstimate(),
                b.getBusinessTier(),
                b.getWebsite(),
                opsStatus,
                scopeProgress,
                b.getCreatedAt()
        );
    }

    private String deriveOpsStatus(Optional<ArchitectBrief> brief, Optional<ContainerTask> latestTask) {
        if (latestTask.isPresent()) {
            ContainerTask task = latestTask.get();
            if (task.getStatus() == ContainerTask.ContainerTaskStatus.COMPLETED) {
                return task.getGithubPrUrl() != null ? "LIVE" : "DEMO";
            }
            if (task.getStatus() == ContainerTask.ContainerTaskStatus.RUNNING ||
                task.getStatus() == ContainerTask.ContainerTaskStatus.RETRYING) {
                return "GENERATING";
            }
        }
        return brief.isPresent() ? "BRIEF" : "SCRAPE";
    }

    private String deriveScopeProgress(BusinessEntity b, Optional<ArchitectBrief> brief,
                                        Optional<ContainerTask> latestTask) {
        int score = 1; // scraped
        if (b.getBusinessTier() != null) score++;        // scored
        if (brief.isPresent()) score++;                   // brief generated
        if (brief.isPresent() && brief.get().getWebsiteType() != null) score++; // brief finalized
        if (latestTask.isPresent() &&
            latestTask.get().getStatus() == ContainerTask.ContainerTaskStatus.COMPLETED) score++; // site built
        if (latestTask.isPresent() && latestTask.get().getGithubPrUrl() != null) score++; // PR live
        return score + "/6";
    }

    public record BusinessDetailResponse(
            BusinessEntity business,
            ArchitectBrief brief,
            ContainerTask latestTask,
            String opsStatus,
            String scopeProgress
    ) {}

    public record BusinessSummaryResponse(
            UUID id,
            String title,
            String category,
            String address,
            String phone,
            Double rating,
            Integer reviewCount,
            String revenueEstimate,
            String businessTier,
            String website,
            String opsStatus,      // SCRAPE | BRIEF | GENERATING | DEMO | LIVE
            String scopeProgress,  // e.g. "3/6"
            java.time.LocalDateTime createdAt
    ) {}

    // --- Request / Response records ---

    public record ScrapeRequest(

            String keyword,

            // ─── Optional scraper settings ───
            String lang,
            Integer depth,
            Integer zoom,
            String lat,
            String lon,
            Boolean fastMode,
            Integer radius,
            Boolean email,
            Integer maxTime,
            List<String> proxies
    ) {
        // Apply defaults for null values
        public ScrapeRequest {
            lang     = lang     != null ? lang     : "en";
            depth    = depth    != null ? depth    : 5;
            zoom     = zoom     != null ? zoom     : 15;
            fastMode = fastMode != null ? fastMode : false;
            radius   = radius   != null ? radius   : 10000;
            email    = email    != null ? email    : false;
            maxTime  = maxTime  != null ? maxTime  : 60;
        }
    }

    public record JobResponse(String jobId, String status) {}

    public record ScrapeResponse(
            int count,
            String message,
            List<BusinessEntity> businesses
    ) {}
}