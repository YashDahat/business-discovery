package com.business.discovery.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "business")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class BusinessEntity {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "scraper_job_id")
    private String scraperJobId;

    @Column(name = "title")
    private String title;

    @Column(name = "category")
    private String category;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "phone")
    private String phone;

    @Column(name = "website")
    private String website;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "rating")
    private Double rating;

    @Column(name = "review_count")
    private Integer reviewCount;

    @Column(name = "price_range")
    private String priceRange;

    @Column(name = "status")
    private String status;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "google_cid")
    private String googleCid;

    @Column(name = "google_place_id")
    private String googlePlaceId;

    @Column(name = "google_data_id")
    private String googleDataId;

    @Column(name = "maps_link", columnDefinition = "TEXT")
    private String mapsLink;

    @Column(name = "reviews_link", columnDefinition = "TEXT")
    private String reviewsLink;

    @Column(name = "thumbnail", columnDefinition = "TEXT")
    private String thumbnail;

    @Column(name = "timezone")
    private String timezone;

    @Column(name = "reservation_link", columnDefinition = "TEXT")
    private String reservationLink;

    @Column(name = "order_online_link", columnDefinition = "TEXT")
    private String orderOnlineLink;

    @Column(name = "menu_link", columnDefinition = "TEXT")
    private String menuLink;

    // --- JSONB fields using native Hibernate 7 approach ---

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "open_hours", columnDefinition = "jsonb")
    private Map<String, String> openHours;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "popular_times", columnDefinition = "jsonb")
    private Map<String, Object> popularTimes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "images", columnDefinition = "jsonb")
    private List<String> images;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "about", columnDefinition = "jsonb")
    private Map<String, Object> about;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "user_reviews", columnDefinition = "jsonb")
    private List<Map<String, Object>> userReviews;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "emails", columnDefinition = "jsonb")
    private List<String> emails;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_data", columnDefinition = "jsonb")
    private Map<String, Object> rawData;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // Add these fields to the existing Business entity
    @Column(name = "website_scope_score")
    private Integer websiteScopeScore;

    @Column(name = "business_tier")
    private String businessTier;

    @Column(name = "revenue_estimate")
    private String revenueEstimate;

    @Column(name = "is_targeted")
    private Boolean isTargeted;
}