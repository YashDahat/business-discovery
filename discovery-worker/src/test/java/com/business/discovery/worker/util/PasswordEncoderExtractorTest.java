package com.business.discovery.worker.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture is the real circuit-house SecurityConfig (2026-07-12) — the boot-fatal cycle
 * userService -> securityConfig (PasswordEncoder @Bean) -> userService that
 * JwtCircularDependencyPatcher cannot see because it never passes through JwtAuthFilter.
 * Same shape shipped on yeti attempt 4 and died at the smoke gate after 120s.
 */
class PasswordEncoderExtractorTest {

    @TempDir
    Path src;

    private Path securityConfig;

    private static final String CIRCUIT_HOUSE_SECURITY_CONFIG = """
            package com.circuithouse.config;

            import com.circuithouse.security.JwtAuthFilter;
            import com.circuithouse.service.UserService;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
            import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
            import org.springframework.security.crypto.password.PasswordEncoder;

            @Configuration
            public class SecurityConfig {
                private final JwtAuthFilter jwtAuthFilter;
                private final UserService userService;

                public SecurityConfig(JwtAuthFilter jwtAuthFilter, UserService userService) {
                    this.jwtAuthFilter = jwtAuthFilter;
                    this.userService = userService;
                }

                @Bean
                public PasswordEncoder passwordEncoder() {
                    return new BCryptPasswordEncoder();
                }

                @Bean
                public DaoAuthenticationProvider authenticationProvider() {
                    DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(userService);
                    authProvider.setPasswordEncoder(passwordEncoder());
                    return authProvider;
                }
            }
            """;

    @BeforeEach
    void fixture() throws Exception {
        securityConfig = src.resolve("com/circuithouse/config/SecurityConfig.java");
        Files.createDirectories(securityConfig.getParent());
        Files.writeString(securityConfig, CIRCUIT_HOUSE_SECURITY_CONFIG);
    }

    @Test
    void breaksTheCircuitHouseCycle() throws Exception {
        boolean changed = PasswordEncoderExtractor.fix(src);

        assertThat(changed).isTrue();

        String host = Files.readString(securityConfig);
        // the @Bean is gone from the host, the helper method stays for internal callers
        assertThat(host).doesNotContain("@Bean\n    public PasswordEncoder");
        assertThat(host).contains("public PasswordEncoder passwordEncoder() {");
        assertThat(host).contains("authProvider.setPasswordEncoder(passwordEncoder());");
        // the OTHER beans keep their annotations
        assertThat(host).contains("@Bean\n    public DaoAuthenticationProvider");

        Path extracted = securityConfig.resolveSibling("PasswordEncoderConfig.java");
        assertThat(extracted).exists();
        String config = Files.readString(extracted);
        assertThat(config).contains("package com.circuithouse.config;");
        assertThat(config).contains("@Configuration");
        assertThat(config).contains("@Bean");
        // body carried verbatim — the extracted bean is the one Spring now serves
        assertThat(config).contains("return new BCryptPasswordEncoder();");
        assertThat(config).contains("import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;");
        // dependency-free: no constructor, no injected fields → cannot sit on a cycle
        assertThat(config).doesNotContain("private final");
    }

    @Test
    void secondRunIsANoOp() throws Exception {
        assertThat(PasswordEncoderExtractor.fix(src)).isTrue();
        String hostAfterFirst = Files.readString(securityConfig);

        assertThat(PasswordEncoderExtractor.fix(src)).isFalse();
        assertThat(Files.readString(securityConfig)).isEqualTo(hostAfterFirst);
    }

    @Test
    void leavesAnAlreadyStandaloneEncoderConfigAlone() throws Exception {
        Files.writeString(securityConfig, """
                package com.circuithouse.config;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.security.crypto.password.PasswordEncoder;
                import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

                @Configuration
                public class EncoderConfig {

                    @Bean
                    public PasswordEncoder passwordEncoder() {
                        return new BCryptPasswordEncoder();
                    }
                }
                """);

        // no constructor dependencies → no cycle possible → nothing to extract
        assertThat(PasswordEncoderExtractor.fix(src)).isFalse();
    }

    @Test
    void noEncoderBeanMeansNoChange() throws Exception {
        Files.writeString(securityConfig, """
                package com.circuithouse.config;
                public class SecurityConfig {
                    private final String x;
                    public SecurityConfig(String x) { this.x = x; }
                }
                """);

        assertThat(PasswordEncoderExtractor.fix(src)).isFalse();
    }

    @Test
    void preservesANonBcryptBodyVerbatim() throws Exception {
        Files.writeString(securityConfig, CIRCUIT_HOUSE_SECURITY_CONFIG.replace(
                "return new BCryptPasswordEncoder();",
                "return PasswordEncoderFactories.createDelegatingPasswordEncoder();"));

        assertThat(PasswordEncoderExtractor.fix(src)).isTrue();
        String config = Files.readString(securityConfig.resolveSibling("PasswordEncoderConfig.java"));
        // the generator's encoder choice survives — extracting must never swap the algorithm,
        // or seeded password hashes stop matching at login
        assertThat(config).contains("PasswordEncoderFactories.createDelegatingPasswordEncoder()");
        assertThat(config).doesNotContain("BCryptPasswordEncoder()");
    }

    @Test
    void matchBraceHandlesNesting() {
        String s = "{ a { b { c } } d }";
        assertThat(PasswordEncoderExtractor.matchBrace(s, 0)).isEqualTo(s.length() - 1);
        assertThat(PasswordEncoderExtractor.matchBrace("{ never closed", 0)).isEqualTo(-1);
    }
}
