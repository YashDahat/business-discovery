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
        String priceRange = business.getPriceRange();

        // Rough multiplier based on price range
        int avgOrder = switch (priceRange != null ? priceRange : "") {
            case "₹₹₹₹" -> 1500;
            case "₹₹₹"  -> 800;
            case "₹₹"   -> 400;
            default      -> 200;
        };

        // Assume 2% review rate, 30 days/month, 12 months
        long estimatedCoversPerYear = (long) reviewCount * 50;
        long minRevenue = estimatedCoversPerYear * avgOrder;
        long maxRevenue = minRevenue * 3;

        return "₹%sL — ₹%sL".formatted(
                minRevenue / 100000, maxRevenue / 100000
        );
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