package com.business.discovery.agents.architect;

import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class ArchitectAgentState extends AgentState {

    // ─── State key constants ──────────────────────────────
    public static final String RUN_ID              = "run_id";
    public static final String KEYWORD             = "keyword";
    public static final String CATEGORY            = "category";
    public static final String LOCATION            = "location";
    public static final String SCRAPED_COUNT       = "scraped_count";
    public static final String FILTERED_COUNT      = "filtered_count";
    public static final String SCORING_STATS       = "scoring_stats";
    public static final String INDUSTRY_RESEARCH   = "industry_research";
    public static final String COMPETITOR_RESEARCH = "competitor_research";
    public static final String SEO_RESEARCH        = "seo_research";
    public static final String TECH_STACK_RESEARCH = "tech_stack_research";
    public static final String ERROR               = "error";
    public static final String SCRAPER_CONFIG      = "scraper_config";

    // Replace
    public static final String ARCHITECT_BRIEF_ID  = "architect_brief_id";

    // With
    public static final String ARCHITECT_BRIEF_IDS = "architect_brief_ids";
    public static final String BRIEFS_COUNT        = "briefs_count";

    // Replace accessor
    public Optional<List<UUID>> architectBriefIds() {
        return value(ARCHITECT_BRIEF_IDS);
    }

    public Optional<Integer> briefsCount() {
        return value(BRIEFS_COUNT);
    }

    public ArchitectAgentState(Map<String, Object> initData) {
        super(initData);
    }

    // ─── Typed accessors ──────────────────────────────────

    public Optional<String> runId() {
        return value(RUN_ID);
    }

    public Optional<String> keyword() {
        return value(KEYWORD);
    }

    public Optional<String> category() {
        return value(CATEGORY);
    }

    public Optional<String> location() {
        return value(LOCATION);
    }

    public Optional<Integer> scrapedCount() {
        return value(SCRAPED_COUNT);
    }

    public Optional<Integer> filteredCount() {
        return value(FILTERED_COUNT);
    }

    public Optional<Map<String, Object>> scoringStats() {
        return value(SCORING_STATS);
    }

    public Optional<String> industryResearch() {
        return value(INDUSTRY_RESEARCH);
    }

    public Optional<String> competitorResearch() {
        return value(COMPETITOR_RESEARCH);
    }

    public Optional<String> seoResearch() {
        return value(SEO_RESEARCH);
    }

    public Optional<String> techStackResearch() {
        return value(TECH_STACK_RESEARCH);
    }

    public Optional<String> architectBriefId() {
        return value(ARCHITECT_BRIEF_ID);
    }

    public Optional<String> error() {
        return value(ERROR);
    }

    public Optional<Map<String, Object>> scraperConfig() {
        return value(SCRAPER_CONFIG);
    }
}