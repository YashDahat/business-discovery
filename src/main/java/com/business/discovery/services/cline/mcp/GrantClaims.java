package com.business.discovery.services.cline.mcp;

import java.util.List;
import java.util.UUID;

/**
 * Decoded, verified claims from an MCP grant token (see {@link McpGrantService}).
 *
 * The grant binds one Cline session to a single acting user and a single project (brief), for a
 * short window. {@code briefId} is authoritative — MCP tools must scope to it and never trust a
 * brief id supplied in the LLM's tool arguments.
 */
public record GrantClaims(
        UUID userId,
        String role,
        UUID briefId,
        List<String> tools,
        long issuedAt,
        long expiresAt,
        String jti
) {
    public boolean allowsTool(String tool) {
        return tools != null && tools.contains(tool);
    }
}
