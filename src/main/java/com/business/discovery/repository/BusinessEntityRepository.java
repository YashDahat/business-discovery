package com.business.discovery.repository;

import com.business.discovery.model.BusinessEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BusinessEntityRepository extends JpaRepository<BusinessEntity, UUID> {

    List<BusinessEntity> findByRunId(UUID runId);

    List<BusinessEntity> findByCategory(String category);

    Optional<BusinessEntity> findByGoogleCid(String googleCid);

    Optional<BusinessEntity> findByGooglePlaceId(String googlePlaceId);

    List<BusinessEntity> findByRatingGreaterThanEqual(Double rating);

    // Find BusinessEntityes scraped by a specific gosom job
    List<BusinessEntity> findByScraperJobId(String scraperJobId);

    // Check if a BusinessEntity already exists to avoid duplicates
    boolean existsByGoogleCid(String googleCid);

    // Find top rated BusinessEntityes in a category
    @Query("SELECT b FROM BusinessEntity b WHERE b.category = :category ORDER BY b.rating DESC")
    List<BusinessEntity> findTopRatedByCategory(String category);

    // Count BusinessEntityes per scraper job
    long countByScraperJobId(String scraperJobId);

    List<BusinessEntity> findByRunIdAndBusinessTier(UUID runId, String businessTier);
}