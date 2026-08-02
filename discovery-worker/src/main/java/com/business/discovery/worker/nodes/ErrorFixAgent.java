package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FileType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.model.GeneratedFile;
import com.business.discovery.worker.repository.GeneratedFileRepository;
import com.business.discovery.worker.service.BuildToolService;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import com.business.discovery.worker.util.ArchitectureJsonUtil;
import com.business.discovery.worker.util.LombokIntegrityGuard;
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
import com.business.discovery.worker.util.ApiContractCard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Agentic fix loop for compilation errors — replaces the single-file ErrorFixNode
 * pattern used by ValidationNodes.
 *
 * The LLM drives the loop: it calls tools (str_replace, write_file, read_file,
 * search_symbol, run_compiler, run_npm_install, read_architecture_spec, list_files)
 * to investigate root causes, patch source files, and verify. Java code only executes
 * what the LLM requests — it does not decide which files to fix.
 *
 * Design informed by the multifit-aundh post-mortem (2026-07-04): the old 10-round
 * budget was spent as 1 diagnose + 4 investigate + 5 blind patches + 0 verification.
 * Changes: 30 rounds (affordable via the moving cache breakpoint in the Claude loop),
 * edit-based patching (str_replace) instead of full-file rewrites, the pre-check
 * compiler output seeded into the trigger, automatic post-mutation compiles via the
 * post-round hook, and an npm install tool for missing-module errors.
 */
@Component
@Slf4j
public class ErrorFixAgent {

    static final int MAX_TOOL_ROUNDS = 30;

    private static final int SEEDED_ERRORS_MAX_CHARS = 6000;
    private static final int AUTO_VERIFY_MAX_CHARS = 4000;

    private static final Set<String> MUTATING_TOOLS =
            Set.of("write_file", "str_replace", "run_npm_install");

    // Framework packages that conflict with the platform stack — installing them "fixes"
    // the compile error while breaking the app (e.g. `next` for a next/navigation import).
    private static final Set<String> NPM_FRAMEWORK_BLACKLIST = Set.of(
            "next", "nuxt", "gatsby", "remix", "@remix-run/react", "svelte", "vue",
            "@angular/core", "express", "@nestjs/core");

