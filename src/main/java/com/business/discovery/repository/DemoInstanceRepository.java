package com.business.discovery.repository;

import com.business.discovery.model.DemoInstance;
import com.business.discovery.model.DemoInstance.DemoStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DemoInstanceRepository extends JpaRepository<DemoInstance, UUID> {

    Optional<DemoInstance> findFirstByBriefIdOrderByStartedAtDesc(UUID briefId);

    Optional<DemoInstance> findFirstByBriefIdAndStatusIn(UUID briefId, Collection<DemoStatus> statuses);

    List<DemoInstance> findByStatusIn(Collection<DemoStatus> statuses);

    List<DemoInstance> findByStatusAndExpiresAtBefore(DemoStatus status, LocalDateTime cutoff);

    boolean existsByHostPortAndStatusIn(Integer hostPort, Collection<DemoStatus> statuses);
}
