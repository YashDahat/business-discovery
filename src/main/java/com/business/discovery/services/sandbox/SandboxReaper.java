package com.business.discovery.services.sandbox;

import com.business.discovery.model.SandboxInstance;
import com.business.discovery.model.SandboxInstance.SandboxStatus;
import com.business.discovery.repository.SandboxInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Stops + removes sandbox containers that have been idle past {@code sandbox.idle-timeout-minutes},
 * freeing capacity and host resources. Mirrors the scheduled-poll style of {@code ContainerMonitorService}.
 * A reaped sandbox is simply recreated (and re-cloned) on the next tool call for that brief.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxReaper {

    private final SandboxInstanceRepository sandboxRepository;
    private final SandboxManager sandboxManager;

    @Value("${sandbox.idle-timeout-minutes:30}")
    private int idleTimeoutMinutes;

    @Scheduled(fixedDelay = 60_000)
    public void reapIdle() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(idleTimeoutMinutes);
        List<SandboxInstance> idle =
                sandboxRepository.findByStatusAndLastUsedAtBefore(SandboxStatus.READY, cutoff);
        for (SandboxInstance s : idle) {
            log.info("[SandboxReaper] Reaping idle sandbox {} (brief {}) — last used {}",
                    s.getContainerName(), s.getBriefId(), s.getLastUsedAt());
            try {
                sandboxManager.destroy(s);
            } catch (Exception e) {
                log.warn("[SandboxReaper] Failed to reap {}: {}", s.getContainerName(), e.getMessage());
            }
        }
    }
}
