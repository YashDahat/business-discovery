package com.business.discovery.model;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Business {
    private String name;
    private String description;
    private String address;
    private String website;
    private boolean hasWebsite;

    private Double latitude;
    private Double longitude;
    private String location;

    private String rating;
    private String reviews;
}
