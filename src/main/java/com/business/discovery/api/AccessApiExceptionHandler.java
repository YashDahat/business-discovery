package com.business.discovery.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Returns a JSON body carrying the exception reason for the Access & Roles endpoints,
 * so the SPA can display messages like the last-operator lockout instead of a generic
 * fallback. Scoped to the auth/user controllers only — it deliberately does NOT change
 * the default error contract of any other endpoint.
 */
@RestControllerAdvice(assignableTypes = { AuthController.class, UserAdminController.class })
public class AccessApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handle(ResponseStatusException ex) {
        String reason = ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString();
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "status", ex.getStatusCode().value(),
                "message", reason));
    }
}
