package com.business.discovery.model;

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
@Table(name = "architect_brief")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArchitectBrief {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    // ─── Business analysis ────────────────────────────────
    // Add this field to ArchitectBrief.java
    @Column(name = "business_id")
    private UUID businessId;

    @Column(name = "business_category")
    private String businessCategory;

    @Column(name = "location")
    private String location;

    @Column(name = "business_count")
    private Integer businessCount;

    @Column(name = "average_rating")
    private Double averageRating;

    // % of scraped businesses that already have a website
    @Column(name = "website_adoption_rate")
    private Double websiteAdoptionRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "website_type")
    private WebsiteType websiteType;

    // ─── Architecture recommendations ─────────────────────
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recommended_pages", columnDefinition = "jsonb")
    private List<String> recommendedPages;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "must_have_features", columnDefinition = "jsonb")
    private List<String> mustHaveFeatures;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "nice_to_have_features", columnDefinition = "jsonb")
    private List<String> niceToHaveFeatures;

    // e.g. {"frontend": "React 19", "backend": "Spring Boot", "database": "PostgreSQL"}
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recommended_tech_stack", columnDefinition = "jsonb")
    private Map<String, String> recommendedTechStack;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "seo_keywords", columnDefinition = "jsonb")
    private List<String> seoKeywords;

    // ─── Design direction ─────────────────────────────────
    @Column(name = "design_direction", columnDefinition = "TEXT")
    private String designDirection;

    @Column(name = "color_scheme")
    private String colorScheme;

    @Column(name = "tone")
    private String tone;

    // ─── Research insights ────────────────────────────────
    @Column(name = "competitor_insights", columnDefinition = "TEXT")
    private String competitorInsights;

    @Column(name = "industry_insights", columnDefinition = "TEXT")
    private String industryInsights;

    @Column(name = "architectural_notes", columnDefinition = "TEXT")
    private String architecturalNotes;

    // ─── Scoring summary ──────────────────────────────────
    @Column(name = "tier1_count")
    private Integer tier1Count;

    @Column(name = "tier2_count")
    private Integer tier2Count;

    // Raw LLM output — kept for debugging and audit
    @Column(name = "raw_llm_output", columnDefinition = "TEXT")
    private String rawLlmOutput;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public enum WebsiteType {
        INFORMATIONAL,      // simple static site — hours, contact, about
        BOOKING,            // needs reservation or appointment system
        ECOMMERCE,          // needs product catalog and payments
        FULL_PLATFORM       // complex — ordering, booking, CRM, loyalty
    }
}