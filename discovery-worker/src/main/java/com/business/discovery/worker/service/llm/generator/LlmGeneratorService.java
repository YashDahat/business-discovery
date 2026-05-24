package com.business.discovery.worker.service.llm.generator;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.service.llm.BriefContext;
import com.business.discovery.worker.service.llm.FileEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Abstract base for all LLM code-generation operations.
 * Subclasses implement only callLlm(); prompt engineering lives here.
 * Swap providers by changing worker.llm.provider env var.
 */
@Slf4j
public abstract class LlmGeneratorService {

    protected static final int MAX_TOKENS = 8192;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Public API called by nodes ────────────────────────────────────────

    public List<FileEntry> generateFileManifest(BriefContext brief) {
        String system = """
                You are a senior software architect planning a website for a local Indian business.
                Given the ArchitectBrief, produce ONLY a JSON array of files to generate.
                Each object must have exactly three fields:
                  "path"        — relative path inside the project (e.g. backend/src/main/java/.../model/Product.java)
                  "type"        — one of BACKEND | FRONTEND | INFRA | CONFIG
                  "description" — one sentence describing the file's purpose

                Rules:
                - ALL JPA entities         → backend/src/main/java/.../model/
                - ALL repositories + SQL   → backend/src/main/java/.../repository/
                - ALL services             → backend/src/main/java/.../service/
                - ALL controllers (REST)   → backend/src/main/java/.../controller/
                - Frontend calls backend REST APIs only — no direct DB access
                - React/TypeScript files   → frontend/src/
                - Return ONLY the JSON array. No markdown, no explanation.
                """;

        String raw = callLlm(system, buildManifestPrompt(brief));
        String json = stripMarkdown(raw);

        try {
            List<FileEntryRaw> rawEntries = objectMapper.readValue(
                    json, new TypeReference<List<FileEntryRaw>>() {});
            return rawEntries.stream()
                    .map(e -> new FileEntry(e.path(), FileType.valueOf(e.type().toUpperCase()), e.description()))
                    .toList();
        } catch (Exception e) {
            throw new WorkerException(FailureType.CODE,
                    "File manifest JSON parsing failed: " + e.getMessage(), e);
        }
    }

    public String generateFileContent(String filePath,
                                      String fileDescription,
                                      BriefContext brief,
                                      List<String> existingFilePaths) {
        String system = """
                You are a senior Java Spring Boot and React developer generating production-quality code.
                Return ONLY the file content — no markdown code fences, no explanation, no preamble.
                All models and SQL queries belong in the backend. Frontend calls backend REST APIs only.
                """;

        return stripMarkdown(callLlm(system, buildFileContentPrompt(filePath, fileDescription, brief, existingFilePaths)));
    }

    public String fixFileContent(String filePath,
                                 String currentContent,
                                 String compilerError,
                                 String tavilySearchResult) {
        String system = """
                You are a senior Java developer fixing a compilation error.
                Return ONLY the corrected file content — no markdown code fences, no explanation.
                """;

        String user = String.format("""
                File: %s

                Current content:
                %s

                Compilation / build error:
                %s

                Relevant search result:
                %s

                Return the fully corrected file content.
                """, filePath, currentContent, compilerError, tavilySearchResult);

        return stripMarkdown(callLlm(system, user));
    }

    // ── Subclass contract ─────────────────────────────────────────────────

    protected abstract String callLlm(String systemPrompt, String userPrompt);

    // ── Private prompt builders ───────────────────────────────────────────

    private String buildManifestPrompt(BriefContext b) {
        return String.format("""
                Business: %s
                Category: %s
                Location: %s
                Website type: %s
                Must-have features: %s
                Nice-to-have features: %s
                Tech stack: %s
                SEO keywords: %s
                Design direction: %s
                Competitor insights: %s
                """,
                b.businessName(), b.category(), b.location(), b.websiteType(),
                b.mustHaveFeatures(), b.niceToHaveFeatures(),
                b.techStack(), b.seoKeywords(), b.designDirection(),
                b.competitorInsights());
    }

    private String buildFileContentPrompt(String filePath,
                                          String description,
                                          BriefContext b,
                                          List<String> existing) {
        return String.format("""
                File to generate: %s
                Description: %s

                Business context:
                  Name: %s | Category: %s | Location: %s
                  Website type: %s
                  Must-have features: %s
                  Tech stack: %s
                  Design direction: %s | Color: %s | Tone: %s
                  Architectural notes: %s

                Already generated files (do NOT re-generate these):
                %s
                """,
                filePath, description,
                b.businessName(), b.category(), b.location(),
                b.websiteType(), b.mustHaveFeatures(), b.techStack(),
                b.designDirection(), b.colorScheme(), b.tone(),
                b.architecturalNotes(),
                String.join("\n", existing));
    }

    private String stripMarkdown(String raw) {
        if (raw == null) return "";
        String trimmed = raw.strip();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```[a-zA-Z]*\\n?", "");
            int end = trimmed.lastIndexOf("```");
            if (end >= 0) trimmed = trimmed.substring(0, end).strip();
        }
        return trimmed;
    }

    private record FileEntryRaw(String path, String type, String description) {}
}
