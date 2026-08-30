package com.business.discovery.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.UUID;

/**
 * The scope of one authenticated MCP tool call, derived from the verified grant and stashed as a
 * request attribute by {@link McpAuthFilter}. {@code briefId} is authoritative — MCP endpoints must
 * scope to it and never trust a brief id supplied in tool arguments.
 */
public record McpCallContext(UUID briefId, List<String> tools, String jti) {

    public static final String ATTR = McpCallContext.class.getName();

    public static McpCallContext current(HttpServletRequest request) {
        Object v = request.getAttribute(ATTR);
        return v instanceof McpCallContext ctx ? ctx : null;
    }

    public boolean allowsTool(String tool) {
        return tools != null && tools.contains(tool);
    }
}
