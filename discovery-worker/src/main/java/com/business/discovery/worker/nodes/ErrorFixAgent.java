package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.model.GeneratedFile;
import com.business.discovery.worker.repository.GeneratedFileRepository;
import com.business.discovery.worker.service.BuildToolService;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.business.discovery.worker.util.ArchitectureJsonUtil;
import com.business.discovery.worker.util.WorkspaceReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Agentic fix loop for compilation errors — replaces the single-file ErrorFixNode
 * pattern used by ValidationNodes.
 *
 * The LLM drives the loop: it calls tools (run_compiler, read_file, write_file,
 * search_symbol, read_architecture_spec, list_files) to investigate root causes,
 * patch source files, and verify via the compiler. Java code only executes what
 * the LLM requests — it does not decide which files to fix.
 */
@Component
@Slf4j
public class ErrorFixAgent {

    static final int MAX_TOOL_ROUNDS = 10;

    private static final String SYSTEM_PROMPT = """
            You are a senior engineer debugging multi-file compilation failures in a generated project.

            You have 6 tools:
            - run_compiler           — run the compiler and get ALL current errors
            - read_file              — read any file's current content
            - write_file             — overwrite a file with the complete fixed content
            - search_symbol          — grep for a class/type/function across the project
            - read_architecture_spec — get the spec (contract) for any file from ARCHITECTURE.json
            - list_files             — list all files in a workspace directory

            ENVIRONMENT (read once, do not investigate further):
            - Docker container: eclipse-temurin:17-jdk-jammy — Java 17 is installed and working.
            - Maven: invoked via ./mvnw wrapper inside backend/. No need to locate javac or mvn binaries.
            - All project files are inside the workspace. list_files and read_file are restricted to it.
            - NEVER explore system paths: /usr, /opt, /etc, /bin, /lib, /jvm — they have no project files.
            - If a Maven dependency is missing, fix backend/pom.xml — do not search for local JARs.
            - If a class fails to compile, it is a code issue — not a JDK installation problem.

            STRATEGY — always follow this order:
            1. Call run_compiler first to collect ALL current errors.
            2. For each error, classify it:
               - CROSS-FILE: "cannot find symbol X", "Module Y has no exported member X",
                 "Cannot find module X" — the fix belongs in the SOURCE file (add the export/type).
               - SELF-CONTAINED: syntax error, wrong return type, bad import — fix the file itself.
            3. For CROSS-FILE errors:
               a. Call search_symbol to find where X is defined (or confirm it is missing).
               b. Call read_architecture_spec on the source file to see what it must export.
               c. Call read_file on the source file to see its current state.
               d. Call write_file to add the missing export/type to the SOURCE file first,
                  not to the importing file.
            4. For SELF-CONTAINED errors:
               a. Call read_file on the failing file.
               b. Call write_file with the complete corrected file.
            5. After patching, call run_compiler again to verify.
            6. If new errors remain, investigate and fix them the same way.
            7. Stop calling tools when: compiler passes, or you have exhausted every fix you can apply.

            RULES:
            - Always fix root causes before symptoms — patch the source file first.
            - Never inline a type that should be imported — add it to the source file instead.
            - Do not invent new abstractions or files not in ARCHITECTURE.json.
            - write_file requires the COMPLETE file content (not a diff). No markdown fences.
            - Use list_files only within project directories (backend/, frontend/) — never system paths.
            """;

    private final LlmGeneratorService proLlm;
    private final BuildToolService buildTool;
    private final GeneratedFileRepository fileRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ObjectMapper specMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    public ErrorFixAgent(@Qualifier("claudeSonnet") LlmGeneratorService proLlm,
                         BuildToolService buildTool,
                         GeneratedFileRepository fileRepo) {
        this.proLlm = proLlm;
        this.buildTool = buildTool;
        this.fileRepo = fileRepo;
    }

