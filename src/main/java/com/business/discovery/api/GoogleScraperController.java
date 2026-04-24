package com.business.discovery.api;

import com.business.discovery.model.BusinessEntity;
import com.business.discovery.services.scraper.GoogleMapsScraperService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
    @RequestMapping("/api/v1/scraper")
@RequiredArgsConstructor
public class GoogleScraperController {

    private final GoogleMapsScraperService scraperService;

    // Submit job + wait + persist — full pipeline
    @PostMapping("/scrape")
    public ResponseEntity<ScrapeResponse> scrape(@RequestBody ScrapeRequest request) {
        log.info("Scrape request received — keyword: {}", request.keyword());

        List<BusinessEntity> businesses = scraperService.scrapeAndPersist(
                request.keyword(), null
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
        String jobId = scraperService.submitJob(request.keyword());
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

//    // Get all businesses from DB
//    @GetMapping("/businesses")
//    public ResponseEntity<List<Business>> getAllBusinesses() {
//        return ResponseEntity.ok(scraperService.getAllBusinesses());
//    }
//
//    // Get businesses by keyword/category
//    @GetMapping("/businesses/category/{category}")
//    public ResponseEntity<List<Business>> getByCategory(@PathVariable String category) {
//        return ResponseEntity.ok(scraperService.getByCategory(category));
//    }

    // --- Request / Response records ---

    public record ScrapeRequest(String keyword) {}

    public record JobResponse(String jobId, String status) {}

    public record ScrapeResponse(
            int count,
            String message,
            List<BusinessEntity> businesses
    ) {}
}