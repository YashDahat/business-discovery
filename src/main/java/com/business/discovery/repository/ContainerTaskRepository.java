package com.business.discovery.repository;

import com.business.discovery.model.ContainerTask;
import com.business.discovery.model.ContainerTask.ContainerTaskStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContainerTaskRepository extends JpaRepository<ContainerTask, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM ContainerTask t WHERE t.id = :id")
    Optional<ContainerTask> findByIdForUpdate(@Param("id") UUID id);

    List<ContainerTask> findByStatus(ContainerTaskStatus status);

    List<ContainerTask> findByBriefId(UUID briefId);

    List<ContainerTask> findByBusinessId(UUID businessId);

    Optional<ContainerTask> findByDockerContainerId(String dockerContainerId);

    // For orphan recovery — find all tasks that were RUNNING when master restarted
    List<ContainerTask> findByStatusIn(List<ContainerTaskStatus> statuses);

    long countByStatus(ContainerTaskStatus status);

    Optional<ContainerTask> findTopByBriefIdOrderByCreatedAtDesc(UUID briefId);
}