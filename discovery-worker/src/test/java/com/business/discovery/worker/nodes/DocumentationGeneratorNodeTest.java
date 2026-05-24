package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.model.ArchitectBrief;
import com.business.discovery.worker.model.BusinessEntity;
import com.business.discovery.worker.service.llm.FileEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentationGeneratorNodeTest {

    private final DocumentationGeneratorNode node = new DocumentationGeneratorNode();

    @TempDir
    Path tempDir;

    @Test
    void readme_containsBusinessNameAndFeatures() throws Exception {
        WorkerContext ctx = buildContext("Shree Cafe", 1, false);

        node.execute(ctx);

        String readme = Files.readString(tempDir.resolve("README.md"));
        assertThat(readme).contains("Shree Cafe");
        assertThat(readme).contains("Restaurant");
        assertThat(readme).contains("Pune");
        assertThat(readme).contains("Online Menu");
        assertThat(readme).contains("docker-compose up --build");
    }

    @Test
    void projectHistory_hasAttempt1Section() throws Exception {
        WorkerContext ctx = buildContext("Pizza Hub", 1, false);

        node.execute(ctx);

        String history = Files.readString(tempDir.resolve("docs/PROJECT_HISTORY.md"));
        assertThat(history).contains("## Attempt 1");
        assertThat(history).contains("Pizza Hub");
        assertThat(history).contains("INFORMATIONAL");
        assertThat(history).contains("# Project History");
    }

    @Test
    void projectHistory_attempt2AppendsToExisting() throws Exception {
        // First run — attempt 1
        WorkerContext ctx1 = buildContext("Coffee Corner", 1, false);
        node.execute(ctx1);

        // Second run — attempt 2, existing history file already present
        WorkerContext ctx2 = buildContext("Coffee Corner", 2, true);
        node.execute(ctx2);

        String history = Files.readString(tempDir.resolve("docs/PROJECT_HISTORY.md"));
        assertThat(history).contains("## Attempt 1");
        assertThat(history).contains("## Attempt 2");
        // Header appears only once
        assertThat(history.indexOf("# Project History"))
                .isEqualTo(history.lastIndexOf("# Project History"));
    }

    @Test
    void generatedFilesListedInHistory() throws Exception {
        WorkerContext ctx = buildContext("Tandoor Palace", 1, false);

        node.execute(ctx);

        String history = Files.readString(tempDir.resolve("docs/PROJECT_HISTORY.md"));
        assertThat(history).contains("backend/src/main/java/com/test/model/Menu.java");
        assertThat(history).contains("frontend/src/App.tsx");
        assertThat(history).contains("Generated Files (2)");
    }

    private WorkerContext buildContext(String businessName, int attemptNumber, boolean reuseWorkspace) {
        WorkerContext ctx = mock(WorkerContext.class);

        BusinessEntity business = BusinessEntity.builder()
                .title(businessName)
                .category("Restaurant")
                .build();

        ArchitectBrief brief = ArchitectBrief.builder()
                .businessCategory("Restaurant")
                .location("Pune")
                .websiteType(ArchitectBrief.WebsiteType.INFORMATIONAL)
                .mustHaveFeatures(List.of("Online Menu", "Contact Form"))
                .recommendedTechStack(Map.of("frontend", "React 19", "backend", "Spring Boot 3"))
                .build();

        List<FileEntry> manifest = List.of(
                new FileEntry("backend/src/main/java/com/test/model/Menu.java", FileType.BACKEND, "Menu entity"),
                new FileEntry("frontend/src/App.tsx", FileType.FRONTEND, "Root component")
        );

        when(ctx.getBusiness()).thenReturn(business);
        when(ctx.getBrief()).thenReturn(brief);
        when(ctx.getFileManifest()).thenReturn(manifest);
        when(ctx.getAttemptNumber()).thenReturn(attemptNumber);
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);

        return ctx;
    }
}
