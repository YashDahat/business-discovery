package com.business.discovery.services.business;

import com.business.discovery.configuration.AgentScoringProperties;
import com.business.discovery.model.BusinessEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessScoringService {

    private final AgentScoringProperties scoringProperties;

    public List<BusinessEntity> scoreAndFilter(List<BusinessEntity> businesses) {
        log.info("Scoring {} businesses", businesses.size());

        List<BusinessEntity> scored = businesses.stream()
                .map(this::score)
                .filter(b -> !b.getBusinessTier().equals("EXCLUDED"))
                .collect(Collectors.toList());

        log.info("Scoring complete — {}/{} businesses passed filter",
                scored.size(), businesses.size());

        return scored;
    }

    private BusinessEntity score(BusinessEntity business) {
        int scopeScore = computeScopeScore(business);
        String tier = computeTier(business);
        String revenueRange = estimateRevenueRange(business);

        business.setWebsiteScopeScore(scopeScore);
        business.setBusinessTier(tier);
        business.setRevenueEstimate(revenueRange);
        business.setIsTargeted(!tier.equals("EXCLUDED"));

        log.debug("Business: {} | Tier: {} | Scope: {} | Revenue: {}",
                business.getTitle(), tier, scopeScore, revenueRange);

        return business;
    }

    private String computeTier(BusinessEntity business) {
        double rating = business.getRating() != null ? business.getRating() : 0.0;
        int reviewCount = business.getReviewCount() != null ? business.getReviewCount() : 0;
        boolean hasWebsite = business.getWebsite() != null
                && !business.getWebsite().isBlank();

        // Exclude — not your target customer
        if (reviewCount < 100 || rating < 3.5) {
            return "EXCLUDED";
        }

        // Exclude — already has a website (Phase 3 will check quality via Tavily)
        // For now exclude entirely — revisit in Phase 3
        if (hasWebsite) {
            return "HAS_WEBSITE";
        }

        // Tier 1 — ₹1Cr+ likely, no website
        if (reviewCount >= scoringProperties.getTier1ReviewCount()
                && rating >= scoringProperties.getTier1MinRating()) {
            return "TIER_1";
        }

        // Tier 2 — potential, no website
        if (reviewCount >= scoringProperties.getTier2ReviewCount()
                && rating >= scoringProperties.getTier2MinRating()) {
            return "TIER_2";
        }

        // Tier 3 — watch list
        if (reviewCount >= 100 && rating >= 4.2) {
            return "TIER_3";
        }

        return "EXCLUDED";
    }

    private int computeScopeScore(BusinessEntity business) {
        int score = 0;

        if (business.getReservationLink() != null) score++;
        if (business.getOrderOnlineLink() != null) score++;
        if (business.getMenuLink() != null) score++;
        if (business.getReviewCount() != null
                && business.getReviewCount() > 500) score++;
        if (isPremiumPriceRange(business.getPriceRange())) score++;
        if (hasFullHours(business)) score++;

        return score;
    }

    private String estimateRevenueRange(BusinessEntity business) {
        int reviewCount = business.getReviewCount() != null
                ? business.getReviewCount() : 0;
        double rating = business.getRating() != null
                ? business.getRating() : 0.0;
        String category = business.getCategory() != null
                ? business.getCategory().toLowerCase() : "";
        String priceRange = business.getPriceRange() != null
                ? business.getPriceRange() : "";

        if (reviewCount == 0) return "Insufficient data";

        // ── Step 1: Category-specific average transaction value ──
        // Based on typical Indian local business transaction sizes
        int avgTransactionMin = getAvgTransactionMin(category, priceRange);
        int avgTransactionMax = getAvgTransactionMax(category, priceRange);

        // ── Step 2: Estimate annual customers from review count ──────
        double reviewRate = getReviewRate(category);

        // Lower bound: 2× the standard review rate (fewer customers)
        // Upper bound: 0.5× the standard review rate (more customers)
        long conservativeCustomers = (long) (reviewCount / (reviewRate * 2));
        long aggressiveCustomers   = (long) (reviewCount / (reviewRate * 0.5));

        // ── Step 3: Apply rating quality multiplier ──────────────
        // Higher rating → higher repeat visit rate → higher revenue per customer
        double ratingMultiplier = getRatingMultiplier(rating);

        // ── Step 4: Calculate revenue range ─────────────────────
        long minRevenue = (long) (conservativeCustomers * avgTransactionMin * ratingMultiplier);
        long maxRevenue = (long) (aggressiveCustomers   * avgTransactionMax * ratingMultiplier);

        // ── Step 5: Format output ────────────────────────────────
        return formatRevenueRange(minRevenue, maxRevenue);
    }

// ── Category-specific transaction values (INR) ──────────────

    private int getAvgTransactionMin(String category, String priceRange) {
        if (matches(category, "restaurant", "cafe", "dhaba", "food", "bakery", "pizza")) {
            return switch (priceRange) {
                case "₹₹₹₹" -> 1200;
                case "₹₹₹"  -> 600;
                case "₹₹"   -> 300;
                default      -> 150;
            };
        }
        if (matches(category, "gym", "fitness", "yoga", "crossfit", "pilates")) {
            return 800;   // monthly membership ÷ ~12 visits → per-visit equivalent
        }
        if (matches(category, "stationar", "book store", "art supply", "print")) {
            return 200;   // typical stationery purchase
        }
        if (matches(category, "salon", "spa", "beauty", "parlour", "barber")) {
            return 400;
        }
        if (matches(category, "clinic", "doctor", "dentist", "hospital", "medical")) {
            return 500;
        }
        if (matches(category, "pharmacy", "chemist", "drug")) {
            return 250;
        }
        if (matches(category, "grocery", "supermarket", "mart")) {
            return 400;
        }
        if (matches(category, "clothing", "fashion", "garment", "textile")) {
            return switch (priceRange) {
                case "₹₹₹₹" -> 3000;
                case "₹₹₹"  -> 1500;
                case "₹₹"   -> 600;
                default      -> 300;
            };
        }
        if (matches(category, "jewellery", "jewelry", "gold")) {
            return 5000;
        }
        if (matches(category, "electronics", "mobile", "laptop", "computer")) {
            return 2000;
        }
        if (matches(category, "hardware", "tools", "plumbing")) {
            return 500;
        }
        if (matches(category, "coaching", "tuition", "institute", "academy")) {
            return 1500;  // monthly fee ÷ visits
        }
        if (matches(category, "real estate", "realty", "property", "builder",
                "developer", "housing", "apartments", "flats", "plots",
                "broker", "estate agent")) {

            // Real estate transactions are per deal — not per visit
            // A broker earns 1-2% commission on property value
            // Pune residential property: ₹50L-2Cr range
            // Commission per deal: ₹50,000 - ₹4,00,000
            // A busy broker closes 1-3 deals per month
            return switch (priceRange) {
                case "₹₹₹₹" -> 300000;  // luxury — high-value deals, 2Cr+ properties
                case "₹₹₹"  -> 150000;  // premium — 1Cr+ properties
                case "₹₹"   -> 75000;   // mid-segment — 50L-1Cr properties
                default      -> 40000;   // affordable segment
            };
        }
        // Default — unknown category
        return switch (priceRange) {
            case "₹₹₹₹" -> 2000;
            case "₹₹₹"  -> 800;
            case "₹₹"   -> 400;
            default      -> 200;
        };
    }

    private int getAvgTransactionMax(String category, String priceRange) {
        // Max is typically 2-3x the min for the same category
        return (int) (getAvgTransactionMin(category, priceRange) * 2.5);
    }

// ── Rating multiplier ────────────────────────────────────────
// High rating → more repeat customers → higher effective revenue

    private double getRatingMultiplier(double rating) {
        if (rating >= 4.5) return 1.3;   // exceptional — high loyalty, repeat visits
        if (rating >= 4.0) return 1.1;   // good — solid repeat base
        if (rating >= 3.5) return 1.0;   // average — baseline
        if (rating >= 3.0) return 0.85;  // below average — higher churn
        return 0.7;                        // poor — mostly one-time visitors
    }

// ── Category keyword matcher ─────────────────────────────────

    private boolean matches(String category, String... keywords) {
        for (String kw : keywords) {
            if (category.contains(kw)) return true;
        }
        return false;
    }

// ── Format revenue range ─────────────────────────────────────

    private String formatRevenueRange(long min, long max) {
        // Convert to Lakhs or Crores depending on magnitude
        if (max >= 10_000_000) {  // 1 Crore+
            return "₹%.1fCr — ₹%.1fCr".formatted(
                    min / 10_000_000.0,
                    max / 10_000_000.0
            );
        }
        if (max >= 100_000) {  // 1 Lakh+
            return "₹%dL — ₹%dL".formatted(
                    min / 100_000,
                    max / 100_000
            );
        }
        return "₹%d — ₹%d".formatted(min, max);
    }

    // ── Step 2: Category-specific review rate ────────────────────

    private double getReviewRate(String category) {
        // Real estate — clients almost always leave a review (high stakes purchase)
        if (matches(category, "real estate", "realty", "property",
                "builder", "developer", "broker", "estate agent")) {
            return 0.25;  // 25% review rate — most clients review after a deal
        }
        // Clinics/doctors — patients frequently review
        if (matches(category, "clinic", "doctor", "dentist",
                "hospital", "medical")) {
            return 0.15;
        }
        // Coaching/education — students often review
        if (matches(category, "coaching", "tuition", "institute", "academy")) {
            return 0.10;
        }
        // Standard retail and services
        return 0.02;  // 2% default — midpoint of 1-3% range
    }

    private boolean isPremiumPriceRange(String priceRange) {
        return priceRange != null &&
                (priceRange.equals("₹₹₹") || priceRange.equals("₹₹₹₹"));
    }

    private boolean hasFullHours(BusinessEntity business) {
        return business.getOpenHours() != null
                && !business.getOpenHours().isEmpty();
    }

    // Summary stats used by synthesizeBriefNode
    public Map<String, Object> computeSummaryStats(List<BusinessEntity> businesses) {
        long tier1 = businesses.stream()
                .filter(b -> "TIER_1".equals(b.getBusinessTier())).count();
        long tier2 = businesses.stream()
                .filter(b -> "TIER_2".equals(b.getBusinessTier())).count();
        long hasWebsite = businesses.stream()
                .filter(b -> "HAS_WEBSITE".equals(b.getBusinessTier())).count();

        double avgRating = businesses.stream()
                .filter(b -> b.getRating() != null)
                .mapToDouble(BusinessEntity::getRating)
                .average()
                .orElse(0.0);

        double websiteAdoptionRate = businesses.isEmpty() ? 0 :
                (double) hasWebsite / businesses.size() * 100;

        return Map.of(
                "total", businesses.size(),
                "tier1", tier1,
                "tier2", tier2,
                "has_website", hasWebsite,
                "avg_rating", Math.round(avgRating * 10.0) / 10.0,
                "website_adoption_rate", Math.round(websiteAdoptionRate * 10.0) / 10.0
        );
    }
}