    private static final String SYSTEM_PROMPT = """
            You are a senior engineer debugging multi-file compilation failures in a generated project.

            You have 8 tools:
            - str_replace            — PREFERRED for fixes: replace an exact text snippet in a file
            - write_file             — for NEW files or unavoidable full rewrites only
            - read_file              — read any file's current content
            - search_symbol          — grep for a class/type/function across the project
            - run_compiler           — run the compiler and get ALL current errors
            - run_npm_install        — install a missing npm package (frontend only)
            - read_architecture_spec — get the spec (contract) for any file from ARCHITECTURE.json
            - list_files             — list all files in a workspace directory

            ENVIRONMENT (read once, do not investigate further):
            - Docker container: eclipse-temurin:17-jdk-jammy — Java 17 is installed and working.
            - Maven: invoked via ./mvnw wrapper inside backend/. No need to locate javac or mvn binaries.
            - All project files are inside the workspace. list_files and read_file are restricted to it.
            - NEVER explore system paths: /usr, /opt, /etc, /bin, /lib, /jvm — they have no project files.
            - If a Maven dependency is missing, fix backend/pom.xml — mvn re-resolves on compile.
            - If an npm module is missing and it's a legitimate library, use run_npm_install.
            - If a class fails to compile, it is a code issue — not a JDK installation problem.

            AUTOMATIC VERIFICATION: after every response in which you modify anything
            (str_replace / write_file / run_npm_install), the harness automatically runs the
            compiler and appends the result. DO NOT call run_compiler after making changes —
            you will get the result for free. Only call run_compiler when you need a fresh
            error list without having just changed something.

            The current compiler errors are provided in the first message — do not re-run
            the compiler to discover them.

            YOUR ROUND BUDGET IS LIMITED — BATCH AGGRESSIVELY:
            Each of your responses costs one round regardless of how many tool calls it
            contains, and you may make MANY tool calls in a single response. Never spend a
            round on a single read_file or a single str_replace:
            - Reconnaissance: ONE response that read_file's EVERY file appearing in the
              error list (plus the types files they import). Ten reads in one response
              costs one round; ten rounds of one read each wastes a third of your budget.
            - Fixing: apply EVERY confident str_replace in the same response — group all
              edits for all files you have already read. A response with 8 edits across
              4 files is normal and desired.

            STRATEGY:
            1. Group the provided errors by ROOT CAUSE, not by file. One missing export can
               produce ten downstream errors — fix the source, not the symptoms.
            2. For each root cause, classify:
               - CROSS-FILE: "cannot find symbol X", "Module Y has no exported member X",
                 "Cannot find module X" — the fix belongs in the SOURCE file (add the export/type)
                 or, for a missing npm library, run_npm_install.
               - SELF-CONTAINED: syntax error, wrong return type, bad import — fix the file itself.
            3. Investigate briskly: read only the files you intend to change plus the involved
               contract (read_architecture_spec) — in as few responses as possible. Do not
               re-read files you have already seen.
            4. Patch with str_replace: minimal old_string (with enough context to be unique)
               and minimal new_string. One str_replace per distinct defect, many per response.
               Use write_file only to CREATE a file that should exist but doesn't.
            5. React to the automatic verification after each change: fixed errors disappear,
               new ones mean your patch was wrong — revert or adjust before moving on.
            6. Stop calling tools when the automatic verification reports the compiler passes,
               or you have exhausted every fix you can apply.

            DERIVED API LAYER (frontend — read carefully):
            Files under frontend/src/types/ (except types/local/) and frontend/src/services/*Service.ts
            are DERIVED from the compiled backend. They are ground truth and are regenerated
            on every attempt — the harness REFUSES edits to them, and any workaround you find
            will be overwritten. When a page/hook disagrees with a derived type or service:
            - "Property 'x' does not exist on type 'YDto'" → rename x in the PAGE to the
              field the derived type actually declares (the error usually suggests it).
            - "'string | null' not assignable to 'string'" → add a null guard or fallback
              in the PAGE (e.g. value ?? '').
            - "Cannot find module '@/types/foo'" → the module was never derived; change the
              PAGE's import to a derived module that exports the needed type. NEVER create
              the missing types/services file.
            - Frontend-only types (UI state, view models) belong in frontend/src/types/local/.

            LOMBOK (backend — read carefully):
            DTOs and entities are annotated (@Data/@Builder) and Lombok synthesises their
            getters, setters and builders at compile time. A wall of "cannot find symbol:
            getX()/setX()" across SEVERAL annotated classes is ONE broken build, not N broken
            classes — annotation processing is not running. Fix the build (lombok present in
            backend/pom.xml AND in maven-compiler-plugin <annotationProcessorPaths>), never the
            classes. Deleting @Data/@Builder and hand-writing accessors DOES make it compile,
            and it silently corrupts the TypeScript types derived from those DTOs — the harness
            refuses those edits. To add or retype a FIELD, edit the field list and leave the
            annotations alone.

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
     * The derived wire contract, for FRONTEND fixes only.
     *
     * Without it the agent is blind to the real SDK: on vikram-s-fitness-studio it hit nine
     * TS2305 "no exported member" errors on bookingService, could not see that classeService
     * and scheduleService exported those functions with the correct paths, and so replaced
     * every call with a hand-written apiClient request against a path it invented from the DTO
     * name (FitnessClassDto -> /api/v1/admin/fitness-classes, which the backend never served).
     * The card was already on disk the whole time — it was simply never handed over.
     */
    private String buildContractSection(FileType fileType, Path workspace) {
        if (fileType != FileType.FRONTEND) return "";

        ApiContractCard card = ApiContractCard.build(workspace);
        if (card.isEmpty()) {
            log.warn("[ErrorFixAgent] No derived API contract on disk — fixing without wire ground truth");
            return "";
        }
        log.info("[ErrorFixAgent] Seeded contract card: {} type file(s), {} route(s)",
                card.typeFileCount(), card.routeCount());

        return """

