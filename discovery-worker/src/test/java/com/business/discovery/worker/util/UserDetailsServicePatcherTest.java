package com.business.discovery.worker.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture is the real Vikram's Fitness Studio backend (2026-07-19): SecurityConfig and
 * JwtAuthFilter both constructor-inject UserDetailsService for DaoAuthenticationProvider,
 * but no class implements it. Compiled clean, published an image, then crash-looped on
 * context refresh ("No qualifying bean of type UserDetailsService") until the smoke gate
 * killed it.
 */
class UserDetailsServicePatcherTest {

    @TempDir
    Path src;

    private static final String SECURITY_CONFIG = """
            package com.vikramsfitnessstudio.config;

            import com.vikramsfitnessstudio.security.JwtAuthFilter;
            import org.springframework.context.annotation.Configuration;
            import org.springframework.security.core.userdetails.UserDetailsService;

            @Configuration
            public class SecurityConfig {
                private final JwtAuthFilter jwtAuthFilter;
                private final UserDetailsService userDetailsService;

                public SecurityConfig(JwtAuthFilter jwtAuthFilter, UserDetailsService userDetailsService) {
                    this.jwtAuthFilter = jwtAuthFilter;
                    this.userDetailsService = userDetailsService;
                }
            }
            """;

    private static final String USER_REPOSITORY = """
            package com.vikramsfitnessstudio.repository;

            import com.vikramsfitnessstudio.model.User;
            import org.springframework.data.jpa.repository.JpaRepository;
            import org.springframework.stereotype.Repository;

            import java.util.Optional;

            @Repository
            public interface UserRepository extends JpaRepository<User, Long> {
                Optional<User> findByUsername(String username);
            }
            """;

    private static final String USER_ENTITY = """
            package com.vikramsfitnessstudio.model;

            import jakarta.persistence.Entity;
            import java.util.Set;

            @Entity
            public class User {
                private String username;
                private String password;
                private Set<Role> roles;

                public String getUsername() { return username; }
                public String getPassword() { return password; }
                public Set<Role> getRoles() { return roles; }
            }
            """;

    private static final String ROLE_ENUM = """
            package com.vikramsfitnessstudio.model;

            public enum Role {
                ADMIN,
                MEMBER
            }
            """;

    private static final String USER_SERVICE = """
            package com.vikramsfitnessstudio.service;

            import com.vikramsfitnessstudio.repository.UserRepository;
            import org.springframework.stereotype.Service;

            @Service
            public class UserService {
                private final UserRepository userRepository;

                public UserService(UserRepository userRepository) {
                    this.userRepository = userRepository;
                }
            }
            """;

    @BeforeEach
    void fixtures() throws Exception {
        write("com/vikramsfitnessstudio/config/SecurityConfig.java", SECURITY_CONFIG);
        write("com/vikramsfitnessstudio/repository/UserRepository.java", USER_REPOSITORY);
        write("com/vikramsfitnessstudio/model/User.java", USER_ENTITY);
        write("com/vikramsfitnessstudio/model/Role.java", ROLE_ENUM);
        write("com/vikramsfitnessstudio/service/UserService.java", USER_SERVICE);
    }

