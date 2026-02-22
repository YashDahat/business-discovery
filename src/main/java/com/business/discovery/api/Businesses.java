package com.business.discovery.api;

import com.business.discovery.model.Business;
import com.business.discovery.services.googleMapsScrapper.GoogleMapsScraperService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/business")
public class Businesses {

    private final GoogleMapsScraperService googleMapScrapper;

    public Businesses() {
        this.googleMapScrapper = new GoogleMapsScraperService();
    }

    @GetMapping("/list-businesses")
    public List<Business> getBusinesses(@RequestParam String query, @RequestParam int size) {
        try {
            return googleMapScrapper.scrape(query, size);
        } catch (Exception e) {
            System.out.println("Exception we got:"+e.getMessage());
            return new ArrayList<>();
        }
    }
}

