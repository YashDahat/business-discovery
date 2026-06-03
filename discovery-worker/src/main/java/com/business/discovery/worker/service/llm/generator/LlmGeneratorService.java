package com.business.discovery.worker.service.llm.generator;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.BriefContext;
import com.business.discovery.worker.util.PromptLoader;
import com.business.discovery.worker.util.PromptTemplate;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Abstract base for all LLM code-generation operations.
 * Subclasses implement only callLlm(); prompt engineering lives in prompts/.
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

        String system = PromptLoader.load("system/arch_spec.txt");
        String user   = buildArchSpecUserPrompt(brief, slug, isUpdate);

        String json = stripMarkdown(callLlm(system, user));
        try {
            return objectMapper.readValue(json, ArchitectureSpec.class);
        } catch (Exception e) {
            throw new WorkerException(FailureType.CODE,
                    "ARCHITECTURE.json parsing failed: " + e.getMessage(), e);
        }
    }

    public String generateFileContent(String filePath,
                                      String fileDescription,
                                      String layer,
                                      BriefContext brief,
                                      List<String> allFilePaths,
                                      String existingContent,
                                      String architectureSpec,
                                      Map<String, String> dependencyFiles) {
        boolean isUpdate = existingContent != null;

        String system = PromptLoader.load(isUpdate
                ? "system/file_update.txt"
                : "system/file_generate.txt");

        String user = buildFileContentUserPrompt(
                filePath, fileDescription, layer, brief,
                allFilePaths, existingContent, architectureSpec, dependencyFiles);

        return stripMarkdown(callLlm(system, user));
    }

    public String fixFileContent(String filePath,
                                 String currentContent,
                                 String compilerError,
                                 Map<String, String> codebaseContext) {
        String system = PromptLoader.load("system/fix_file.txt");

        String user = PromptTemplate.from(PromptLoader.load("user/fix_file.txt"))
                .with("filePath",        filePath)
                .with("currentContent",  currentContent)
                .with("compilerError",   compilerError)
                .with("codebaseSection", formatFilesSection(codebaseContext, filePath))
                .render();

        return stripMarkdown(callLlm(system, user));
    }

    // ── Subclass contract ─────────────────────────────────────────────────

    protected abstract String callLlm(String systemPrompt, String userPrompt);

    // ── User prompt builders ──────────────────────────────────────────────

    private String buildArchSpecUserPrompt(BriefContext b, String slug, boolean isUpdate) {
        String changesSection = isUpdate
                ? "\nClient-requested changes:\n" + b.requestedChanges()
                  + (b.projectHistory() != null
                        ? "\n\nPrevious project state:\n" + b.projectHistory()
                        : "")
                : "";

        String mustHaveList = b.mustHaveFeatures().stream()
                .map(f -> "- " + f)
                .collect(Collectors.joining("\n"));

        return PromptTemplate.from(PromptLoader.load("user/arch_spec.txt"))
                .with("businessName",      b.businessName())
                .with("category",          b.category())
                .with("location",          b.location())
                .with("slug",              slug)
                .with("techStack",         b.techStack().toString())
                .with("mustHaveFeatures",  mustHaveList)
                .with("niceToHaveFeatures", b.niceToHaveFeatures().toString())
                .with("architecturalNotes", b.architecturalNotes())
                .with("seoKeywords",       b.seoKeywords().toString())
                .with("designDirection",   b.designDirection())
                .with("colorScheme",       b.colorScheme())
                .with("tone",              b.tone())
                .with("changesSection",    changesSection)
                .render();
    }

    private String buildFileContentUserPrompt(String filePath,
                                              String description,
                                              String layer,
                                              BriefContext b,
                                              List<String> allFilePaths,
                                              String existingContent,
                                              String architectureSpec,
                                              Map<String, String> dependencyFiles) {
        String changesSection = (b.requestedChanges() != null && !b.requestedChanges().isBlank())
                ? "\nClient-requested changes (MUST be applied in this file where applicable):\n"
                  + b.requestedChanges() + "\n"
                : "";
        String historySection = (b.projectHistory() != null && !b.projectHistory().isBlank())
                ? "\n== PROJECT HISTORY (previous attempts — avoid repeating the same mistakes) ==\n"
                  + b.projectHistory() + "\n"
                : "";
        String existingSection = existingContent != null
                ? "\n== EXISTING FILE CONTENT (update — preserve logic not touched by the changes) ==\n"
                  + existingContent + "\n"
                : "";

        String mustHaveList = b.mustHaveFeatures().stream()
                .map(f -> "- " + f)
                .collect(Collectors.joining("\n"));

        return PromptTemplate.from(PromptLoader.load("user/file_content.txt"))
                .with("filePath",           filePath)
                .with("layer",              layer)
                .with("description",        description)
                .with("businessName",       b.businessName())
                .with("category",           b.category())
                .with("location",           b.location())
                .with("websiteType",        b.websiteType())
                .with("mustHaveFeatures",   mustHaveList)
                .with("techStack",          b.techStack().toString())
                .with("designDirection",    b.designDirection())
                .with("colorScheme",        b.colorScheme())
                .with("tone",               b.tone())
                .with("architecturalNotes", b.architecturalNotes())
                .with("changesSection",     changesSection)
                .with("architectureSpec",   architectureSpec != null ? architectureSpec : "")
                .with("historySection",     historySection)
                .with("dependencySection",  formatFilesSection(dependencyFiles, filePath))
                .with("existingSection",    existingSection)
                .with("allFilePaths",       String.join("\n", allFilePaths))
                .render();
    }

    // ── Shared helpers ────────────────────────────────────────────────────

    private String formatFilesSection(Map<String, String> codebaseContext, String currentFilePath) {
        if (codebaseContext == null || codebaseContext.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
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
