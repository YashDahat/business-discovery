package com.business.discovery.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class GoogleMapsScraperConfig {

    @Value("${scraper.base-url}")
    private String scraperBaseUrl;

    @Bean
    public RestClient scraperRestClient() {
        return RestClient.builder()
                .baseUrl(scraperBaseUrl)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .build();
    }
}