package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.model.ArchitectBrief;
import com.business.discovery.worker.model.BusinessEntity;
import com.business.discovery.worker.service.llm.FileEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Component
@Order(8)
@Slf4j
public class DocumentationGeneratorNode implements WorkerNode {

    @Override
    public void execute(WorkerContext ctx) {
        try {
            writeReadme(ctx);
            writeProjectHistory(ctx);
        } catch (IOException e) {
            throw new WorkerException(FailureType.INFRA,
                    "Failed to write documentation: " + e.getMessage(), e);
        }

        log.info("[DocumentationGeneratorNode] Documentation written for '{}' attempt {}",
                ctx.getBusiness().getTitle(), ctx.getAttemptNumber());
    }

    private void writeReadme(WorkerContext ctx) throws IOException {
        ArchitectBrief brief = ctx.getBrief();
        BusinessEntity business = ctx.getBusiness();

        String techStack = brief.getRecommendedTechStack() != null
                ? brief.getRecommendedTechStack().entrySet().stream()
                        .map(e -> "- **" + e.getKey() + "**: " + e.getValue())
                        .collect(Collectors.joining("\n"))
                : "- Backend: Spring Boot 3\n- Frontend: React 19\n- Database: PostgreSQL";

        String features = brief.getMustHaveFeatures() != null && !brief.getMustHaveFeatures().isEmpty()
                ? brief.getMustHaveFeatures().stream().map(f -> "- " + f).collect(Collectors.joining("\n"))
                : "- Core business website features";

        String content = """
                # %s

                Auto-generated website for %s — %s, %s.

                ## Tech Stack

                %s

                ## Features

                %s

                ## Running Locally

                ```bash
                docker-compose up --build
                ```

                The app will be available at http://localhost:8080

                ## Development

                **Backend:**
                ```bash
                cd backend && mvn spring-boot:run
                ```

                **Frontend:**
                ```bash
                cd frontend && npm install && npm run dev
                ```
                """.formatted(
                business.getTitle(),
                business.getTitle(),
                nullSafe(business.getCategory(), "Local Business"),
                nullSafe(brief.getLocation(), "India"),
                techStack,
                features);

        Files.writeString(ctx.getWorkspaceDir().resolve("README.md"), content);
    }

    private void writeProjectHistory(WorkerContext ctx) throws IOException {
        Path historyPath = ctx.getWorkspaceDir().resolve("docs/PROJECT_HISTORY.md");
        Files.createDirectories(historyPath.getParent());

        ArchitectBrief brief = ctx.getBrief();
        BusinessEntity business = ctx.getBusiness();

        List<String> generatedPaths = ctx.getFileManifest().stream()
                .map(FileEntry::path)
                .toList();

        String featureList = brief.getMustHaveFeatures() != null && !brief.getMustHaveFeatures().isEmpty()
                ? brief.getMustHaveFeatures().stream().map(f -> "- " + f).collect(Collectors.joining("\n"))
                : "- Standard features";

        String fileList = generatedPaths.stream()
                .map(p -> "- " + p)
                .collect(Collectors.joining("\n"));

        String section = """
                ## Attempt %d — %s

                **Business:** %s
                **Category:** %s
                **Website Type:** %s

                **Must-Have Features:**
                %s

                **Generated Files (%d):**
                %s

                ---
                """.formatted(
                ctx.getAttemptNumber(),
                LocalDate.now(),
                business.getTitle(),
                nullSafe(brief.getBusinessCategory(), nullSafe(business.getCategory(), "General")),
                brief.getWebsiteType() != null ? brief.getWebsiteType().name() : "INFORMATIONAL",
                featureList,
                generatedPaths.size(),
                fileList);

        if (Files.exists(historyPath)) {
            String existing = Files.readString(historyPath);
            Files.writeString(historyPath, existing + "\n" + section);
        } else {
            String header = """
                    # Project History

                    This file tracks each generation attempt for this project.

                    """;
            Files.writeString(historyPath, header + section);
        }
    }

    private String nullSafe(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }
}
