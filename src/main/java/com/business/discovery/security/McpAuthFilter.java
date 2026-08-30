package com.business.discovery.security;

import com.business.discovery.model.PlatformUser;
import com.business.discovery.repository.PlatformUserRepository;
import com.business.discovery.services.cline.mcp.GrantClaims;
import com.business.discovery.services.cline.mcp.McpGrantService;
import com.business.discovery.services.user.PlatformUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Two-layer auth for the {@code /internal/mcp/**} endpoints the Cline sidecar calls:
 *
 *   Layer 1 — a shared internal token (X-Internal-Token) proves the caller is our sidecar.
 *   Layer 2 — a signed grant (X-Mcp-Grant) proves which real user + project the call acts for.
 *
 * On success it reconstructs the acting user as a {@link PlatformUserDetails} and sets it in the
 * SecurityContext, so the existing role model + {@code AccessScope} apply unchanged; the scoped
 * briefId + tools are stashed in a {@link McpCallContext} request attribute.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpAuthFilter extends OncePerRequestFilter {

    private final McpGrantService grantService;
    private final PlatformUserRepository userRepository;

    @Value("${cline.proxy.internal-token:}")
    private String internalToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Layer 1 — service authentication.
        if (StringUtils.hasText(internalToken)
                && !internalToken.equals(request.getHeader("X-Internal-Token"))) {
            unauthorized(response, "invalid internal token");
            return;
        }

        // Layer 2 — scoped user grant.
        GrantClaims claims;
        try {
            claims = grantService.verify(request.getHeader("X-Mcp-Grant"));
        } catch (McpGrantService.InvalidGrantException e) {
            unauthorized(response, "invalid grant: " + e.getMessage());
            return;
        }

        PlatformUser user = userRepository.findById(claims.userId()).orElse(null);
        if (user == null || user.getStatus() != PlatformUser.UserStatus.ACTIVE) {
            unauthorized(response, "user not found or inactive");
            return;
        }

        PlatformUserDetails principal = new PlatformUserDetails(user);
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        request.setAttribute(McpCallContext.ATTR,
                new McpCallContext(claims.briefId(), claims.tools(), claims.jti()));

        chain.doFilter(request, response);
    }

    private void unauthorized(HttpServletResponse response, String reason) throws IOException {
        log.warn("[McpAuth] Rejected MCP call — {}", reason);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"unauthorized\"}");
        response.getWriter().flush();
    }
}
