package com.business.discovery.worker.service.llm.generator;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.BriefContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Abstract base for all LLM code-generation operations.
 * Subclasses implement only callLlm(); prompt engineering lives here.
 * Swap providers by changing worker.llm.provider env var.
 */
@Slf4j
public abstract class LlmGeneratorService {

    protected static final int MAX_TOKENS = 8192;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // ── Public API called by nodes ────────────────────────────────────────

    public ArchitectureSpec generateArchitectureSpec(BriefContext brief, String slug) {
        boolean isUpdate = brief.requestedChanges() != null && !brief.requestedChanges().isBlank();

        String system = """
                You are a senior software architect creating a machine-readable project blueprint.
                This JSON is the authoritative spec — every subsequent code generation call receives it as context.
                Be specific: exact field names, parameter types, endpoint paths, return types.
                If two files must integrate (e.g. MenuService used by MenuController), their specs must be consistent.
                Return ONLY valid JSON matching the schema exactly. No markdown fences, no explanation, no preamble.
                """;

        String raw = callLlm(system, buildArchSpecPrompt(brief, slug, isUpdate));
        String json = stripMarkdown(raw);

        try {
            return objectMapper.readValue(json, ArchitectureSpec.class);
        } catch (Exception e) {
            throw new WorkerException(FailureType.CODE,
                    "ARCHITECTURE.json parsing failed: " + e.getMessage(), e);
        }
    }

    public String generateFileContent(String filePath,
                                      String fileDescription,
                                      BriefContext brief,
                                      List<String> existingFilePaths,
                                      String existingContent,
                                      Map<String, String> codebaseContext) {
        boolean isUpdate = existingContent != null;

        String system = isUpdate ? """
                You are a senior Java Spring Boot and React developer updating an existing file.
                Return ONLY the updated file content — no markdown code fences, no explanation, no preamble.
                Preserve all logic that is not affected by the requested changes.
                All models and SQL queries belong in the backend. Frontend calls backend REST APIs only.
                Match naming conventions, import styles, and patterns from the existing codebase.
                """ : """
                You are a senior Java Spring Boot and React developer generating production-quality code.
                Return ONLY the file content — no markdown code fences, no explanation, no preamble.
                All models and SQL queries belong in the backend. Frontend calls backend REST APIs only.
                Match naming conventions, import styles, and patterns from the existing codebase.
                """;

        return stripMarkdown(callLlm(system,
                buildFileContentPrompt(filePath, fileDescription, brief,
                        existingFilePaths, existingContent, codebaseContext)));
    }

    public String fixFileContent(String filePath,
                                 String currentContent,
                                 String compilerError,
                                 String tavilySearchResult,
                                 Map<String, String> codebaseContext) {
        String system = """
                You are a senior Java developer fixing a compilation error.
                Return ONLY the corrected file content — no markdown code fences, no explanation.
                Use the existing codebase context to resolve symbol references, import paths, and dependencies.
                """;

        String codebaseSection = formatCodebaseSection(codebaseContext, filePath);

        String user = String.format("""
                File: %s

                Current content:
                %s

                Compilation / build error:
                %s

                Relevant search result:
                %s
                %s
                Return the fully corrected file content.
                """, filePath, currentContent, compilerError, tavilySearchResult, codebaseSection);

        return stripMarkdown(callLlm(system, user));
    }

    // ── Subclass contract ─────────────────────────────────────────────────

    protected abstract String callLlm(String systemPrompt, String userPrompt);

    // ── Private prompt builders ───────────────────────────────────────────