    /**
     * Runs the agentic fix loop for the given file type (BACKEND or FRONTEND).
     * Returns true if compilation passes after the loop, false otherwise.
     */
    public boolean fix(FileType fileType, WorkerContext ctx) {
        Path workspace = ctx.getWorkspaceDir();
        Path compileDir = workspace.resolve(fileType == FileType.BACKEND ? "backend" : "frontend");
        WorkspaceReader reader = new WorkspaceReader(workspace);

        List<ToolSpecification> tools = buildToolSpecs();

        String trigger = "Begin: call run_compiler(\"" + (fileType == FileType.BACKEND ? "backend" : "frontend")
                + "\") to see current errors, then investigate and fix them.";

        BuildToolService.BuildResult preCheck = (fileType == FileType.BACKEND)
                ? buildTool.runMvnCompile(compileDir)
                : buildTool.runTscCheck(compileDir);
        if (!preCheck.success()) {
            String preview = preCheck.output();
            if (preview.length() > 3000) preview = preview.substring(0, 3000) + "\n[...truncated]";
            log.warn("[ErrorFixAgent] {} errors before fix loop:\n{}", fileType, preview);
        }

        log.info("[ErrorFixAgent] Starting fix loop for {} — max {} tool rounds", fileType, MAX_TOOL_ROUNDS);

        proLlm.runFixAgentLoop(SYSTEM_PROMPT, trigger, tools,
                req -> executeToolCall(req, fileType, workspace, compileDir, reader, ctx),
                MAX_TOOL_ROUNDS);

        BuildToolService.BuildResult finalResult = (fileType == FileType.BACKEND)
                ? buildTool.runMvnCompile(compileDir)
                : buildTool.runTscCheck(compileDir);

        if (finalResult.success()) {
            log.info("[ErrorFixAgent] {} compilation passes after agent loop", fileType);
            return true;
        }
        String remainingErrors = finalResult.output();
        if (remainingErrors.length() > 3000) remainingErrors = remainingErrors.substring(0, 3000) + "\n[...truncated]";
        log.warn("[ErrorFixAgent] {} compilation still failing after agent loop:\n{}", fileType, remainingErrors);
        return false;
    }

    // ── Tool Dispatch ─────────────────────────────────────────────────────────

