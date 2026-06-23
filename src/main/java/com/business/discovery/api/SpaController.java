package com.business.discovery.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {

    // Forward all React Router paths to index.html so deep links work after production build.
    // The regex [^\\.] matches segments without dots — excludes /assets/index.js, /favicon.ico etc.
    // Those are served by Spring Boot's default static resource handler.
    // Single-segment paths: /agents, /runs, /businesses, /pipeline, /kpi, /access
    // Two-segment drill-down paths: /runs/:id, /businesses/:id
    // Segments with dots (e.g. /assets/index.js) are intentionally excluded — handled by static resource handler.
    @GetMapping(value = {
        "/",
        "/{path:[^\\.]*}",
        "/runs/{id:[^\\.]*}",
        "/businesses/{id:[^\\.]*}",
    })
    public String forward() {
        return "forward:/index.html";
    }
}
