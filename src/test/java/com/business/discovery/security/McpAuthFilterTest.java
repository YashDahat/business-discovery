package com.business.discovery.security;

import com.business.discovery.model.PlatformUser;
import com.business.discovery.repository.PlatformUserRepository;
import com.business.discovery.services.cline.mcp.McpGrantService;
import com.business.discovery.services.user.PlatformUserDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Direct test of the two-layer MCP auth filter — no DB/Spring context. Proves service-token + grant
 * validation, that a valid pair establishes the acting user + scoped brief, and that any failure is a
 * 401 that stops the chain.
 */
class McpAuthFilterTest {

    private static final String INTERNAL_TOKEN = "svc-token";
    private static final String SECRET = "grant-secret";

    private McpGrantService grantService;
    private PlatformUserRepository userRepository;
    private McpAuthFilter filter;

    @BeforeEach
    void setUp() {
        grantService = new McpGrantService(new ObjectMapper());
        ReflectionTestUtils.setField(grantService, "configuredSecret", SECRET);
        ReflectionTestUtils.setField(grantService, "ttlSeconds", 300L);
        ReflectionTestUtils.invokeMethod(grantService, "init");

        userRepository = mock(PlatformUserRepository.class);
        filter = new McpAuthFilter(grantService, userRepository);
        ReflectionTestUtils.setField(filter, "internalToken", INTERNAL_TOKEN);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private PlatformUser activeUser(UUID id) {
        return PlatformUser.builder()
                .id(id).name("Op").email("op@x.com")
                .role(PlatformUser.Role.OPERATOR)
                .status(PlatformUser.UserStatus.ACTIVE)
                .build();
    }

    private McpGrantService freshTtl(long ttl) {
        McpGrantService s = new McpGrantService(new ObjectMapper());
        ReflectionTestUtils.setField(s, "configuredSecret", SECRET);
        ReflectionTestUtils.setField(s, "ttlSeconds", ttl);
        ReflectionTestUtils.invokeMethod(s, "init");
        return s;
    }

    @Test
    void validTokenAndGrant_establishesUserAndScope() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID briefId = UUID.randomUUID();
        PlatformUser user = activeUser(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        String grant = grantService.mint(user, briefId, List.of("get_project_context"));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Internal-Token", INTERNAL_TOKEN);
        req.addHeader("X-Mcp-Grant", grant);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(chain.getRequest()).isNotNull(); // chain proceeded
        assertThat(res.getStatus()).isEqualTo(200);
        var principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal).isInstanceOf(PlatformUserDetails.class);
        assertThat(((PlatformUserDetails) principal).getId()).isEqualTo(userId);
        McpCallContext ctx = McpCallContext.current(req);
        assertThat(ctx).isNotNull();
        assertThat(ctx.briefId()).isEqualTo(briefId);
        assertThat(ctx.allowsTool("get_project_context")).isTrue();
    }

    @Test
    void wrongInternalToken_is401_andStopsChain() throws Exception {
        UUID userId = UUID.randomUUID();
        PlatformUser user = activeUser(userId);
        String grant = grantService.mint(user, UUID.randomUUID(), List.of("x"));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Internal-Token", "WRONG");
        req.addHeader("X-Mcp-Grant", grant);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull(); // chain not proceeded
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void expiredGrant_is401() throws Exception {
        PlatformUser user = activeUser(UUID.randomUUID());
        String expired = freshTtl(-10).mint(user, UUID.randomUUID(), List.of("x"));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Internal-Token", INTERNAL_TOKEN);
        req.addHeader("X-Mcp-Grant", expired);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void unknownUser_is401() throws Exception {
        UUID userId = UUID.randomUUID();
        PlatformUser user = activeUser(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        String grant = grantService.mint(user, UUID.randomUUID(), List.of("x"));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Internal-Token", INTERNAL_TOKEN);
        req.addHeader("X-Mcp-Grant", grant);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }
}
