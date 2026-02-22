package com.business.discovery.services.googleMapsScrapper;

import com.business.discovery.model.Business;
import com.microsoft.playwright.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

@Service
public class GoogleMapsScraperService {

    private final Browser mapBrowser;

    public GoogleMapsScraperService(){
        Playwright playwright = Playwright.create();
        mapBrowser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true).setArgs(Arrays.asList(
                "--disable-gpu",
                "--disable-dev-shm-usage",
                "--no-sandbox",
                "--disable-setuid-sandbox",
                "--disable-extensions",
                "--disable-background-networking",
                "--disable-background-timer-throttling",
                "--disable-renderer-backgrounding"
        )));
    }



    public List<Business> scrape(String query, int limit) throws ExecutionException, InterruptedException {

        List<Business> results = new ArrayList<>();

        Page page = mapBrowser.newPage();
        page.navigate("https://www.google.com/maps");

        // Handle consent if exists
        try {
            Locator accept = page.locator("button:has-text('Accept all')");
            if (accept.isVisible()) accept.click();
        } catch (Exception ignored) {}

        // Wait for search box
        page.waitForSelector("input[name='q']");

        // Fill query
        page.fill("input[name='q']", query);
        page.keyboard().press("Enter");

        // Wait for initial results
        page.waitForSelector("div[role='article']");

        Locator feed = page.locator("div[role='feed']");
        Locator businessCards = feed.locator("div[role='article']");

        // ✅ -------- SCROLL TO LOAD MORE RESULTS --------
        int loaded = businessCards.count();

        while (loaded < limit) {
            feed.evaluate("el => el.scrollBy(0, el.scrollHeight)");
            page.waitForTimeout(1500);

            int newCount = businessCards.count();
            if (newCount == loaded) {
                // No more results available
                break;
            }
            loaded = newCount;
        }
        // ✅ --------------------------------------------

        int count = Math.min(limit, businessCards.count());


        for (int i = 0; i < count; i++) {

            Locator card = businessCards.nth(i);

            // ----- Sidebar Data Extraction -----
            String name = card.locator(".qBF1Pd").textContent();

            String rating = card.locator(".MW4etd").count() > 0
                    ? card.locator(".MW4etd").textContent()
                    : null;

            String reviewCount = card.locator(".UY7F9").count() > 0
                    ? card.locator(".UY7F9").textContent()
                    : null;

            String phone = card.locator(".UsdlK").count() > 0
                    ? card.locator(".UsdlK").textContent()
                    : null;

            String website = card.locator("a[data-value='Website']").count() > 0
                    ? card.locator("a[data-value='Website']").getAttribute("href")
                    : null;

            // ----- Open Detail Panel -----
            card.locator("a.hfpxzc").click();
            page.waitForSelector("h1");

            String address = null;
            if (page.locator("button[data-item-id='address']").count() > 0) {
                address = page.locator("button[data-item-id='address']")
                        .first()
                        .textContent()
                        .replace("Address:", "")
                        .trim();
            }

            String url = page.url();
            double[] latLng = extractLatLng(url);

            results.add(new Business(
                    name,
                    null,
                    phone,
                    website,
                    website != null,
                    latLng != null ? latLng[0] : null,
                    latLng != null ? latLng[1] : null,
                    address != null ? address : query,
                    rating,
                    reviewCount
            ));

            // Close detail panel
            page.keyboard().press("Escape");
            page.waitForSelector("div[role='article']");
        }


        return results;
    }


    private double[] extractLatLng(String url) {
        try {
            int atIndex = url.indexOf("@");
            if (atIndex == -1) return null;

            String coords = url.substring(atIndex + 1);
            String[] parts = coords.split(",");

            return new double[]{
                    Double.parseDouble(parts[0]),
                    Double.parseDouble(parts[1])
            };
        } catch (Exception e) {
            return null;
        }
    }
}
