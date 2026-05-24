package com.business.discovery.repository;

import com.business.discovery.model.ArchitectBrief;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    @Modifying
    @Transactional
    @Query("UPDATE ArchitectBrief b SET b.requestedChanges = :changes WHERE b.id = :id")
    void updateRequestedChanges(@Param("id") UUID id, @Param("changes") String changes);
}