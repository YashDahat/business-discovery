package com.business.discovery.api;

import com.business.discovery.model.DemoInstance;
import com.business.discovery.services.demo.DemoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 4 — client demos from smoke-tested GHCR images.
 *
 * POST   /api/v4/demo/{briefId}   start (202; poll GET for status)
 * GET    /api/v4/demo/{briefId}   latest instance for the brief (UI polling target)
 * DELETE /api/v4/demo/{briefId}   stop the active demo
 * GET    /api/v4/demo             list active demos
 */
@RestController
@RequestMapping("/api/v4/demo")
@RequiredArgsConstructor
public class DemoController {

    private final DemoService demoService;

    @PostMapping("/{briefId}")
    public ResponseEntity<?> start(@PathVariable UUID briefId) {
        try {
            DemoInstance demo = demoService.start(briefId);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(demo);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{briefId}")
    public ResponseEntity<?> get(@PathVariable UUID briefId) {
        try {
            return ResponseEntity.ok(demoService.getForBrief(briefId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{briefId}")
    public ResponseEntity<?> stop(@PathVariable UUID briefId) {
        try {
            return ResponseEntity.ok(demoService.stopForBrief(briefId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping
    public List<DemoInstance> listActive() {
        return demoService.listActive();
    }
}
