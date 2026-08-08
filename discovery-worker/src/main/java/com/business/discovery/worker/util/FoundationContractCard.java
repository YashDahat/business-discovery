package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads the fenced-foundation contract cards shipped in the webapp-foundation repo
 * ({@code frontend/FOUNDATION_CONTRACT.md} and {@code backend/FOUNDATION_CONTRACT.md}) from the
 * cloned workspace. Each card is the ground-truth API surface of the immutable
 * auth / cart / checkout / payment / user spine — the shapes generated code must match but
 * which the LLM otherwise guesses (user.role vs roles, isLoading vs loading, useCheckout(steps),
 * User.email, PaymentService.createOrder, ...).
 *
 * Split per stack on purpose: the frontend and backend stages never run at the same time, so
 * each injects only its own card and neither pays for the other's tokens.
 *
 * The cards are authored by hand in the foundation repo (they describe fenced, immutable files),
 * so they are deterministic and byte-identical every run — appended to the SYSTEM prompt they ride
 * the provider's prefix cache. {@link #frontend}/{@link #backend} return {@code ""} when the file
 * is absent (a foundation clone predating this feature) so callers degrade gracefully.
 */
@Slf4j
public final class FoundationContractCard {

    public static final String PROMPT_KEY =
            "── FENCED FOUNDATION CONTRACT (ground truth — these modules already exist and are immutable; "
          + "import and call them EXACTLY as written, never re-declare or re-implement them) ──";

    private static final String FRONTEND_REL = "frontend/FOUNDATION_CONTRACT.md";
    private static final String BACKEND_REL  = "backend/FOUNDATION_CONTRACT.md";

    private FoundationContractCard() {}

    /** Raw frontend contract body, or {@code ""} if the file is absent/empty. */
    public static String frontend(Path workspace) { return read(workspace.resolve(FRONTEND_REL)); }

    /** Raw backend contract body, or {@code ""} if the file is absent/empty. */
    public static String backend(Path workspace) { return read(workspace.resolve(BACKEND_REL)); }

    /** Frontend contract as a prompt section (key + body), or {@code ""} if absent. */
    public static String frontendSection(Path workspace) { return section(frontend(workspace)); }

    /** Backend contract as a prompt section (key + body), or {@code ""} if absent. */
    public static String backendSection(Path workspace) { return section(backend(workspace)); }

    private static String section(String body) {
        return body.isEmpty() ? "" : PROMPT_KEY + "\n" + body;
    }

    private static String read(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                String content = Files.readString(file).strip();
                if (!content.isEmpty()) return content;
            }
        } catch (IOException e) {
            log.warn("[FoundationContractCard] Could not read {}: {}", file, e.getMessage());
        }
        return "";
    }
}
