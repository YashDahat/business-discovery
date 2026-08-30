package com.business.discovery.repository;

import com.business.discovery.model.SandboxInstance;
import com.business.discovery.model.SandboxInstance.SandboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SandboxInstanceRepository extends JpaRepository<SandboxInstance, UUID> {

    Optional<SandboxInstance> findFirstByBriefIdAndStatusIn(UUID briefId, Collection<SandboxStatus> statuses);

    // Latest instance for a brief regardless of status — used to preserve the working branch across
    // sandbox recreation (idle-reap / container death).
    Optional<SandboxInstance> findFirstByBriefIdOrderByCreatedAtDesc(UUID briefId);

    List<SandboxInstance> findByStatusIn(Collection<SandboxStatus> statuses);

    List<SandboxInstance> findByStatusAndLastUsedAtBefore(SandboxStatus status, LocalDateTime cutoff);

    long countByStatusIn(Collection<SandboxStatus> statuses);
}