    private String buildArchSpecPrompt(BriefContext b, String slug, boolean isUpdate) {
        String changesSection = isUpdate
                ? "\nClient-requested changes:\n" + b.requestedChanges()
                  + (b.projectHistory() != null ? "\n\nPrevious project state:\n" + b.projectHistory() : "")
                : "";

        String manifestList = b.mustHaveFeatures().stream()
                .map(f -> "- " + f).collect(Collectors.joining("\n"));

        return """
                Business: %s
                Category: %s | Location: %s
                Base Java package: com.%s
                Tech stack: %s
                Must-have features:
                %s
                Nice-to-have features: %s
                Architectural notes: %s
                SEO keywords: %s
                Design direction: %s | Color: %s | Tone: %s
                %s

                Generate ARCHITECTURE.json following this schema exactly:
                {
                  "generated_at": "<ISO-8601 timestamp>",
                  "business_name": "<business name>",
                  "base_package": "com.%s",
                  "project_dependencies": {
                    "spring_boot_starters": ["web", "data-jpa", "postgresql", "lombok", "validation", "actuator", "<add security/mail/etc. if features require>"],
                    "npm_packages": ["@tanstack/react-query", "react-hook-form", "zod", "axios", "react-router-dom", "<add extras if features require>"]
                  },
                  "files": [
                    {
                      "file_name": "<FileName.java or Component.tsx — filename only>",
                      "file_path": "<relative path from project root>",
                      "file_type": "<BACKEND|FRONTEND|INFRA|CONFIG>",
                      "layer": "<MODEL|REPOSITORY|SERVICE|CONTROLLER|DTO|EXCEPTION|CONFIG|PAGE|COMPONENT|HOOK|CONTEXT|UTIL|INFRA>",
                      "created_date": "<YYYY-MM-DD>",
                      "updated_date": "<YYYY-MM-DD>",
                      "status": "PLANNED",
                      "description": "<one sentence — what this file does>",
                      "public_functions": [
                        { "name": "<method or component name>", "parameters": ["<Type paramName>"], "return_type": "<Type>", "description": "<what it does>" }
                      ],
                      "public_variables": [
                        { "name": "<fieldName>", "type": "<Java or TS type>", "description": "<purpose + constraints e.g. not null, max 100>" }
                      ],
                      "api_endpoints": [
                        { "method": "GET|POST|PUT|DELETE", "path": "/api/...", "request_body": "<JSON shape or null>", "response_body": "<type>", "description": "<...>" }
                      ],
                      "api_endpoints_consumed": [
                        { "method": "GET|POST", "path": "/api/...", "description": "<...>" }
                      ],
                      "imports_from": ["<OtherFile.java or Component.tsx — filenames only>"],
                      "depends_on": ["<OtherFile.java — filenames only>"]
                    }
                  ]
                }

                Layer-specific rules:
                - MODEL: public_variables = ALL @Column fields with Java type + constraints; api_endpoints = null; api_endpoints_consumed = null
                - REPOSITORY: public_functions = ALL custom query methods with exact signatures; api_endpoints = null
                - SERVICE: public_functions = ALL public methods using Request/Response DTOs (not entities); api_endpoints = null
                - CONTROLLER: api_endpoints = FULL endpoint list; public_functions = handler method signatures
                - DTO: public_variables = ALL fields with Java type + Bean Validation annotations; api_endpoints = null
                - EXCEPTION: minimal public_functions; api_endpoints = null
                - PAGE (frontend): api_endpoints_consumed = backend endpoints this page calls; api_endpoints = null
                - COMPONENT (frontend): public_functions = component name with props type; api_endpoints = null; api_endpoints_consumed = null
                - HOOK (frontend): api_endpoints_consumed = backend endpoints this hook calls; public_functions = the hook signature

                ALWAYS include these files regardless of business type:
                - backend/src/main/java/com/%s/exception/GlobalExceptionHandler.java (layer: EXCEPTION)
                - backend/src/main/java/com/%s/dto/ErrorResponse.java (layer: DTO)
                - frontend/src/App.tsx (layer: PAGE) — root component with React Router routes
                - frontend/src/api/client.ts (layer: UTIL) — Axios singleton with base URL from env

                Do NOT include these — they are generated by CLI scaffold tools:
                - pom.xml, mvnw, mvnw.cmd (Spring Initializr)
                - Application.java (Spring Initializr generates this)
                - package.json, tsconfig.json, vite.config.ts, index.html, main.tsx (Vite scaffold)
                - Dockerfile, docker-compose.yml, application.properties, .env.example
                """.formatted(
                        b.businessName(), b.category(), b.location(), slug,
                        b.techStack(), manifestList, b.niceToHaveFeatures(),
                        b.architecturalNotes(), b.seoKeywords(),
                        b.designDirection(), b.colorScheme(), b.tone(),
                        changesSection,
                        slug, slug, slug);
    }

    private String buildFileContentPrompt(String filePath,
                                          String description,
                                          BriefContext b,
                                          List<String> existingPaths,
                                          String existingContent,
                                          Map<String, String> codebaseContext) {
        String changesSection = (b.requestedChanges() != null && !b.requestedChanges().isBlank())
                ? "\nClient-requested changes (MUST be applied in this file where applicable):\n"
                        + b.requestedChanges() + "\n"
                : "";
        String historySection = (b.projectHistory() != null && !b.projectHistory().isBlank())
                ? "\nPROJECT_HISTORY:\n" + b.projectHistory() + "\n"
                : "";
        String existingSection = (existingContent != null)
                ? "\nEXISTING FILE CONTENT (update this — preserve logic not touched by requested changes):\n"
                        + existingContent + "\n"
                : "";
        String codebaseSection = formatCodebaseSection(codebaseContext, filePath);

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
                %s%s%s%s
                All files in project (do NOT re-generate these unless listed above):
                %s
                """,
                filePath, description,
                b.businessName(), b.category(), b.location(),
                b.websiteType(), b.mustHaveFeatures(), b.techStack(),
                b.designDirection(), b.colorScheme(), b.tone(),
                b.architecturalNotes(),
                changesSection, historySection, existingSection, codebaseSection,
                String.join("\n", existingPaths));
    }

    private String formatCodebaseSection(Map<String, String> codebaseContext, String currentFilePath) {
        if (codebaseContext == null || codebaseContext.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\nEXISTING CODEBASE " +
                "(read for consistency — match naming, imports, and patterns):\n");
        for (Map.Entry<String, String> entry : codebaseContext.entrySet()) {
            if (!entry.getKey().equals(currentFilePath)) {
                sb.append("\n--- ").append(entry.getKey()).append(" ---\n");
                sb.append(entry.getValue()).append("\n");
            }
        }
        return sb.toString();
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

}
