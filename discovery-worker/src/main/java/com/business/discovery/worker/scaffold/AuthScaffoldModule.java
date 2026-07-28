package com.business.discovery.worker.scaffold;

import com.business.discovery.worker.util.JavaFileTemplater;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Scaffolds the universal auth/security spine — the slice that is byte-for-byte identical across
 * every business (only the base package varies). Pre-writing it makes a whole class of runtime
 * defects structurally impossible: the wrong Role enum values (ID/NAME), the missing
 * UserDetailsService bean, the PasswordEncoder-in-SecurityConfig cycle, ROLE_-prefix mismatches,
 * and — via the hardened JwtAuthFilter — the stale-JWT 500.
 *
 * <p>Templates are lifted verbatim from a verified-booting reference project; the base package is
 * injected by replacing the {@code __BP__} token. JwtUtil is delegated to
 * {@link JavaFileTemplater#jwtUtilSource} so the JJWT 0.11.5 template has a single source of truth.
 */
@Component
@Order(10)
@Slf4j
public class AuthScaffoldModule implements ScaffoldModule {

    private static final String TOKEN = "__BP__";

    @Override
    public String name() {
        return "auth";
    }

    @Override
    public void write(Path backendJavaRoot, String basePackage) throws IOException {
        Path root = backendJavaRoot.resolve(basePackage.replace('.', '/'));

        writeFile(root.resolve("model/Role.java"),                    render(ROLE, basePackage));
        writeFile(root.resolve("model/User.java"),                    render(USER, basePackage));
        writeFile(root.resolve("repository/UserRepository.java"),     render(USER_REPOSITORY, basePackage));
        writeFile(root.resolve("dto/AuthRequest.java"),               render(AUTH_REQUEST, basePackage));
        writeFile(root.resolve("dto/AuthResponse.java"),              render(AUTH_RESPONSE, basePackage));
        writeFile(root.resolve("util/JwtUtil.java"),                  JavaFileTemplater.jwtUtilSource(basePackage + ".util", "JwtUtil"));
        writeFile(root.resolve("security/JwtAuthFilter.java"),        render(JWT_AUTH_FILTER, basePackage));
        writeFile(root.resolve("service/UserService.java"),          render(USER_SERVICE, basePackage));
        writeFile(root.resolve("config/PasswordEncoderConfig.java"), render(PASSWORD_ENCODER_CONFIG, basePackage));
        writeFile(root.resolve("config/SecurityConfig.java"),        render(SECURITY_CONFIG, basePackage));
        writeFile(root.resolve("controller/AuthController.java"),    render(AUTH_CONTROLLER, basePackage));
        writeFile(root.resolve("config/AdminInitializer.java"),      render(ADMIN_INITIALIZER, basePackage));

        log.info("[AuthScaffoldModule] Wrote 12 auth-spine files under {}", basePackage);
    }

    @Override
    public List<Pattern> ownedFilePatterns() {
        // Anchored to .java so frontend authService.ts / types/auth.ts are never touched.
        // Broad on the security-critical names (Jwt*, UserDetailsService*) to catch LLM renames.
        return List.of(
                Pattern.compile("^Role\\.java$"),
                Pattern.compile("^User\\.java$"),
                Pattern.compile("^UserRepository\\.java$"),
                Pattern.compile("^(UserService|UserDetailsService(Impl)?)\\.java$"),
                Pattern.compile("^AuthController\\.java$"),
                Pattern.compile("^Auth(Request|Response)\\.java$"),
                Pattern.compile("^SecurityConfig(uration)?\\.java$"),
                Pattern.compile("^Jwt.*\\.java$"),
                Pattern.compile("^PasswordEncoder.*\\.java$"),
                Pattern.compile("^AdminInitializer\\.java$")
        );
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String render(String template, String basePackage) {
        return template.replace(TOKEN, basePackage);
    }

    private static void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    // ── templates (base package injected via __BP__) ─────────────────────────

    private static final String ROLE = """
            package __BP__.model;

            public enum Role {
                ADMIN,
                USER
            }
            """;

    private static final String USER = """
            package __BP__.model;

            import jakarta.persistence.*;
            import lombok.AllArgsConstructor;
            import lombok.Builder;
            import lombok.Data;
            import lombok.NoArgsConstructor;
            import org.springframework.security.core.GrantedAuthority;
            import org.springframework.security.core.authority.SimpleGrantedAuthority;
            import org.springframework.security.core.userdetails.UserDetails;

            import java.util.Collection;
            import java.util.List;

            @Data
            @Builder
            @NoArgsConstructor
            @AllArgsConstructor
            @Entity
            @Table(name = "_user")
            public class User implements UserDetails {

                @Id
                @GeneratedValue(strategy = GenerationType.IDENTITY)
                private Integer id;
                private String firstName;
                private String lastName;
                private String email;
                private String password;

                @Enumerated(EnumType.STRING)
                private Role role;

                @Override
                public Collection<? extends GrantedAuthority> getAuthorities() {
                    return List.of(new SimpleGrantedAuthority(role.name()));
                }

                @Override
                public String getUsername() {
                    return email;
                }

                @Override
                public boolean isAccountNonExpired() {
                    return true;
                }

                @Override
                public boolean isAccountNonLocked() {
                    return true;
                }

                @Override
                public boolean isCredentialsNonExpired() {
                    return true;
                }

                @Override
                public boolean isEnabled() {
                    return true;
                }
            }
            """;

    private static final String USER_REPOSITORY = """
            package __BP__.repository;

            import __BP__.model.User;
            import org.springframework.data.jpa.repository.JpaRepository;
            import org.springframework.stereotype.Repository;

            import java.util.Optional;

            @Repository
            public interface UserRepository extends JpaRepository<User, Integer> {
                Optional<User> findByEmail(String email);
            }
            """;

    private static final String AUTH_REQUEST = """
            package __BP__.dto;

            import lombok.AllArgsConstructor;
            import lombok.Builder;
            import lombok.Data;
            import lombok.NoArgsConstructor;

            @Data
            @Builder
            @NoArgsConstructor
            @AllArgsConstructor
            public class AuthRequest {
                private String username;
                private String password;
            }
            """;

    private static final String AUTH_RESPONSE = """
            package __BP__.dto;

            import lombok.AllArgsConstructor;
            import lombok.Builder;
            import lombok.Data;
            import lombok.NoArgsConstructor;

            @Data
            @Builder
            @NoArgsConstructor
            @AllArgsConstructor
            public class AuthResponse {
                private String token;
            }
            """;

    private static final String JWT_AUTH_FILTER = """
            package __BP__.security;

            import __BP__.service.UserService;
            import __BP__.util.JwtUtil;
            import io.jsonwebtoken.JwtException;
            import jakarta.servlet.FilterChain;
            import jakarta.servlet.ServletException;
            import jakarta.servlet.http.HttpServletRequest;
            import jakarta.servlet.http.HttpServletResponse;
            import org.springframework.context.annotation.Lazy;
            import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
            import org.springframework.security.core.context.SecurityContextHolder;
            import org.springframework.security.core.userdetails.UserDetails;
            import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
            import org.springframework.stereotype.Component;
            import org.springframework.web.filter.OncePerRequestFilter;

            import java.io.IOException;

            @Component
            public class JwtAuthFilter extends OncePerRequestFilter {

                private final JwtUtil jwtUtil;
                private final UserService userService;

                public JwtAuthFilter(JwtUtil jwtUtil, @Lazy UserService userService) {
                    this.jwtUtil = jwtUtil;
                    this.userService = userService;
                }

                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                        throws ServletException, IOException {
                    final String authHeader = request.getHeader("Authorization");

                    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                        filterChain.doFilter(request, response);
                        return;
                    }

                    final String jwt = authHeader.substring(7);
                    try {
                        final String userEmail = jwtUtil.extractUsername(jwt);
                        if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                            UserDetails userDetails = this.userService.loadUserByUsername(userEmail);
                            if (jwtUtil.validateToken(jwt, userDetails)) {
                                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                                        userDetails, null, userDetails.getAuthorities());
                                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                                SecurityContextHolder.getContext().setAuthentication(authToken);
                            }
                        }
                    } catch (JwtException | IllegalArgumentException ex) {
                        // Invalid / expired / tampered token (e.g. a stale JWT signed with a previous
                        // deploy's secret). Continue as anonymous rather than 500 — public endpoints
                        // still serve; protected ones fall through to the normal 401/403 path.
                        logger.warn("Ignoring invalid JWT: " + ex.getMessage());
                    }

                    filterChain.doFilter(request, response);
                }
            }
            """;

    private static final String USER_SERVICE = """
            package __BP__.service;

            import __BP__.model.User;
            import __BP__.repository.UserRepository;
            import org.springframework.security.core.userdetails.UserDetails;
            import org.springframework.security.core.userdetails.UserDetailsService;
            import org.springframework.security.core.userdetails.UsernameNotFoundException;
            import org.springframework.stereotype.Service;

            @Service
            public class UserService implements UserDetailsService {

                private final UserRepository userRepository;

                public UserService(UserRepository userRepository) {
                    this.userRepository = userRepository;
                }

                @Override
                public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
                    return userRepository.findByEmail(username)
                            .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + username));
                }
            }
            """;

    private static final String PASSWORD_ENCODER_CONFIG = """
            package __BP__.config;

            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
            import org.springframework.security.crypto.password.PasswordEncoder;

            /**
             * Extracted from the security configuration so the encoder bean has no dependencies
             * (can never participate in a bean cycle) and is guaranteed to be registered.
             */
            @Configuration
            public class PasswordEncoderConfig {

                @Bean
                public PasswordEncoder passwordEncoder() {
                    return new BCryptPasswordEncoder();
                }
            }
            """;

    private static final String SECURITY_CONFIG = """
            package __BP__.config;

            import __BP__.security.JwtAuthFilter;
            import __BP__.service.UserService;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            import org.springframework.security.authentication.AuthenticationManager;
            import org.springframework.security.authentication.AuthenticationProvider;
            import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
            import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
            import org.springframework.security.config.annotation.web.builders.HttpSecurity;
            import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
            import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
            import org.springframework.security.config.http.SessionCreationPolicy;
            import org.springframework.security.crypto.password.PasswordEncoder;
            import org.springframework.security.web.SecurityFilterChain;
            import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

            @Configuration
            @EnableWebSecurity
            public class SecurityConfig {

                private final JwtAuthFilter jwtAuthFilter;
                private final UserService userService;
                private final PasswordEncoder passwordEncoder;

                public SecurityConfig(JwtAuthFilter jwtAuthFilter, UserService userService, PasswordEncoder passwordEncoder) {
                    this.jwtAuthFilter = jwtAuthFilter;
                    this.userService = userService;
                    this.passwordEncoder = passwordEncoder;
                }

                @Bean
                public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
                    http
                            .csrf(AbstractHttpConfigurer::disable)
                            .authorizeHttpRequests(authorize -> authorize
                                    .requestMatchers("/api/v1/auth/**", "/", "/index.html", "/static/**", "/*.js", "/*.css", "/*.json", "/*.ico", "/assets/**").permitAll()
                                    .requestMatchers("/api/v1/admin/**").hasAuthority("ADMIN")
                                    .anyRequest().permitAll()
                            )
                            .sessionManagement(session -> session
                                    .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                            )
                            .authenticationProvider(authenticationProvider())
                            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
                    return http.build();
                }

                @Bean
                public AuthenticationProvider authenticationProvider() {
                    DaoAuthenticationProvider authenticationProvider = new DaoAuthenticationProvider(userService);
                    authenticationProvider.setPasswordEncoder(passwordEncoder);
                    return authenticationProvider;
                }

                @Bean
                public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
                    return config.getAuthenticationManager();
                }
            }
            """;

    private static final String AUTH_CONTROLLER = """
            package __BP__.controller;

            import __BP__.dto.AuthRequest;
            import __BP__.dto.AuthResponse;
            import __BP__.service.UserService;
            import __BP__.util.JwtUtil;
            import org.springframework.http.ResponseEntity;
            import org.springframework.security.authentication.AuthenticationManager;
            import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
            import org.springframework.security.core.userdetails.UserDetails;
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestBody;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            @RequestMapping("/api/v1/auth")
            public class AuthController {

                private final AuthenticationManager authenticationManager;
                private final JwtUtil jwtUtil;
                private final UserService userService;

                public AuthController(AuthenticationManager authenticationManager, JwtUtil jwtUtil, UserService userService) {
                    this.authenticationManager = authenticationManager;
                    this.jwtUtil = jwtUtil;
                    this.userService = userService;
                }

                @PostMapping("/login")
                public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest authRequest) {
                    authenticationManager.authenticate(
                            new UsernamePasswordAuthenticationToken(authRequest.getUsername(), authRequest.getPassword())
                    );
                    final UserDetails userDetails = userService.loadUserByUsername(authRequest.getUsername());
                    final String jwt = jwtUtil.generateToken(userDetails);
                    return ResponseEntity.ok(new AuthResponse(jwt));
                }
            }
            """;

    private static final String ADMIN_INITIALIZER = """
            package __BP__.config;

            import __BP__.model.Role;
            import __BP__.model.User;
            import __BP__.repository.UserRepository;
            import org.springframework.beans.factory.annotation.Value;
            import org.springframework.boot.CommandLineRunner;
            import org.springframework.security.crypto.password.PasswordEncoder;
            import org.springframework.stereotype.Component;

            /**
             * Seeds the single ADMIN user from the admin.email / admin.password properties (which carry
             * demo-safe defaults in application.properties). Idempotent — never creates a duplicate.
             * This is the ONLY user-seeding component; a business DataSeeder must seed domain content only.
             */
            @Component
            public class AdminInitializer implements CommandLineRunner {

                private final UserRepository userRepository;
                private final PasswordEncoder passwordEncoder;

                @Value("${admin.email:owner@yourbusiness.com}")
                private String adminEmail;

                @Value("${admin.password:changeme123}")
                private String adminPassword;

                public AdminInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
                    this.userRepository = userRepository;
                    this.passwordEncoder = passwordEncoder;
                }

                @Override
                public void run(String... args) {
                    if (userRepository.findByEmail(adminEmail).isEmpty()) {
                        userRepository.save(User.builder()
                                .firstName("Admin")
                                .lastName("User")
                                .email(adminEmail)
                                .password(passwordEncoder.encode(adminPassword))
                                .role(Role.ADMIN)
                                .build());
                    }
                }
            }
            """;
}
