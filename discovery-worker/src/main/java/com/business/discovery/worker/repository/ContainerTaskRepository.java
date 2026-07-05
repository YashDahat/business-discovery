package com.business.discovery.worker.repository;

import com.business.discovery.worker.model.ContainerTask;
import com.business.discovery.worker.model.ContainerTask.ContainerTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Repository
public interface ContainerTaskRepository extends JpaRepository<ContainerTask, UUID> {

    @Modifying
    @Transactional
    @Query("UPDATE ContainerTask t SET t.githubRepoUrl = :url WHERE t.id = :id")
    void updateGithubRepoUrl(@Param("id") UUID id, @Param("url") String url);

    @Modifying
    @Transactional
    @Query("UPDATE ContainerTask t SET t.githubPrUrl = :url WHERE t.id = :id")
    void updateGithubPrUrl(@Param("id") UUID id, @Param("url") String url);

    @Modifying
    @Transactional
    @Query("UPDATE ContainerTask t SET t.status = :status WHERE t.id = :id")
    void updateStatus(@Param("id") UUID id, @Param("status") ContainerTaskStatus status);

    @Modifying
    @Transactional
    @Query("UPDATE ContainerTask t SET t.publishedImage = :imageRef WHERE t.id = :id")
    void updatePublishedImage(@Param("id") UUID id, @Param("imageRef") String imageRef);

    @Modifying
    @Transactional
    @Query("UPDATE ContainerTask t SET t.failedGate = :gate WHERE t.id = :id")
    void updateFailedGate(@Param("id") UUID id, @Param("gate") String gate);

    @Modifying
    @Transactional
    @Query("UPDATE ContainerTask t SET t.llmInputTokens = :inputTokens, t.llmOutputTokens = :outputTokens, t.generationCostUsd = :costUsd WHERE t.id = :id")
    void updateGenerationCost(@Param("id") UUID id,
                              @Param("inputTokens") long inputTokens,
                              @Param("outputTokens") long outputTokens,
                              @Param("costUsd") double costUsd);
}
