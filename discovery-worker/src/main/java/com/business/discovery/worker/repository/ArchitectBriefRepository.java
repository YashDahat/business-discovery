package com.business.discovery.worker.repository;

import com.business.discovery.worker.model.ArchitectBrief;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ArchitectBriefRepository extends JpaRepository<ArchitectBrief, UUID> {
}
