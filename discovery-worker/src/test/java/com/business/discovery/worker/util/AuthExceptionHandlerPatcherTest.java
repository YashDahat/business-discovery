package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AuthExceptionHandlerPatcherTest {

    @TempDir
    Path root;

    private Path srcDir() throws Exception {
        Path p = root.resolve("backend/src/main/java");
        Files.createDirectories(p);
        return p;
    }

    private Path write(String rel, String content) throws Exception {
        Path p = srcDir().resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    /** The circuit-house shape: an advice whose only catch-all maps everything to 500. */
    private static final String GENERIC_ONLY = """
            package com.circuithouse.exception;

            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.ExceptionHandler;
            import org.springframework.web.bind.annotation.RestControllerAdvice;

            @RestControllerAdvice
            public class GlobalExceptionHandler {

                @ExceptionHandler(Exception.class)
                public ResponseEntity<ErrorResponse> handleGlobalException(Exception ex) {
                    return ResponseEntity.internalServerError().build();
                }
            }
            """;

    @Test
    void injectsAuthenticationExceptionHandlerReturning401() throws Exception {
        Path advice = write("com/circuithouse/exception/GlobalExceptionHandler.java", GENERIC_ONLY);

        boolean changed = AuthExceptionHandlerPatcher.fix(srcDir());

        assertThat(changed).isTrue();
        String out = Files.readString(advice);
        assertThat(out).contains("AuthenticationException.class");
        assertThat(out).contains("HttpStatus.UNAUTHORIZED");
        // the class is still well-formed: last non-space char is the closing brace
        assertThat(out.strip()).endsWith("}");
    }

    @Test
    void skipsWhenAuthenticationAlreadyHandled() throws Exception {
        write("com/circuithouse/exception/GlobalExceptionHandler.java", GENERIC_ONLY.replace(
                "@ExceptionHandler(Exception.class)",
                "@ExceptionHandler(org.springframework.security.core.AuthenticationException.class)\n"
                        + "    public ResponseEntity<String> handleAuth(Exception e) { return null; }\n\n"
                        + "    @ExceptionHandler(Exception.class)"));

        assertThat(AuthExceptionHandlerPatcher.fix(srcDir())).isFalse();
    }

    @Test
    void isIdempotent() throws Exception {
        Path advice = write("com/circuithouse/exception/GlobalExceptionHandler.java", GENERIC_ONLY);
        assertThat(AuthExceptionHandlerPatcher.fix(srcDir())).isTrue();
        String once = Files.readString(advice);
        assertThat(AuthExceptionHandlerPatcher.fix(srcDir())).isFalse();
        assertThat(Files.readString(advice)).isEqualTo(once);
    }

    @Test
    void noAdviceIsNoOp() throws Exception {
        assertThat(AuthExceptionHandlerPatcher.fix(srcDir())).isFalse();
    }
}
