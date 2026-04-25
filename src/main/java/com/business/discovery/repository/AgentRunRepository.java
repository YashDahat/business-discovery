package com.business.discovery.repository;

import com.business.discovery.model.AgentRun;
import com.business.discovery.model.AgentRun.AgentRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AgentRunRepository extends JpaRepository<AgentRun, UUID> {

    List<AgentRun> findByStatusOrderByCreatedAtDesc(AgentRunStatus status);

    List<AgentRun> findAllByOrderByCreatedAtDesc();

    List<AgentRun> findByCategoryAndLocation(String category, String location);
}