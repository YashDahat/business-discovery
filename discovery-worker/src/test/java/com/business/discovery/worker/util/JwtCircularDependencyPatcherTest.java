package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JwtCircularDependencyPatcherTest {

    @TempDir
    Path src;

    // Exact shape observed on multifit-aundh — compiled clean, crashed at boot with
    // "The dependencies of some of the beans in the application context form a cycle:
    //  jwtAuthFilter -> userService -> securityConfig -> jwtAuthFilter"
    private static final String UNPATCHED = """
            package com.multifitaundh.security;

            import com.multifitaundh.service.UserService;
            import com.multifitaundh.util.JwtUtil;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Component;

            @Component
            public class JwtAuthFilter extends OncePerRequestFilter {

                private final JwtUtil jwtUtil;
                private final UserService userService;

                @Autowired
                public JwtAuthFilter(JwtUtil jwtUtil, UserService userService) {
                    this.jwtUtil = jwtUtil;
                    this.userService = userService;
                }
            }
            """;

    private Path writeFilter(String content) throws Exception {
        Path dir = src.resolve("com/multifitaundh/security");
        Files.createDirectories(dir);
        Path file = dir.resolve("JwtAuthFilter.java");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void addsLazyToNonJwtUtilConstructorParam_andImport() throws Exception {
        Path file = writeFilter(UNPATCHED);

        boolean changed = JwtCircularDependencyPatcher.fix(src);

        assertThat(changed).isTrue();
        String result = Files.readString(file);
        assertThat(result).contains("public JwtAuthFilter(JwtUtil jwtUtil, @Lazy UserService userService)");
        assertThat(result).contains("import org.springframework.context.annotation.Lazy;");
        assertThat(result).doesNotContain("@Lazy JwtUtil"); // JwtUtil itself untouched
    }

    @Test
    void idempotent_alreadyLazyIsUntouched() throws Exception {
        String alreadyPatched = UNPATCHED
                .replace("import com.multifitaundh.util.JwtUtil;",
                         "import com.multifitaundh.util.JwtUtil;\nimport org.springframework.context.annotation.Lazy;")
                .replace("JwtUtil jwtUtil, UserService userService", "JwtUtil jwtUtil, @Lazy UserService userService");
        Path file = writeFilter(alreadyPatched);

        boolean changed = JwtCircularDependencyPatcher.fix(src);

        assertThat(changed).isFalse();
        assertThat(Files.readString(file)).isEqualTo(alreadyPatched);
    }

    @Test
    void noJwtAuthFilter_returnsFalse() {
        assertThat(JwtCircularDependencyPatcher.fix(src)).isFalse();
    }

    @Test
    void missingDirectory_returnsFalseWithoutThrowing() {
        assertThat(JwtCircularDependencyPatcher.fix(src.resolve("does-not-exist"))).isFalse();
    }
}
