package com.business.discovery.services.cline.mcp;

import com.business.discovery.model.PlatformUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpGrantServiceTest {

    private McpGrantService service(String secret, long ttlSeconds) {
        McpGrantService svc = new McpGrantService(new ObjectMapper());
        ReflectionTestUtils.setField(svc, "configuredSecret", secret);
        ReflectionTestUtils.setField(svc, "ttlSeconds", ttlSeconds);
        ReflectionTestUtils.invokeMethod(svc, "init");
        return svc;
    }

    private PlatformUser user() {
        return PlatformUser.builder()
                .id(UUID.randomUUID())
                .name("Op")
                .email("op@example.com")
                .role(PlatformUser.Role.OPERATOR)
                .build();
    }

    @Test
    void mintThenVerify_roundTrips() {
        McpGrantService svc = service("test-secret", 300);
        PlatformUser u = user();
        UUID briefId = UUID.randomUUID();

        String token = svc.mint(u, briefId, List.of("get_project_context"));
        GrantClaims claims = svc.verify(token);

        assertThat(claims.userId()).isEqualTo(u.getId());
        assertThat(claims.briefId()).isEqualTo(briefId);
        assertThat(claims.role()).isEqualTo("OPERATOR");
        assertThat(claims.tools()).containsExactly("get_project_context");
        assertThat(claims.allowsTool("get_project_context")).isTrue();
        assertThat(claims.allowsTool("update_architect_brief")).isFalse();
        assertThat(claims.expiresAt()).isGreaterThan(claims.issuedAt());
    }

    @Test
    void tamperedSignature_isRejected() {
        McpGrantService svc = service("test-secret", 300);
        String token = svc.mint(user(), UUID.randomUUID(), List.of("x"));
        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "AAAA";

        assertThatThrownBy(() -> svc.verify(tampered))
                .isInstanceOf(McpGrantService.InvalidGrantException.class);
    }

    @Test
    void tamperedPayload_isRejected() {
        McpGrantService svc = service("test-secret", 300);
        String token = svc.mint(user(), UUID.randomUUID(), List.of("x"));
        // Flip a character in the payload segment — signature no longer matches.
        char[] chars = token.toCharArray();
        int i = 3;
        chars[i] = chars[i] == 'a' ? 'b' : 'a';
        assertThatThrownBy(() -> svc.verify(new String(chars)))
                .isInstanceOf(McpGrantService.InvalidGrantException.class);
    }

    @Test
    void expiredGrant_isRejected() {
        McpGrantService svc = service("test-secret", -10); // exp already in the past
        String token = svc.mint(user(), UUID.randomUUID(), List.of("x"));

        assertThatThrownBy(() -> svc.verify(token))
                .isInstanceOf(McpGrantService.InvalidGrantException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void grantSignedByDifferentSecret_isRejected() {
        String token = service("secret-a", 300).mint(user(), UUID.randomUUID(), List.of("x"));
        McpGrantService other = service("secret-b", 300);

        assertThatThrownBy(() -> other.verify(token))
                .isInstanceOf(McpGrantService.InvalidGrantException.class);
    }

    @Test
    void malformedToken_isRejected() {
        McpGrantService svc = service("test-secret", 300);
        assertThatThrownBy(() -> svc.verify("not-a-token"))
                .isInstanceOf(McpGrantService.InvalidGrantException.class);
        assertThatThrownBy(() -> svc.verify(""))
                .isInstanceOf(McpGrantService.InvalidGrantException.class);
    }
}