    private void write(String rel, String content) throws Exception {
        Path p = src.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void createsCustomUserDetailsServiceWiredToTheRealRepositoryShape() throws Exception {
        assertThat(UserDetailsServicePatcher.fix(src)).isTrue();

        Path created = src.resolve("com/vikramsfitnessstudio/service/CustomUserDetailsService.java");
        assertThat(created).exists();

        String content = Files.readString(created);
        assertThat(content).contains("package com.vikramsfitnessstudio.service;");
        assertThat(content).contains("import com.vikramsfitnessstudio.model.User;");
        assertThat(content).contains("import com.vikramsfitnessstudio.repository.UserRepository;");
        assertThat(content).contains("@Service");
        assertThat(content).contains("implements UserDetailsService");
        assertThat(content).contains("public CustomUserDetailsService(UserRepository userRepository)");
        // wired to the ACTUAL repo method/param, not a fixed assumption
        assertThat(content).contains("userRepository.findByUsername(username)");
        assertThat(content).contains("user.getUsername()");
        assertThat(content).contains("user.getPassword()");
        assertThat(content).contains("user.getRoles()");
        assertThat(content).contains("new SimpleGrantedAuthority(\"ROLE_\" + role.name())");
    }

    @Test
    void doesNothingWhenAlreadyImplemented() throws Exception {
        write("com/vikramsfitnessstudio/service/UserService.java", """
                package com.vikramsfitnessstudio.service;

                import com.vikramsfitnessstudio.repository.UserRepository;
                import org.springframework.security.core.userdetails.UserDetails;
                import org.springframework.security.core.userdetails.UserDetailsService;
                import org.springframework.stereotype.Service;

                @Service
                public class UserService implements UserDetailsService {
                    private final UserRepository userRepository;

                    public UserService(UserRepository userRepository) {
                        this.userRepository = userRepository;
                    }

                    @Override
                    public UserDetails loadUserByUsername(String username) {
                        return null;
                    }
                }
                """);

        assertThat(UserDetailsServicePatcher.fix(src)).isFalse();
        assertThat(src.resolve("com/vikramsfitnessstudio/service/CustomUserDetailsService.java")).doesNotExist();
    }

    @Test
    void doesNothingWhenNobodyInjectsIt() throws Exception {
        write("com/vikramsfitnessstudio/config/SecurityConfig.java", """
                package com.vikramsfitnessstudio.config;

                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class SecurityConfig {
                }
                """);

        assertThat(UserDetailsServicePatcher.fix(src)).isFalse();
        assertThat(src.resolve("com/vikramsfitnessstudio/service/CustomUserDetailsService.java")).doesNotExist();
    }

    @Test
    void isIdempotentAcrossAttempts() throws Exception {
        assertThat(UserDetailsServicePatcher.fix(src)).isTrue();
        assertThat(UserDetailsServicePatcher.fix(src)).isFalse();
    }

    @Test
    void backsOffWhenNoFindByStringLookupExists() throws Exception {
        write("com/vikramsfitnessstudio/repository/UserRepository.java", """
                package com.vikramsfitnessstudio.repository;

                import com.vikramsfitnessstudio.model.User;
                import org.springframework.data.jpa.repository.JpaRepository;
                import org.springframework.stereotype.Repository;

                @Repository
                public interface UserRepository extends JpaRepository<User, Long> {
                }
                """);

        assertThat(UserDetailsServicePatcher.fix(src)).isFalse();
        assertThat(src.resolve("com/vikramsfitnessstudio/service/CustomUserDetailsService.java")).doesNotExist();
    }

    @Test
    void backsOffWhenRolesGetterMissing() throws Exception {
        write("com/vikramsfitnessstudio/model/User.java", """
                package com.vikramsfitnessstudio.model;

                import jakarta.persistence.Entity;

                @Entity
                public class User {
                    private String username;
                    private String password;

                    public String getUsername() { return username; }
                    public String getPassword() { return password; }
                }
                """);

        assertThat(UserDetailsServicePatcher.fix(src)).isFalse();
        assertThat(src.resolve("com/vikramsfitnessstudio/service/CustomUserDetailsService.java")).doesNotExist();
    }

    @Test
    void prefersFindByUsernameWhenBothUsernameAndEmailLookupsExist() throws Exception {
        write("com/vikramsfitnessstudio/repository/UserRepository.java", """
                package com.vikramsfitnessstudio.repository;

                import com.vikramsfitnessstudio.model.User;
                import org.springframework.data.jpa.repository.JpaRepository;
                import org.springframework.stereotype.Repository;

                import java.util.Optional;

                @Repository
                public interface UserRepository extends JpaRepository<User, Long> {
                    Optional<User> findByEmail(String email);
                    Optional<User> findByUsername(String username);
                }
                """);

        assertThat(UserDetailsServicePatcher.fix(src)).isTrue();
        String content = Files.readString(
                src.resolve("com/vikramsfitnessstudio/service/CustomUserDetailsService.java"));
        assertThat(content).contains("userRepository.findByUsername(username)");
    }
}
