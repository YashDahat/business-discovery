package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loads the fenced-foundation contract cards (frontend/backend FOUNDATION_CONTRACT.md) from the
 * cloned workspace, split per stack, degrading to "" when absent so an older foundation clone
 * still generates.
 */
class FoundationContractCardTest {

    @TempDir Path workspace;

    @Test
    void frontend_readsBodyAndWrapsSectionWithKey() throws IOException {
        Files.createDirectories(workspace.resolve("frontend"));
        Files.writeString(workspace.resolve("frontend/FOUNDATION_CONTRACT.md"),
                "useAuth(): { isLoading: boolean; user: { username: string; role: string } | null }");

        assertThat(FoundationContractCard.frontend(workspace))
                .contains("useAuth()").contains("isLoading");
        assertThat(FoundationContractCard.frontendSection(workspace))
                .startsWith(FoundationContractCard.PROMPT_KEY).contains("useAuth()");
    }

    @Test
    void backend_readsBodyAndWrapsSectionWithKey() throws IOException {
        Files.createDirectories(workspace.resolve("backend"));
        Files.writeString(workspace.resolve("backend/FOUNDATION_CONTRACT.md"),
                "class User { Integer id; String email; Role role; }");

        assertThat(FoundationContractCard.backend(workspace))
                .contains("class User").contains("email");
        assertThat(FoundationContractCard.backendSection(workspace))
                .startsWith(FoundationContractCard.PROMPT_KEY).contains("class User");
    }

    @Test
    void returnsEmptyWhenAbsent() {
        assertThat(FoundationContractCard.frontend(workspace)).isEmpty();
        assertThat(FoundationContractCard.backend(workspace)).isEmpty();
        assertThat(FoundationContractCard.frontendSection(workspace)).isEmpty();
        assertThat(FoundationContractCard.backendSection(workspace)).isEmpty();
    }

    @Test
    void returnsEmptyWhenFileBlank() throws IOException {
        Files.createDirectories(workspace.resolve("frontend"));
        Files.writeString(workspace.resolve("frontend/FOUNDATION_CONTRACT.md"), "   \n  \n");
        assertThat(FoundationContractCard.frontend(workspace)).isEmpty();
        assertThat(FoundationContractCard.frontendSection(workspace)).isEmpty();
    }

    @Test
    void stagesAreIndependent() throws IOException {
        Files.createDirectories(workspace.resolve("frontend"));
        Files.writeString(workspace.resolve("frontend/FOUNDATION_CONTRACT.md"), "FRONTEND-ONLY");
        assertThat(FoundationContractCard.frontend(workspace)).isEqualTo("FRONTEND-ONLY");
        assertThat(FoundationContractCard.backend(workspace)).isEmpty();
    }
}
