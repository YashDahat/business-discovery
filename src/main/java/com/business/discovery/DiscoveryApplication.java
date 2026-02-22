package com.business.discovery;

import com.business.discovery.services.googleMapsScrapper.GoogleMapsScraperService;
import com.business.discovery.model.Business;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.List;

@SpringBootApplication
public class DiscoveryApplication {

	public static void main(String[] args) {
		SpringApplication.run(DiscoveryApplication.class, args);
	}

}
