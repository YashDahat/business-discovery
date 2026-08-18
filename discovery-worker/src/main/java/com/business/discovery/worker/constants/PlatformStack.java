package com.business.discovery.worker.constants;

import java.util.Map;

/**
 * Single source of truth for the FIXED platform tech stack.
 *
 * The stack is a platform decision, never a per-brief one — a brief-supplied stack
 * (e.g. "Next.js (React)") is not actionable and only mis-seeds generation and the
 * generated docs/PR. {@code DataLoaderNode} overwrites every brief's
 * {@code recommendedTechStack} with {@link #STACK} at ingestion (in-memory only, never
 * persisted — the loaded entity is detached), so no downstream consumer — planning,
 * enrichment, docs, PR, or any future one — can surface a brief-supplied framework.
 * See docs/frontend-error-patterns-abs-fitness.md (F6).
 *
 * When the platform moves (e.g. React → Next.js), this constant is the single flip point.
 */
public final class PlatformStack {

    public static final Map<String, String> STACK = Map.of(
            "frontend", "React 19 + TypeScript on Vite, react-router-dom, Tailwind CSS",
            "backend", "Spring Boot 3 (Java 17) + Spring Data JPA",
            "database", "PostgreSQL");

    private PlatformStack() {}
}
