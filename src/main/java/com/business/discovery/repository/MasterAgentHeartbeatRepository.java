package com.business.discovery.repository;

import com.business.discovery.model.MasterAgentHeartbeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MasterAgentHeartbeatRepository extends JpaRepository<MasterAgentHeartbeat, Integer> {
    // Single row — always use findById(1)
}