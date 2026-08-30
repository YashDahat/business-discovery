package com.business.discovery.api;

import com.business.discovery.security.McpCallContext;
import com.business.discovery.services.user.PlatformUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Read-only MCP endpoint used to prove the two-layer auth chain end to end before any mutating tool
 * exists: it simply echoes the authenticated acting user + the grant-scoped project. Reaching it at all
 * means the internal token AND the signed grant both validated in {@link com.business.discovery.security.McpAuthFilter}.
 */
@Slf4j
@RestController
@RequestMapping("/internal/mcp")
public class McpContextController {

    private final com.business.discovery.services.cline.ClineStepRecorder stepRecorder;

    public McpContextController(com.business.discovery.services.cline.ClineStepRecorder stepRecorder) {
        this.stepRecorder = stepRecorder;
    }

    @GetMapping("/context")
    public ResponseEntity<Map<String, Object>> context(
            @AuthenticationPrincipal PlatformUserDetails principal,
            HttpServletRequest request) {

        McpCallContext ctx = McpCallContext.current(request);

        return stepRecorder.track(ctx != null ? ctx.briefId() : null,
                "get_project_context", "Read project context", () -> {
            Map<String, Object> body = new HashMap<>();
            body.put("userName", principal != null ? principal.getName() : null);
            body.put("role", principal != null ? principal.getRole().name() : null);
            body.put("briefId", ctx != null ? ctx.briefId() : null);
            body.put("tools", ctx != null ? ctx.tools() : null);

            log.info("[McpContext] resolved user={} brief={}",
                    body.get("userName"), body.get("briefId"));
            return ResponseEntity.ok(body);
        });
    }
}
