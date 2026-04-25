package com.business.discovery.repository;

import com.business.discovery.model.AgentEvent;
import com.business.discovery.model.AgentEvent.EventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AgentEventRepository extends JpaRepository<AgentEvent, UUID> {

    List<AgentEvent> findByRunIdOrderByCreatedAtAsc(UUID runId);

    List<AgentEvent> findByRunIdAndEventType(UUID runId, EventType eventType);

    long countByRunId(UUID runId);
}