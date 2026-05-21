package com.business.discovery.services.container;

import com.business.discovery.model.ContainerPoolSlot;
import com.business.discovery.model.ContainerTask.ContainerTaskStatus;
import com.business.discovery.repository.ContainerPoolSlotRepository;
import com.business.discovery.repository.ContainerTaskRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContainerPoolManager {

    private final ContainerPoolSlotRepository poolSlotRepository;
    private final ContainerTaskRepository containerTaskRepository;

    @Value("${docker.pool.max-size:2}")
    private int maxPoolSize;

    // ─── Initialise pool on startup ───────────────────────

    @PostConstruct
    @Transactional
    public void initialise() {
        ContainerPoolSlot slot = poolSlotRepository.findById(1)
                .orElse(ContainerPoolSlot.builder()
                        .id(1)
                        .poolSize(maxPoolSize)
                        .activeSlots(0)
                        .build());

        // Recalibrate active slots from DB on restart — orphan recovery
        long actualRunning = containerTaskRepository
                .countByStatus(ContainerTaskStatus.RUNNING);

        slot.setPoolSize(maxPoolSize);
        slot.setActiveSlots((int) actualRunning);
        poolSlotRepository.save(slot);

        log.info("Container pool initialised — size: {}, active: {}, available: {}",
                maxPoolSize, actualRunning, maxPoolSize - actualRunning);
    }

    // ─── Slot management ──────────────────────────────────

    @Transactional
    public boolean tryAcquireSlot() {
        ContainerPoolSlot slot = poolSlotRepository.findByIdForUpdate(1)
                .orElseThrow(() -> new IllegalStateException("Pool not initialised"));

        if (!slot.hasAvailableSlot()) {
            log.info("Pool full — {}/{} slots in use", slot.getActiveSlots(), slot.getPoolSize());
            return false;
        }

        slot.acquire();
        poolSlotRepository.save(slot);
        log.info("Slot acquired — {}/{} slots now in use", slot.getActiveSlots(), slot.getPoolSize());
        return true;
    }

    @Transactional
    public void releaseSlot() {
        ContainerPoolSlot slot = poolSlotRepository.findByIdForUpdate(1)
                .orElseThrow(() -> new IllegalStateException("Pool not initialised"));
        slot.release();
        poolSlotRepository.save(slot);
        log.info("Slot released — {}/{} slots now in use", slot.getActiveSlots(), slot.getPoolSize());
    }

    @Transactional
    public void releaseAllSlots() {
        ContainerPoolSlot slot = getPool();
        slot.setActiveSlots(0);
        poolSlotRepository.save(slot);
        log.info("All slots released — pool reset to 0/{}", slot.getPoolSize());
    }

    public boolean isSlotAvailable() {
        return getPool().hasAvailableSlot();
    }

    public int availableSlots() {
        return getPool().availableSlots();
    }

    public int activeSlots() {
        return getPool().getActiveSlots();
    }

    public int poolSize() {
        return getPool().getPoolSize();
    }

    private ContainerPoolSlot getPool() {
        return poolSlotRepository.findById(1)
                .orElseThrow(() -> new IllegalStateException(
                        "Pool not initialised — call initialise() first"));
    }
}