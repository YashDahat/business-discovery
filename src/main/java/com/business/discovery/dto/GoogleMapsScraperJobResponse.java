package com.business.discovery.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GoogleMapsScraperJobResponse {

    @JsonProperty("id")
    private String id;

    @JsonProperty("Name")
    private String name;

    @JsonProperty("Status")
    private String status;

    @JsonProperty("Date")
    private String date;

    @JsonProperty("Data")
    private JobData data;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JobData {

        @JsonProperty("keywords")
        private java.util.List<String> keywords;

        @JsonProperty("lang")
        private String lang;

        @JsonProperty("zoom")
        private int zoom;

        @JsonProperty("lat")
        private String lat;

        @JsonProperty("lon")
        private String lon;

        @JsonProperty("fast_mode")
        private boolean fastMode;

        @JsonProperty("radius")
        private int radius;

        @JsonProperty("depth")
        private int depth;

        @JsonProperty("email")
        private boolean email;

        @JsonProperty("max_time")
        private long maxTime;
    }
}