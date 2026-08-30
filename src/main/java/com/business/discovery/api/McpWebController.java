package com.business.discovery.api;

import com.business.discovery.security.McpCallContext;
import com.business.discovery.services.research.TavilyClient;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * MCP tool endpoints that give Cline Tavily's web capabilities — search, extract, crawl, map —
 * backed by {@link TavilyClient}. Each maps 1:1 to a tool registered in the sidecar's MCP server
 * (projectContextServer.ts) and returns Tavily's raw JSON so Cline sees the full structured result.
 *
 * Security invariants (inherited from the MCP foundation, same as {@link McpBriefController}):
 *  - {@link com.business.discovery.security.McpAuthFilter} has already validated the internal token
 *    and the signed grant, and set the acting user in the SecurityContext.
 *  - The grant must list the specific tool being called (checked per method).
 *
 * These are read-only web reads (they do not touch project data), so unlike update_architect_brief
 * they are not OPERATOR-gated beyond the grant scope; the Tavily API key never leaves Spring Boot.
 */
@Slf4j
@RestController
@RequestMapping("/internal/mcp/web")
public class McpWebController {

    private final TavilyClient tavilyClient;
    private final com.business.discovery.services.cline.ClineStepRecorder stepRecorder;

    public McpWebController(TavilyClient tavilyClient,
                           com.business.discovery.services.cline.ClineStepRecorder stepRecorder) {
        this.tavilyClient = tavilyClient;
        this.stepRecorder = stepRecorder;
    }

    public record SearchRequest(
            String query,
            String searchDepth,
            String topic,
            Integer maxResults,
            Boolean includeAnswer,
            Boolean includeRawContent,
            List<String> includeDomains,
            List<String> excludeDomains
    ) {}

    public record ExtractRequest(
            List<String> urls,
            String extractDepth,
            Boolean includeImages
    ) {}

    public record CrawlRequest(
            String url,
            Integer maxDepth,
            Integer limit,
            String instructions,
            String extractDepth
    ) {}

    public record MapRequest(
            String url,
            Integer maxDepth,
            Integer limit,
            String instructions
    ) {}

    @PostMapping("/search")
    public ResponseEntity<String> search(@RequestBody SearchRequest req, HttpServletRequest request) {
        McpCallContext ctx = requireTool("web_search", request);
        if (req.query() == null || req.query().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query is required");
        }
        return stepRecorder.track(ctx.briefId(), "web_search", "Search: " + req.query(),
                () -> json(tavilyClient.search(req.query(), req.searchDepth(), req.topic(), req.maxResults(),
                        req.includeAnswer(), req.includeRawContent(), req.includeDomains(), req.excludeDomains())));
    }

    @PostMapping("/extract")
    public ResponseEntity<String> extract(@RequestBody ExtractRequest req, HttpServletRequest request) {
        McpCallContext ctx = requireTool("web_extract", request);
        if (req.urls() == null || req.urls().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "urls is required");
        }
        return stepRecorder.track(ctx.briefId(), "web_extract",
                "Extract " + req.urls().size() + " page(s)",
                () -> json(tavilyClient.extract(req.urls(), req.extractDepth(), req.includeImages())));
    }

    @PostMapping("/crawl")
    public ResponseEntity<String> crawl(@RequestBody CrawlRequest req, HttpServletRequest request) {
        McpCallContext ctx = requireTool("web_crawl", request);
        if (req.url() == null || req.url().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "url is required");
        }
        return stepRecorder.track(ctx.briefId(), "web_crawl", "Crawl " + req.url(),
                () -> json(tavilyClient.crawl(req.url(), req.maxDepth(), req.limit(), req.instructions(),
                        req.extractDepth())));
    }

    @PostMapping("/map")
    public ResponseEntity<String> map(@RequestBody MapRequest req, HttpServletRequest request) {
        McpCallContext ctx = requireTool("web_map", request);
        if (req.url() == null || req.url().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "url is required");
        }
        return stepRecorder.track(ctx.briefId(), "web_map", "Map " + req.url(),
                () -> json(tavilyClient.map(req.url(), req.maxDepth(), req.limit(), req.instructions())));
    }

    /** Enforce that the session grant actually permits this tool; returns the call context (for the briefId). */
    private McpCallContext requireTool(String tool, HttpServletRequest request) {
        McpCallContext ctx = McpCallContext.current(request);
        if (ctx == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No MCP scope");
        }
        if (!ctx.allowsTool(tool)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Grant does not permit " + tool);
        }
        return ctx;
    }

    private static ResponseEntity<String> json(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
