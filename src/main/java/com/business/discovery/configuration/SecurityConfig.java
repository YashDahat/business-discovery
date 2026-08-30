package com.business.discovery.configuration;

import com.business.discovery.security.McpAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Authentication + authorization wiring for the Ops Console (Access & Roles feature).
 *
 * Rules (first match wins):
 *   /api/auth/**            → public (login/logout/me/profile/change-password self-manage auth)
 *   /actuator/health        → public
 *   /api/admin/**           → OPERATOR only (user administration)
 *   POST/PUT/PATCH/DELETE   → OPERATOR only (ANALYST/CLIENT/RESELLER are read-only)
 *   other /api/**           → any authenticated user (row-level business scoping in controllers)
 *   everything else         → public (SPA HTML, JS/CSS/assets, /login) — data still behind /api
 *
 * Unauthenticated API calls get 401 and authenticated-but-forbidden get 403 (no redirect to a
 * login form), so the SPA can distinguish "sign in" from "not permitted".
 *
 * The AuthenticationManager is built from AuthenticationConfiguration (not injected here), so
 * this config never depends on UserService/PasswordEncoder — avoiding a bean cycle.
 *
 * CSRF is disabled: internal, same-origin JSON console using an httpOnly session cookie.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Session-backed context repository. AuthController saves the authenticated
     * SecurityContext here on login so subsequent requests (e.g. /api/auth/me) resolve
     * the same user from the session cookie.
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * Exposes the auto-configured AuthenticationManager. Because a single
     * UserDetailsService bean (PlatformUserDetailsService) and a PasswordEncoder bean
     * exist, Spring wires a DaoAuthenticationProvider into it automatically.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * Spring Boot auto-registers any {@link jakarta.servlet.Filter} bean into the GLOBAL servlet chain.
     * Without this, {@link McpAuthFilter} would run on every request (not just /internal/mcp/**) and 401
     * everything lacking an MCP grant — including login. Disabling the auto-registration keeps the bean
     * available for {@link #mcpSecurityFilterChain} only.
     */
    @Bean
    public FilterRegistrationBean<McpAuthFilter> mcpAuthFilterRegistration(McpAuthFilter filter) {
        FilterRegistrationBean<McpAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Dedicated stateless chain for the MCP tool endpoints the Cline sidecar calls. These carry no
     * session cookie — auth is the two-layer internal-token + signed-grant check in {@link McpAuthFilter},
     * which sets the acting user in the SecurityContext so the same role model applies. Ordered ahead of
     * the main chain so /internal/mcp/** is matched here first.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http,
                                                      McpAuthFilter mcpAuthFilter) throws Exception {
        http
                .securityMatcher("/internal/mcp/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(mcpAuthFilter, AuthorizationFilter.class)
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED)));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SecurityContextRepository contextRepository) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .securityContext(sc -> sc.securityContextRepository(contextRepository))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        // Public — login/logout/me/profile/change-password enforce their own auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // User administration is operator-only
                        .requestMatchers("/api/admin/**").hasRole("OPERATOR")
                        // All state-changing API calls are operator-only (others read-only)
                        .requestMatchers(HttpMethod.POST, "/api/**").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.PUT, "/api/**").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.PATCH, "/api/**").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasRole("OPERATOR")
                        // Any other API read requires a logged-in user (controllers scope the data)
                        .requestMatchers("/api/**").authenticated()
                        // SPA shell, static assets, /login, world-dotmap.svg, etc.
                        .anyRequest().permitAll()
                )
                // We ship our own JSON endpoints under /api/auth instead of the defaults.
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                // 401 for unauthenticated, 403 for forbidden — no login-form redirect (SPA handles it)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED))
                        .accessDeniedHandler((req, res, e) -> res.sendError(HttpServletResponse.SC_FORBIDDEN)));
        return http.build();
    }
}
