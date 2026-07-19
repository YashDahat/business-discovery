package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Ensures the exception advice maps a Spring Security {@code AuthenticationException} to
 * HTTP 401, not 500.
 *
 * {@code authenticationManager.authenticate(...)} throws {@code BadCredentialsException}
 * (an {@code AuthenticationException}) on a wrong password, from INSIDE the login controller
 * method — so a {@code @RestControllerAdvice} with a generic {@code @ExceptionHandler(Exception.class)}
 * catches it and returns 500. Circuit-house 2026-07-17 shipped exactly this: every wrong-password
 * login answered 500, which reads as a server crash and misleads the smoke report.
 *
 * A runtime-correctness patch — the defect compiles clean, so this runs unconditionally in
 * BackendValidationNode before compilation, alongside the other boot/request-time patchers.
 */
@Slf4j
public final class AuthExceptionHandlerPatcher {

    private AuthExceptionHandlerPatcher() {}

    private static final String HANDLER = """

                @org.springframework.web.bind.annotation.ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
                public org.springframework.http.ResponseEntity<String> handleAuthenticationException(
                        org.springframework.security.core.AuthenticationException ex) {
                    return org.springframework.http.ResponseEntity
                            .status(org.springframework.http.HttpStatus.UNAUTHORIZED)
                            .body(ex.getMessage());
                }
            """;

    /** Returns true if a handler was injected. */
    public static boolean fix(Path backendSrcDir) {
        Optional<Path> adviceFile = findAdvice(backendSrcDir);
        if (adviceFile.isEmpty()) {
            log.info("[AuthExceptionHandlerPatcher] No @ControllerAdvice found — skipping");
            return false;
        }
        Path file = adviceFile.get();
        try {
            String content = Files.readString(file);
            // Already handled (either the base type or the concrete BadCredentialsException).
            if (content.contains("AuthenticationException.class")
                    || content.contains("BadCredentialsException.class")) {
                return false;
            }
            int lastBrace = content.lastIndexOf('}');
            if (lastBrace < 0) return false;

            String patched = content.substring(0, lastBrace) + HANDLER + content.substring(lastBrace);
            Files.writeString(file, patched);
            log.info("[AuthExceptionHandlerPatcher] Added AuthenticationException→401 handler to {}",
                    file.getFileName());
            return true;
        } catch (IOException e) {
            log.warn("[AuthExceptionHandlerPatcher] Could not patch {}: {}", file, e.getMessage());
            return false;
        }
    }

    /** The exception advice class — GlobalExceptionHandler by convention, else any advice. */
    private static Optional<Path> findAdvice(Path backendSrcDir) {
        if (!Files.exists(backendSrcDir)) return Optional.empty();
        try (Stream<Path> s = Files.walk(backendSrcDir)) {
            return s.filter(p -> p.toString().endsWith(".java"))
                    .filter(AuthExceptionHandlerPatcher::isAdvice)
                    .min((a, b) -> Boolean.compare(
                            !a.getFileName().toString().contains("GlobalExceptionHandler"),
                            !b.getFileName().toString().contains("GlobalExceptionHandler")));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static boolean isAdvice(Path p) {
        try {
            String c = Files.readString(p);
            return c.contains("@RestControllerAdvice") || c.contains("@ControllerAdvice");
        } catch (IOException e) {
            return false;
        }
    }
}
