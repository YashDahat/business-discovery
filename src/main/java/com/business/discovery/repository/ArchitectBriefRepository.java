package com.business.discovery.repository;

import com.business.discovery.model.ArchitectBrief;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ArchitectBriefRepository extends JpaRepository<ArchitectBrief, UUID> {

    Optional<ArchitectBrief> findByRunId(UUID runId);

    boolean existsByRunId(UUID runId);

    Optional<ArchitectBrief> findByBusinessId(UUID businessId);
    boolean existsByBusinessId(UUID businessId);

    List<ArchitectBrief> findAllByRunId(UUID runId);
}