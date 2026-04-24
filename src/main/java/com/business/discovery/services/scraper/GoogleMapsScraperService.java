package com.business.discovery.services.scraper;

import com.business.discovery.dto.GoogleMapsScraperJobRequest;
import com.business.discovery.dto.GoogleMapsScraperJobResponse;
import com.business.discovery.model.BusinessEntity;
import com.business.discovery.repository.BusinessEntityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleMapsScraperService {

    private final RestClient scraperRestClient;
    private final BusinessEntityRepository businessRepository;

    // Step 1: Submit a scraping job to gosom
    public String submitJob(String keyword) {
        log.info("Submitting scrape job for keyword: {}", keyword);

        GoogleMapsScraperJobRequest request = GoogleMapsScraperJobRequest.builder()
                .name(keyword)                    // name is mandatory
                .keywords(List.of(keyword))       // keywords is mandatory — wrap in list
                .depth(5)
                .maxTime(1000)
                .build();

        GoogleMapsScraperJobResponse response = scraperRestClient.post()
                .uri("/api/v1/jobs")
                .body(request)
                .retrieve()
                .body(GoogleMapsScraperJobResponse.class);

        log.info("Scrape job submitted — job ID: {}", response.getId());
        return response.getId();
    }

    // Step 2: Poll job status
    public String getJobStatus(String jobId) {
        GoogleMapsScraperJobResponse response = scraperRestClient.get()
                .uri("/api/v1/jobs/{id}", jobId)
                .retrieve()
                .body(GoogleMapsScraperJobResponse.class);

        log.info("Job {} status: {}", jobId, response);
        assert response != null;
        return response.getStatus();
    }

    // Step 3: Wait until job completes (blocking poll — time is not a constraint)
    public void waitForCompletion(String jobId) {
        log.info("Waiting for job {} to complete...", jobId);

        int maxAttempts = 60; // 60 attempts × 60s = max 1 hour wait
        int attempt = 0;

        while (attempt < maxAttempts) {
            attempt++;

            try {
                String status = getJobStatus(jobId);

                // Handle null or empty status — gosom may not populate it immediately
                if (status == null || status.isBlank()) {
                    log.info("Job {} status not yet available (attempt {}/{}), waiting 20s...",
                            jobId, attempt, maxAttempts);
                    sleep(20_000);
                    continue;
                }

                log.info("Job {} status: {} (attempt {}/{})", jobId, status, attempt, maxAttempts);

                switch (status.toLowerCase().trim()) {
                    case "ok" -> {                        // ← gosom uses "ok" not "completed"
                        log.info("Job {} completed successfully after {} attempts", jobId, attempt);
                        return;
                    }
                    case "failed", "error" -> throw new RuntimeException(
                            "Scraper job " + jobId + " failed after " + attempt + " attempts"
                    );
                    case "pending", "running", "in_progress" -> {
                        log.info("Job {} still in progress, waiting 20s...", jobId);
                        sleep(20_000);
                    }
                    default -> {
                        log.warn("Job {} unknown status: '{}', waiting 20s...", jobId, status);
                        sleep(20_000);
                    }
                }

            } catch (RuntimeException e) {
                // Rethrow failed job exception directly
                if (e.getMessage() != null && e.getMessage().contains("failed after")) {
                    throw e;
                }
                // For any other exception (network blip etc.) log and retry
                log.warn("Job {} poll attempt {} failed with error: {}, retrying in 6s...",
                        jobId, attempt, e.getMessage());
                sleep(20_000);
            }
        }

        throw new RuntimeException(
                "Job " + jobId + " did not complete within maximum wait time of "
                        + (maxAttempts * 60 / 60) + " hours"
        );
    }

    // Step 4: Download results directly into Business entity and persist
    public List<BusinessEntity> downloadAndPersist(String jobId, UUID runId) {
        log.info("Downloading CSV results for job {}", jobId);

        byte[] csvBytes = scraperRestClient.get()
                .uri("/api/v1/jobs/{id}/download", jobId)
                .retrieve()
                .body(byte[].class);

        if (csvBytes == null || csvBytes.length == 0) {
            log.warn("Empty CSV for job {}", jobId);
            return Collections.emptyList();
        }

        List<BusinessEntity> businesses = new ArrayList<>();

        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(csvBytes));
             CSVParser parser = CSVFormat.DEFAULT
                     .withFirstRecordAsHeader()
                     .withIgnoreHeaderCase()
                     .withTrim()
                     .parse(reader)) {

            for (CSVRecord record : parser) {
                BusinessEntity b = mapCsvRecordToBusiness(record, jobId, runId);
                if (b.getGoogleCid() != null
                        && !businessRepository.existsByGoogleCid(b.getGoogleCid())) {
                    businesses.add(b);
                }
            }

        } catch (Exception e) {
            log.error("Failed to parse CSV for job {}: {}", jobId, e.getMessage());
            throw new RuntimeException("CSV parsing failed", e);
        }

        List<BusinessEntity> saved = businessRepository.saveAll(businesses);
        log.info("Persisted {} businesses from CSV", saved.size());
        return saved;
    }

    private BusinessEntity mapCsvRecordToBusiness(CSVRecord record, String jobId, UUID runId) {
        return BusinessEntity.builder()
                .runId(runId)
                .scraperJobId(jobId)
                .title(get(record, "title"))
                .category(get(record, "category"))
                .address(get(record, "address"))
                .phone(get(record, "phone"))
                .website(get(record, "web_site"))
                .latitude(parseDouble(record, "latitude"))
                .longitude(parseDouble(record, "longtitude"))  // gosom typo — longtitude
                .rating(parseDouble(record, "review_rating"))
                .reviewCount(parseInt(record, "review_count"))
                .priceRange(get(record, "price_range"))
                .status(get(record, "status"))
                .description(get(record, "description"))
                .googleCid(get(record, "cid"))
                .googlePlaceId(get(record, "place_id"))
                .mapsLink(get(record, "link"))
                .reviewsLink(get(record, "reviews_link"))
                .thumbnail(get(record, "thumbnail"))
                .timezone(get(record, "timezone"))
                .build();
    }

    // Safe helpers
    private String get(CSVRecord r, String col) {
        try { return r.get(col); } catch (Exception e) { return null; }
    }

    private Double parseDouble(CSVRecord r, String col) {
        try { return Double.parseDouble(r.get(col)); } catch (Exception e) { return null; }
    }

    private Integer parseInt(CSVRecord r, String col) {
        try { return Integer.parseInt(r.get(col)); } catch (Exception e) { return null; }
    }

    // Full pipeline: submit → wait → download → persist
    public List<BusinessEntity> scrapeAndPersist(String keyword, UUID runId) {
        String jobId = submitJob(keyword);
        waitForCompletion(jobId);
        return downloadAndPersist(jobId, runId);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Polling interrupted", e);
        }
    }
}