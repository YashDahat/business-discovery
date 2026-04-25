package com.business.discovery.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "agent.scoring")
public class AgentScoringProperties {

    private int minReviewCount = 200;
    private double minRating = 4.0;
    private int tier1ReviewCount = 500;
    private double tier1MinRating = 4.0;
    private int tier2ReviewCount = 200;
    private double tier2MinRating = 3.8;
}