                %s
                %s
                When an import fails because a module does not export a symbol, find the module
                above that DOES export it and re-point the import — splitting one import across
                several modules if that is where the functions live. NEVER replace the call with a
                hand-written apiClient/axios request, and NEVER infer an endpoint path from a type
                name. The routes above are the only ones the backend serves; a path absent from
                them 404s at runtime even though it compiles.
                """.formatted(ApiContractCard.PROMPT_KEY, card.toPromptSection());
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

        // Seed the trigger with the actual error list — the old trigger made the model
        // spend its first round rediscovering errors we had already collected.
        BuildToolService.BuildResult preCheck = check(fileType, compileDir);

        if (preCheck.success()) {
            log.info("[ErrorFixAgent] {} already compiles — no fix loop needed", fileType);
            return true;
        }

        String errors = prioritizeCompilerOutput(preCheck.output(), SEEDED_ERRORS_MAX_CHARS);
        log.warn("[ErrorFixAgent] {} errors before fix loop:\n{}", fileType, errors);

        String trigger = """
                Fix the %s compilation. Current compiler errors:

                %s
                %s
                Group these by root cause, investigate the minimum necessary, and patch with
                str_replace. The harness auto-compiles after each of your changes.
                """.formatted(fileType == FileType.BACKEND ? "backend (Java)" : "frontend (TypeScript)",
                              errors, buildContractSection(fileType, workspace));

        log.info("[ErrorFixAgent] Starting fix loop for {} — max {} tool rounds", fileType, MAX_TOOL_ROUNDS);

        proLlm.runFixAgentLoop(SYSTEM_PROMPT, trigger, tools,
                req -> executeToolCall(req, fileType, workspace, compileDir, reader, ctx),
                executedTools -> autoVerify(executedTools, fileType, compileDir),
                MAX_TOOL_ROUNDS);

        BuildToolService.BuildResult finalResult = check(fileType, compileDir);

        if (finalResult.success()) {
            log.info("[ErrorFixAgent] {} compilation passes after agent loop", fileType);
            return true;
        }
        String remainingErrors = finalResult.output();
        if (remainingErrors.length() > 3000) remainingErrors = remainingErrors.substring(0, 3000) + "\n[...truncated]";
        log.warn("[ErrorFixAgent] {} compilation still failing after agent loop:\n{}", fileType, remainingErrors);
        return false;
    }

    /**
     * Post-round hook: when the model changed anything this round, run the compiler and
     * hand it the result for free — the multifit session made five consecutive patches
     * without a single verification because each compile cost a round.
     */
    private String autoVerify(List<String> executedTools, FileType fileType, Path compileDir) {
        boolean mutated = executedTools.stream().anyMatch(MUTATING_TOOLS::contains);
        if (!mutated) return null;

        BuildToolService.BuildResult result = check(fileType, compileDir);

        if (result.success()) {
            return "[auto-verify] COMPILATION PASSED — all errors resolved. You are done; stop calling tools.";
        }
        String output = prioritizeCompilerOutput(result.output(), AUTO_VERIFY_MAX_CHARS);
        return "[auto-verify] Compiler run after your changes — remaining errors:\n" + output;
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
                case "str_replace"            -> strReplace(args.path("path").asText(), args.path("old_string").asText(),
                                                            args.path("new_string").asText(), workspace, ctx);
                case "run_npm_install"        -> runNpmInstall(args.path("package").asText(), fileType, workspace);
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

    /**
     * The authoritative check per side — MUST match what the ValidationNodes enforce.
     * Frontend uses `npm run build` (tsc + vite bundling), NOT bare tsc: the multifit
     * debut run had the agent win on tsc while the node's final vite build still failed,
     * trapping retries in a loop where the agent saw nothing wrong.
     */
    private BuildToolService.BuildResult check(FileType fileType, Path compileDir) {
        return fileType == FileType.BACKEND
                ? buildTool.runMvnCompile(compileDir)
                : buildTool.runNpmBuild(compileDir);
    }

    private String runCompiler(String type, FileType defaultType, Path compileDir) {
        boolean isBackend = "backend".equalsIgnoreCase(type)
                || (type == null || type.isBlank()) && defaultType == FileType.BACKEND;

        BuildToolService.BuildResult result = check(
                isBackend ? FileType.BACKEND : FileType.FRONTEND, compileDir);

        if (result.success()) return "COMPILATION PASSED — no errors.";

        return prioritizeCompilerOutput(result.output(), 5000);
    }

    private static final Pattern TSC_ERROR_LINE =
            Pattern.compile("^(\\S+?)\\(\\d+,\\d+\\): error TS\\d+");

    /**
     * Surfaces derived-type errors first and makes truncation loss-aware. tsc lists errors
     * in path order (components < ... < types), so with a fixed-size window the derived
     * src/types/ errors — the ones the agent must escalate rather than work around — were
     * always the part cut off (circuit-house 2026-07-12: 37KB of output, types errors
     * starting at byte 25K, every window capped well below that). Reorders tsc error
     * blocks so src/types/ comes first, then truncates on a block boundary and reports
     * how many errors were dropped. Non-tsc output (mvn) falls back to plain truncation.
     */
    static String prioritizeCompilerOutput(String output, int maxChars) {
        List<String> lines = output.lines().toList();

        List<String> preamble = new ArrayList<>();
        List<List<String>> typeBlocks = new ArrayList<>();
        List<List<String>> otherBlocks = new ArrayList<>();
        List<String> current = null;

        for (String line : lines) {
            Matcher m = TSC_ERROR_LINE.matcher(line);
            if (m.find()) {
                current = new ArrayList<>();
                (m.group(1).startsWith("src/types/") ? typeBlocks : otherBlocks).add(current);
            }
            if (current == null) preamble.add(line);
            else current.add(line);
        }

        int totalErrors = typeBlocks.size() + otherBlocks.size();
        if (totalErrors == 0) {
            return output.length() > maxChars
                    ? output.substring(0, maxChars) + "\n[...truncated]" : output;
        }

        StringBuilder sb = new StringBuilder(String.join("\n", preamble));
        if (!typeBlocks.isEmpty()) {
            sb.append("\nNOTE: ").append(typeBlocks.size()).append(" error(s) are inside DERIVED files ")
              .append("(src/types/*), listed first below. Those files are regenerated every attempt and ")
              .append("cannot be edited — if a derived file itself is malformed, no page-level fix can ")
              .append("succeed; say so and stop instead of patching consumers.\n");
        }

        int shown = 0;
        for (List<String> block : concat(typeBlocks, otherBlocks)) {
            String rendered = "\n" + String.join("\n", block);
            if (sb.length() + rendered.length() > maxChars) break;
            sb.append(rendered);
            shown++;
        }
        if (shown < totalErrors) {
            sb.append("\n[...truncated: showing ").append(shown).append(" of ")
              .append(totalErrors).append(" errors — recompile after fixing these to see the rest]");
        }
        return sb.toString();
    }

    private static List<List<String>> concat(List<List<String>> a, List<List<String>> b) {
        List<List<String>> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** Marker line stamped by TsTypeGenerator/TsSdkGenerator on every derived file. */
    private static final String DERIVED_MARKER = "GENERATED from the backend API contract";

    /** Marker line stamped by RouteManifestGenerator on routes.ts (App.tsx stays editable). */
    private static final String PLAN_MARKER = "GENERATED from the architecture plan";

    /**
     * Fence around the derived API + navigation layers. Observed failure mode: the agent
     * "fixes" unresolvable imports by hand-writing phantom type/service files or rewriting
     * derived ones — which the next attempt's re-derivation clobbers (churn loop).
     * Mutations are refused with a redirect to fixing the importing file instead.
     *
     * @return an error string when the mutation must be refused, else null
     */
    private String derivedFileGuard(Path target, String relativePath) {
        String p = relativePath.replace('\\', '/');
        boolean inTypes    = p.startsWith("frontend/src/types/") && !p.startsWith("frontend/src/types/local/");
        // Any .ts under services/ (not just *Service.ts — blocks adminOrderHelpers.ts-style
        // end-runs), with services/local/ as the model-writable escape hatch.
        boolean inServices = p.startsWith("frontend/src/services/")
                && !p.startsWith("frontend/src/services/local/") && p.endsWith(".ts");
        boolean isRoutes   = p.equals("frontend/src/routes.ts");

        if (Files.exists(target)) {
            try {
                String firstLine = Files.readAllLines(target).stream().findFirst().orElse("");
                if (firstLine.contains(DERIVED_MARKER) || firstLine.contains(PLAN_MARKER)) {
                    return "REFUSED: " + relativePath + " is DERIVED (from the compiled backend contract "
                            + "or the architecture plan) and is regenerated on every attempt — edits here are "
                            + "discarded. Its contents are ground truth. Fix the files that IMPORT it instead: "
                            + "rename the mismatched field/function/route in the page/hook/component to match "
                            + "what this file actually exports.";
                }
            } catch (IOException ignored) {
                // unreadable — fall through to normal handling
            }
            return null;
        }

        if (inTypes || inServices || isRoutes) {
            return "REFUSED: cannot create " + relativePath + " — wire types, API services and the route "
                    + "registry are DERIVED and this module was not derived, so the import path is "
                    + "wrong. Fix the importing file to use a module that exists (see the derived files "
                    + "under frontend/src/types/, frontend/src/services/ and frontend/src/routes.ts). "
                    + "Frontend-only types belong in frontend/src/types/local/; non-API service modules "
                    + "belong in frontend/src/services/local/.";
        }
        return null;
    }

    private String writeFile(String relativePath, String content, Path workspace, WorkerContext ctx) {
        if (relativePath == null || relativePath.isBlank()) return "ERROR: path is required";
        if (content == null || content.isBlank()) return "ERROR: content is required";

        Path target = workspace.resolve(relativePath).normalize();
        if (!target.startsWith(workspace)) return "ERROR: path traversal not allowed: " + relativePath;
        String refusal = derivedFileGuard(target, relativePath);
        if (refusal != null) return refusal;

        // ── Option 3: enforce surgical str_replace edits on existing files ──────────
        // write_file on an existing file replaces ALL content at once: the agent must
        // reproduce the entire file from memory, which (a) costs ~10–40× more output
        // tokens than a targeted str_replace, and (b) risks silently changing lines it
        // didn't intend to touch. Blocking it here forces the agent to use str_replace
        // for every edit, which is always safe: read the file, find the exact string,
        // replace just that string, write back — zero drift outside the changed lines.
        if (Files.exists(target)) {
            log.warn("[ErrorFixAgent] Blocked write_file on existing file {} — use str_replace for edits",
                    relativePath);
            return "REFUSED: '" + relativePath + "' already exists. "
                    + "Use str_replace to make targeted edits — write_file is only for creating new files. "
                    + "str_replace(path, old_string, new_string) replaces exactly old_string with new_string; "
                    + "make old_string the smallest unique snippet that contains the lines you want to change.";
        }

        refusal = lombokGuard(target, relativePath, content);
        if (refusal != null) return refusal;

        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            updateDbRecord(relativePath, ctx);
            log.info("[ErrorFixAgent] Wrote {} ({} chars)", relativePath, content.length());
            return "OK";
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Refuses mutations that strip Lombok off an annotated class or hand-expand the
     * accessors it generates. Applied to the POST-edit content, so a str_replace that
     * deletes {@code @Data} is caught the same as a full-file rewrite that omits it.
     */
    private String lombokGuard(Path target, String relativePath, String proposed) {
        if (!Files.exists(target)) return null;
        try {
            String refusal = LombokIntegrityGuard.check(
                    relativePath, Files.readString(target), proposed);
            if (refusal != null) {
                log.warn("[ErrorFixAgent] Refused Lombok-stripping mutation on {}", relativePath);
            }
            return refusal;
        } catch (IOException e) {
            return null; // unreadable — let the normal path handle it
        }
    }

    /**
     * Edit-based patching: replaces one exact occurrence of old_string. Enforcing
     * uniqueness keeps edits deterministic; a minimal diff also cuts output tokens
     * ~10x vs full-file rewrites and eliminates max_tokens truncation of large files.
     */
    private String strReplace(String relativePath, String oldString, String newString,
                              Path workspace, WorkerContext ctx) {
        if (relativePath == null || relativePath.isBlank()) return "ERROR: path is required";
        if (oldString == null || oldString.isEmpty()) return "ERROR: old_string is required";
        if (newString == null) newString = "";

        Path target = workspace.resolve(relativePath).normalize();
        if (!target.startsWith(workspace)) return "ERROR: path traversal not allowed: " + relativePath;
        if (!Files.exists(target)) return "ERROR: file not found: " + relativePath
                + " (use write_file to create new files)";
        String refusal = derivedFileGuard(target, relativePath);
        if (refusal != null) return refusal;

        try {
            String content = Files.readString(target);
            int occurrences = countOccurrences(content, oldString);
            if (occurrences == 0) {
                return "ERROR: old_string not found in " + relativePath
                        + ". Read the file and copy the exact text including whitespace.";
            }
            if (occurrences > 1) {
                return "ERROR: old_string occurs " + occurrences + " times in " + relativePath
                        + " — include more surrounding context to make it unique.";
            }
            String patched = content.replace(oldString, newString);
            String lombokRefusal = LombokIntegrityGuard.check(relativePath, content, patched);
            if (lombokRefusal != null) {
                log.warn("[ErrorFixAgent] Refused Lombok-stripping patch on {}", relativePath);
                return lombokRefusal;
            }
            Files.writeString(target, patched);
            updateDbRecord(relativePath, ctx);
            log.info("[ErrorFixAgent] Patched {} ({} -> {} chars)", relativePath,
                    oldString.length(), newString.length());
            return "OK — replaced 1 occurrence";
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /**
     * Installs a missing npm package. Previously "Cannot find module 'x'" was unfixable:
     * the agent could edit package.json but tsc reads node_modules. Framework packages
     * are refused — installing `next` to satisfy a next/navigation import "fixes" the
     * compile while breaking the Vite app at runtime.
     */
    private String runNpmInstall(String packageName, FileType fileType, Path workspace) {
        if (packageName == null || packageName.isBlank()) return "ERROR: package is required";
        if (fileType == FileType.BACKEND) {
            return "ERROR: run_npm_install is frontend-only. For Maven dependencies edit backend/pom.xml"
                    + " — mvn re-resolves on the next compile.";
        }
        String normalized = packageName.trim().toLowerCase();
        if (NPM_FRAMEWORK_BLACKLIST.contains(normalized)) {
            return "ERROR: '" + packageName + "' is a framework package that conflicts with the platform"
                    + " stack (Vite + react-router-dom). Fix the importing file to use the platform"
                    + " equivalents instead (e.g. next/navigation -> react-router-dom useNavigate).";
        }
        if (!packageName.matches("(@[a-z0-9-~][a-z0-9-._~]*/)?[a-z0-9-~][a-z0-9-._~]*")) {
            return "ERROR: invalid npm package name: " + packageName;
        }

        BuildToolService.BuildResult result =
                buildTool.runNpmInstallPackage(workspace.resolve("frontend"), packageName);
        if (result.success()) {
            log.info("[ErrorFixAgent] Installed npm package {}", packageName);
            return "OK — installed " + packageName;
        }
        String output = result.output();
        return "ERROR: npm install failed:\n"
                + (output.length() > 1000 ? output.substring(output.length() - 1000) : output);
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
                        .name("str_replace")
                        .description("PREFERRED fix tool: replace one exact occurrence of old_string with new_string in a file. old_string must match exactly (including whitespace) and be unique in the file — include surrounding context lines to disambiguate. Keep both strings minimal.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("path", "Relative path from workspace root")
                                .addStringProperty("old_string", "Exact text to replace — must occur exactly once in the file")
                                .addStringProperty("new_string", "Replacement text")
                                .required(List.of("path", "old_string", "new_string"))
                                .build())
                        .build(),

                ToolSpecification.builder()
                        .name("write_file")
                        .description("Create a NEW file, or fully rewrite one when str_replace is impractical. Write the entire file — not a diff. No markdown fences in the content.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("path", "Relative path from workspace root")
                                .addStringProperty("content", "Complete new file content — raw source code only")
                                .required(List.of("path", "content"))
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
                        .name("run_compiler")
                        .description("Run the compiler for a fresh error list. NOT needed after your own changes — the harness auto-compiles and reports after every mutating response.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("type", "\"backend\" to run mvn compile, \"frontend\" to run tsc --noEmit")
                                .required(List.of("type"))
                                .build())
                        .build(),

                ToolSpecification.builder()
                        .name("run_npm_install")
                        .description("Install a missing npm package into the frontend (fixes 'Cannot find module X' for real libraries). Framework packages (next, vue, express...) are refused — fix those imports to platform equivalents instead.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("package", "npm package name, e.g. date-fns or @radix-ui/react-tabs")
                                .required(List.of("package"))
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
