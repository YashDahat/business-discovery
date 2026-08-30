package com.business.discovery.services.cline.mcp;

import com.business.discovery.model.PlatformUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Mints and verifies short-lived, scoped MCP grant tokens — Layer 2 of the MCP auth model.
 *
 * A grant is a compact HMAC-SHA256 token ({@code base64url(payload) + "." + base64url(sig)}, JWT-shaped
 * but dependency-free) that binds one Cline session to a single acting user and a single project (brief)
 * for a short TTL. It is minted by Spring Boot when a Cline session is established (the app already knows
 * the authenticated user there), threaded through the sidecar, and presented on every MCP tool call — where
 * {@link McpGrantService#verify} re-establishes the user + scope server-side.
 */
@Slf4j
@Service
public class McpGrantService {

    private static final String HMAC_ALG = "HmacSHA256";

    private final ObjectMapper objectMapper;

    @Value("${cline.mcp.grant-secret:}")
    private String configuredSecret;

    @Value("${cline.mcp.grant-ttl-seconds:300}")
    private long ttlSeconds;

    private byte[] secretKey;

    public McpGrantService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        if (StringUtils.hasText(configuredSecret)) {
            secretKey = configuredSecret.getBytes(StandardCharsets.UTF_8);
        } else {
            // Dev fallback: sign with a random per-process key so grants are always signed. Tokens won't
            // survive a restart — set CLINE_MCP_GRANT_SECRET in any real deployment.
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            secretKey = random;
            log.warn("[McpGrant] cline.mcp.grant-secret not set — using an ephemeral random key "
                    + "(grants invalidate on restart). Set CLINE_MCP_GRANT_SECRET for stable signing.");
        }
    }

    /** Raised when a grant is missing, malformed, tampered, or expired. */
    public static class InvalidGrantException extends RuntimeException {
        public InvalidGrantException(String message) { super(message); }
    }

    public String mint(PlatformUser user, UUID briefId, List<String> tools) {
        long now = Instant.now().getEpochSecond();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("sub", user.getId().toString());
        payload.put("role", user.getRole().name());
        payload.put("brief", briefId.toString());
        var toolsArr = payload.putArray("tools");
        if (tools != null) tools.forEach(toolsArr::add);
        payload.put("iat", now);
        payload.put("exp", now + ttlSeconds);
        payload.put("jti", UUID.randomUUID().toString());

        String encodedPayload;
        try {
            encodedPayload = b64(objectMapper.writeValueAsBytes(payload));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize MCP grant payload", e);
        }
        String sig = b64(hmac(encodedPayload.getBytes(StandardCharsets.UTF_8)));
        return encodedPayload + "." + sig;
    }

    public GrantClaims verify(String token) {
        if (!StringUtils.hasText(token)) {
            throw new InvalidGrantException("Missing grant");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) {
            throw new InvalidGrantException("Malformed grant");
        }
        byte[] expectedSig = hmac(parts[0].getBytes(StandardCharsets.UTF_8));
        byte[] presentedSig;
        try {
            presentedSig = unb64(parts[1]);
        } catch (Exception e) {
            throw new InvalidGrantException("Malformed grant signature");
        }
        if (!MessageDigest.isEqual(expectedSig, presentedSig)) {
            throw new InvalidGrantException("Bad grant signature");
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(unb64(parts[0]));
        } catch (Exception e) {
            throw new InvalidGrantException("Unreadable grant payload");
        }

        long exp = payload.path("exp").asLong(0);
        if (exp <= Instant.now().getEpochSecond()) {
            throw new InvalidGrantException("Grant expired");
        }

        List<String> tools = new ArrayList<>();
        payload.path("tools").forEach(n -> tools.add(n.asText()));

        try {
            return new GrantClaims(
                    UUID.fromString(payload.path("sub").asText()),
                    payload.path("role").asText(),
                    UUID.fromString(payload.path("brief").asText()),
                    tools,
                    payload.path("iat").asLong(0),
                    exp,
                    payload.path("jti").asText());
        } catch (IllegalArgumentException e) {
            throw new InvalidGrantException("Invalid grant claims");
        }
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALG));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }

    private static String b64(byte[] data) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static byte[] unb64(String s) {
        return java.util.Base64.getUrlDecoder().decode(s);
    }
}
