package com.business.discovery.api;

import com.business.discovery.model.ArchitectBrief;
import com.business.discovery.repository.ArchitectBriefRepository;
import com.business.discovery.security.McpCallContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MCP tool endpoint: apply a change to a project's ArchitectBrief (Phase B, first mutating tool).
 *
 * Security invariants (inherited from the MCP foundation):
 *  - The target brief comes from the GRANT ({@link McpCallContext#briefId()}), never from the tool
 *    arguments — a prompt-injected Cline cannot touch another project.
 *  - Operator-only (the acting user's real authorities are in the SecurityContext, set by McpAuthFilter).
 *  - The grant must list this tool.
 *  - Field-whitelisted (only the fields on {@link BriefUpdateRequest} can change) and persist-only —
 *    it does NOT trigger regeneration/deploy.
 */
@Slf4j
@RestController
@RequestMapping("/internal/mcp")
public class McpBriefController {

    private static final String TOOL_NAME = "update_architect_brief";

    private final ArchitectBriefRepository briefRepository;
    private final com.business.discovery.services.cline.ClineStepRecorder stepRecorder;

    public McpBriefController(ArchitectBriefRepository briefRepository,
                             com.business.discovery.services.cline.ClineStepRecorder stepRecorder) {
        this.briefRepository = briefRepository;
        this.stepRecorder = stepRecorder;
    }

    /** Whitelisted, all-optional fields Cline may change. Unknown fields are ignored by Jackson. */
    public record BriefUpdateRequest(
            String designDirection,
            String colorScheme,
            String tone,
            List<String> mustHaveFeatures,
            List<String> niceToHaveFeatures,
            List<String> recommendedPages,
            List<String> seoKeywords
    ) {}

    @PostMapping("/brief")
    @PreAuthorize("hasRole('OPERATOR')")
    @Transactional
    public ResponseEntity<Map<String, Object>> updateBrief(
            @RequestBody BriefUpdateRequest req,
            HttpServletRequest request) {

        McpCallContext ctx = McpCallContext.current(request);
        if (ctx == null || ctx.briefId() == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "No MCP scope");
        }
        if (!ctx.allowsTool(TOOL_NAME)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "Grant does not permit " + TOOL_NAME);
        }

        // Brief is taken from the grant — NOT from the request body.
        UUID briefId = ctx.briefId();
        return stepRecorder.track(briefId, TOOL_NAME, "Update project brief",
                () -> applyBriefUpdate(briefId, req));
    }

    private ResponseEntity<Map<String, Object>> applyBriefUpdate(UUID briefId, BriefUpdateRequest req) {
        ArchitectBrief brief = briefRepository.findById(briefId)
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Brief not found: " + briefId));

        List<String> changed = new ArrayList<>();
        if (req.designDirection() != null) { brief.setDesignDirection(req.designDirection()); changed.add("designDirection"); }
        if (req.colorScheme() != null)     { brief.setColorScheme(req.colorScheme());         changed.add("colorScheme"); }
        if (req.tone() != null)            { brief.setTone(req.tone());                        changed.add("tone"); }
        if (req.mustHaveFeatures() != null)    { brief.setMustHaveFeatures(req.mustHaveFeatures());     changed.add("mustHaveFeatures"); }
        if (req.niceToHaveFeatures() != null)  { brief.setNiceToHaveFeatures(req.niceToHaveFeatures()); changed.add("niceToHaveFeatures"); }
        if (req.recommendedPages() != null)    { brief.setRecommendedPages(req.recommendedPages());     changed.add("recommendedPages"); }
        if (req.seoKeywords() != null)         { brief.setSeoKeywords(req.seoKeywords());               changed.add("seoKeywords"); }

        if (changed.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "briefId", briefId.toString(),
                    "error", "no whitelisted fields provided",
                    "updatedFields", List.of()));
        }

        briefRepository.save(brief);
        log.info("[McpBrief] Brief {} updated via MCP — fields: {}", briefId, changed);

        return ResponseEntity.ok(Map.of(
                "briefId", briefId.toString(),
                "updatedFields", changed,
                "note", "Brief persisted. Regeneration/deploy is a separate, explicit step."));
    }
}