    private String executeToolCall(dev.langchain4j.agent.tool.ToolExecutionRequest req,
                                   FileType fileType, Path workspace, Path compileDir,
                                   WorkspaceReader reader, WorkerContext ctx) {
        try {
            JsonNode args = objectMapper.readTree(req.arguments() != null ? req.arguments() : "{}");
            return switch (req.name()) {
                case "run_compiler"           -> runCompiler(args.path("type").asText(), fileType, compileDir);
                case "read_file"              -> reader.readFile(args.path("path").asText());
                case "write_file"             -> writeFile(args.path("path").asText(), args.path("content").asText(), workspace, ctx);
                case "search_symbol"          -> searchSymbol(args.path("symbol").asText(), args.path("scope").asText("all"), workspace);
                case "read_architecture_spec" -> readArchSpec(args.path("path").asText(), workspace);
                case "list_files"             -> listFiles(args.path("directory").asText(), workspace);
                default -> "ERROR: unknown tool '" + req.name() + "'";
            };
        } catch (Exception e) {
            log.warn("[ErrorFixAgent] Tool execution error for {}: {}", req.name(), e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    // ── Tool Implementations ──────────────────────────────────────────────────

    private String runCompiler(String type, FileType defaultType, Path compileDir) {
        boolean isBackend = "backend".equalsIgnoreCase(type)
                || (type == null || type.isBlank()) && defaultType == FileType.BACKEND;

        BuildToolService.BuildResult result = isBackend
                ? buildTool.runMvnCompile(compileDir)
                : buildTool.runTscCheck(compileDir);

        if (result.success()) return "COMPILATION PASSED — no errors.";

        String output = result.output();
        return output.length() > 5000 ? output.substring(0, 5000) + "\n[...truncated]" : output;
    }

    private String writeFile(String relativePath, String content, Path workspace, WorkerContext ctx) {
        if (relativePath == null || relativePath.isBlank()) return "ERROR: path is required";
        if (content == null || content.isBlank()) return "ERROR: content is required";

        Path target = workspace.resolve(relativePath).normalize();
        if (!target.startsWith(workspace)) return "ERROR: path traversal not allowed: " + relativePath;

        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            updateDbRecord(relativePath, ctx);
            log.info("[ErrorFixAgent] Patched {} ({} chars)", relativePath, content.length());
            return "OK";
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String searchSymbol(String symbol, String scope, Path workspace) {
        if (symbol == null || symbol.isBlank()) return "ERROR: symbol is required";

        List<String> includes = switch (scope != null ? scope.toLowerCase() : "all") {
            case "backend"  -> List.of("--include=*.java");
            case "frontend" -> List.of("--include=*.ts", "--include=*.tsx");
            default         -> List.of("--include=*.java", "--include=*.ts", "--include=*.tsx");
        };

        try {
            List<String> cmd = new java.util.ArrayList<>(List.of("grep", "-r", "-n", "--", symbol));
            cmd.addAll(includes);
            cmd.add(workspace.toString());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();

            if (output.isBlank()) return "No occurrences of '" + symbol + "' found.";

            String cleaned = output.replace(workspace.toString() + "/", "");
            return cleaned.length() > 2000 ? cleaned.substring(0, 2000) + "\n[...truncated]" : cleaned;
        } catch (Exception e) {
            return "ERROR running search: " + e.getMessage();
        }
    }

    private String readArchSpec(String filePath, Path workspace) {
        if (filePath == null || filePath.isBlank()) return "ERROR: path is required";
        try {
            return ArchitectureJsonUtil.findByPath(workspace, filePath)
                    .map(spec -> {
                        try {
                            return specMapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec);
                        } catch (Exception e) {
                            return spec.toString();
                        }
                    })
                    .orElse("SPEC_NOT_FOUND: " + filePath);
        } catch (IOException e) {
            return "ERROR reading spec: " + e.getMessage();
        }
    }

    private String listFiles(String directory, Path workspace) {
        if (directory == null || directory.isBlank()) directory = ".";
        Path dir = workspace.resolve(directory).normalize();
        if (!dir.startsWith(workspace)) {
            log.warn("[ErrorFixAgent] Tool execution error for list_files: Path traversal attempt blocked: {}", directory);
            return "ERROR: list_files is restricted to the project workspace. Use relative paths like 'backend/src' or 'frontend/src/components'.";
        }
        if (!Files.exists(dir)) return "DIRECTORY_NOT_FOUND: " + directory;
        try {
            String result = Files.walk(dir)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String s = p.toString();
                        return !s.contains("node_modules") && !s.contains("/target/")
                                && !s.contains("/.git/") && !s.contains("/dist/")
                                && !s.contains("/.mvn/");
                    })
                    .sorted()
                    .limit(100)
                    .map(p -> workspace.relativize(p).toString())
                    .collect(Collectors.joining("\n"));
            return result.isBlank() ? "(empty directory)" : result;
        } catch (IOException e) {
            return "ERROR listing files: " + e.getMessage();
        }
    }

    // ── Tool Spec Builder ─────────────────────────────────────────────────────

    private List<ToolSpecification> buildToolSpecs() {
        return List.of(
                ToolSpecification.builder()
                        .name("run_compiler")
                        .description("Run the compiler and get all current errors. Call this first to see what's broken, and again after patching to verify fixes.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("type", "\"backend\" to run mvn compile, \"frontend\" to run tsc --noEmit")
                                .required(List.of("type"))
                                .build())
                        .build(),

                ToolSpecification.builder()
                        .name("read_file")
                        .description("Read the current content of a file in the workspace. Always read before patching.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("path", "Relative path from workspace root, e.g. backend/src/main/java/com/example/OrderService.java")
                                .required(List.of("path"))
                                .build())
                        .build(),

                ToolSpecification.builder()
                        .name("write_file")
                        .description("Overwrite a file with the complete fixed content. Write the entire file — not a diff. No markdown fences in the content.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("path", "Relative path from workspace root")
                                .addStringProperty("content", "Complete new file content — raw source code only")
                                .required(List.of("path", "content"))
                                .build())
                        .build(),

                ToolSpecification.builder()
                        .name("search_symbol")
                        .description("Search for a class name, function, or exported identifier across the project. Use when an error says 'cannot find symbol X' to find where X is (or should be) defined.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("symbol", "The identifier to search for, e.g. OrderResponse or RazorpayService")
                                .addStringProperty("scope", "\"backend\" (Java files only), \"frontend\" (TS/TSX only), or \"all\"")
                                .required(List.of("symbol"))
                                .build())
                        .build(),

                ToolSpecification.builder()
                        .name("read_architecture_spec")
                        .description("Get the specification for a specific file from ARCHITECTURE.json. Shows what the file must export, its dependencies, and its role — the CONTRACT it must fulfil.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("path", "Relative file path, e.g. frontend/src/types/order.ts")
                                .required(List.of("path"))
                                .build())
                        .build(),

                ToolSpecification.builder()
                        .name("list_files")
                        .description("List all source files in a workspace directory. Use relative paths within the project only (e.g. 'backend/src/main/java', 'frontend/src/components'). System directories (/usr, /opt, /etc, /bin) are blocked and will return an error.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("directory", "Relative directory path within the project workspace, e.g. backend/src/main/java/com/example or frontend/src/types")
                                .required(List.of("directory"))
                                .build())
                        .build()
        );
    }

    // ── DB Helper ─────────────────────────────────────────────────────────────

    private void updateDbRecord(String relPath, WorkerContext ctx) {
        fileRepo.findByTaskIdAndFilePath(ctx.getTaskId(), relPath).ifPresent(f -> {
            f.setStatus(GeneratedFile.FileStatus.VALIDATED);
            f.setErrorMessage(null);
            fileRepo.save(f);
        });
    }
}
