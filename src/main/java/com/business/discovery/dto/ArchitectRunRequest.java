package com.business.discovery.dto;

import java.util.List;

public record ArchitectRunRequest(

        String keyword,                 // e.g. "restaurants in Khadki, Pune"

        // ─── Scraper settings (all optional — sensible defaults applied) ───

        String lang,                    // default: "en"
        Integer depth,                  // default: 5 — how deep gosom scrapes
        Integer zoom,                   // default: 15 — Google Maps zoom level
        String lat,                     // optional — center latitude for search
        String lon,                     // optional — center longitude for search
        Boolean fastMode,               // default: false
        Integer radius,                 // default: 10000 — search radius in meters
        Boolean email,                  // default: false — extract emails from websites
        Integer maxTime,                // default: 0 — no time limit
        List<String> proxies            // default: null — no proxies
) {
    // Compact constructor — apply defaults for null values
    public ArchitectRunRequest {
        lang     = lang     != null ? lang     : "en";
        depth    = depth    != null ? depth    : 5;
        zoom     = zoom     != null ? zoom     : 15;
        fastMode = fastMode != null ? fastMode : false;
        radius   = radius   != null ? radius   : 10000;
        email    = email    != null ? email    : false;
        maxTime  = maxTime  != null ? maxTime  : 0;
    }
}