package com.business.discovery.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class GoogleMapsScraperJobRequest {

    @JsonProperty("name")
    private String name;

    @JsonProperty("keywords")
    private List<String> keywords;

    @JsonProperty("lang")
    @Builder.Default
    private String lang = "en";

    @JsonProperty("depth")
    @Builder.Default
    private int depth = 5;

    @JsonProperty("email")
    @Builder.Default
    private boolean email = false;

    @JsonProperty("zoom")
    @Builder.Default
    private int zoom = 15;

    @JsonProperty("lat")
    private String lat;

    @JsonProperty("lon")
    private String lon;

    @JsonProperty("fast_mode")
    @Builder.Default
    private boolean fastMode = false;

    @JsonProperty("radius")
    @Builder.Default
    private int radius = 10000;

    @JsonProperty("max_time")
    private int maxTime;

    @JsonProperty("proxies")
    private List<String> proxies;
}