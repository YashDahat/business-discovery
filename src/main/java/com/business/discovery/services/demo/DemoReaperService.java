package com.business.discovery.services.demo;

import com.business.discovery.model.DemoInstance;
import com.business.discovery.model.DemoInstance.DemoStatus;
import com.business.discovery.repository.DemoInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Tears down expired demos. Each demo stack holds ~1GB RAM (Spring app + Postgres),
 * so forgotten demos would starve the worker pool — same lifecycle discipline as
 * ContainerMonitorService applies to workers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoReaperService {

    private final DemoInstanceRepository demoRepo;
    private final DemoService demoService;

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void reapExpiredDemos() {
        List<DemoInstance> expired = demoRepo.findByStatusAndExpiresAtBefore(
                DemoStatus.RUNNING, LocalDateTime.now());
        for (DemoInstance demo : expired) {
            try {
                log.info("[DemoReaper] Reaping expired demo {} (expired {})",
                        demo.getSlug(), demo.getExpiresAt());
                demoService.stop(demo);
            } catch (Exception e) {
                log.warn("[DemoReaper] Could not reap demo {}: {}", demo.getId(), e.getMessage());
            }
        }
    }
}
