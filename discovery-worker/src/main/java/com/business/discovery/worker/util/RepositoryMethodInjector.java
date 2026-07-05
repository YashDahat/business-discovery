package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses "cannot find symbol: method findByX on SomeRepository" compile errors and injects
 * the missing Spring Data JPA method declarations into the repository interface before
 * ErrorFixAgent runs.
 *
 * Root cause this fixes: JavaFileTemplater generates REPOSITORY interfaces from the architect
 * spec's publicFunctions list. If the SERVICE layer LLM calls a repository method that was
 * never declared in the spec (e.g. findByRazorpayOrderId), the interface is missing the
 * method and the project won't compile.
 *
 * Running this before ErrorFixAgent eliminates what would otherwise be 3-5 rounds of the
 * agent loop just to add a one-liner to a repository interface.
 */
@Slf4j
public final class RepositoryMethodInjector {

    private RepositoryMethodInjector() {}

    // Matches: symbol:   method findByRazorpayOrderId(java.lang.String)
    private static final Pattern METHOD_SYMBOL = Pattern.compile(
            "symbol:\\s+method (\\w+)\\(([^)]*)\\)");

    // Matches: location: variable orderRepository of type com.foo.repository.OrderRepository
    private static final Pattern REPO_LOCATION = Pattern.compile(
            "location: variable \\w+ of type ([\\w.]+Repository)");

    /**
     * Scans compile output for "cannot find symbol: method X on SomeRepository" errors,
     * then injects the missing method declarations into the corresponding .java file.
     * Returns true if at least one method was injected (caller should re-compile).
     */
    public static boolean injectMissingMethods(Path backendDir, String compileOutput) {
        if (compileOutput == null || compileOutput.isBlank()) return false;
        List<MethodMissing> missing = extractMissingMethods(compileOutput);
        if (missing.isEmpty()) return false;

        boolean anyInjected = false;
        for (MethodMissing m : missing) {
            if (injectIntoRepository(backendDir, m)) anyInjected = true;
        }
        return anyInjected;
    }

    private static List<MethodMissing> extractMissingMethods(String output) {
        List<MethodMissing> result = new ArrayList<>();
        String[] lines = output.split("\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher methodMatcher = METHOD_SYMBOL.matcher(lines[i]);
            if (!methodMatcher.find()) continue;
            // Scan ahead up to 5 lines for the "location: ... Repository" line
            for (int j = i + 1; j < Math.min(i + 6, lines.length); j++) {
                Matcher repoMatcher = REPO_LOCATION.matcher(lines[j]);
                if (repoMatcher.find()) {
                    result.add(new MethodMissing(
                            methodMatcher.group(1),
                            methodMatcher.group(2).trim(),
                            repoMatcher.group(1)));
                    break;
                }
            }
        }
        // Deduplicate same (method, repo) pairs
        return result.stream().distinct().toList();
    }

    private static boolean injectIntoRepository(Path backendDir, MethodMissing m) {
        String simpleRepoName = m.repoFqn.substring(m.repoFqn.lastIndexOf('.') + 1);
        String entityName = simpleRepoName.replace("Repository", "");
        Path repoFile = findFile(backendDir, simpleRepoName + ".java");

        if (repoFile == null) {
            log.warn("[RepositoryMethodInjector] Cannot find {} — skipping injection of {}",
                    simpleRepoName, m.methodName);
            return false;
        }

        try {
            String content = Files.readString(repoFile);
            if (content.contains(m.methodName + "(")) {
                log.info("[RepositoryMethodInjector] {} already declares {} — skipping",
                        simpleRepoName, m.methodName);
                return false;
            }
            int lastBrace = content.lastIndexOf('}');
            if (lastBrace < 0) {
                log.warn("[RepositoryMethodInjector] No closing brace in {} — cannot inject", repoFile);
                return false;
            }

            String methodDecl = buildMethodDeclaration(m.methodName, m.paramTypes, entityName);
            String patched = content.substring(0, lastBrace)
                    + "    " + methodDecl + "\n"
                    + content.substring(lastBrace);
            Files.writeString(repoFile, patched);
            log.info("[RepositoryMethodInjector] Injected '{}' into {}", methodDecl.trim(), simpleRepoName);
            return true;

        } catch (IOException e) {
            log.warn("[RepositoryMethodInjector] Failed to patch {}: {}", repoFile, e.getMessage());
            return false;
        }
    }

    private static String buildMethodDeclaration(String methodName, String rawParams, String entityName) {
        String returnType = inferReturnType(methodName, entityName);
        String params = buildParamList(methodName, rawParams);
        return returnType + " " + methodName + "(" + params + ");";
    }

    private static String inferReturnType(String methodName, String entityName) {
        String lower = methodName.toLowerCase();
        if (lower.startsWith("findallby") || lower.startsWith("getallby") || lower.startsWith("searchby"))
            return "java.util.List<" + entityName + ">";
        if (lower.startsWith("findby") || lower.startsWith("getby") || lower.startsWith("fetchby"))
            return "java.util.Optional<" + entityName + ">";
        if (lower.startsWith("existsby")) return "boolean";
        if (lower.startsWith("countby")) return "long";
        if (lower.startsWith("deleteby") || lower.startsWith("removeby")) return "void";
        // Default: Optional
        return "java.util.Optional<" + entityName + ">";
    }

    private static String buildParamList(String methodName, String rawParams) {
        if (rawParams.isBlank()) return "";
        // When there are multiple params (comma-separated), handle each
        String[] parts = rawParams.split(",");
        String paramName = inferParamName(methodName, parts.length);
        if (parts.length == 1) {
            return simplifyType(parts[0].trim()) + " " + paramName;
        }
        // Multi-param: generate generic names
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(simplifyType(parts[i].trim())).append(" param").append(i + 1);
        }
        return sb.toString();
    }

    /**
     * Derives a parameter name from the method name.
     * findByRazorpayOrderId → razorpayOrderId
     * existsByEmail → email
     */
    private static String inferParamName(String methodName, int paramCount) {
        if (paramCount > 1) return "value";
        for (String prefix : List.of("findAllBy", "findBy", "getBy", "getAllBy",
                "existsBy", "countBy", "deleteBy", "removeBy", "fetchBy", "searchBy")) {
            if (methodName.startsWith(prefix) && methodName.length() > prefix.length()) {
                String field = methodName.substring(prefix.length());
                return Character.toLowerCase(field.charAt(0)) + field.substring(1);
            }
        }
        return "value";
    }

    private static String simplifyType(String fqn) {
        return switch (fqn) {
            case "java.lang.String"  -> "String";
            case "java.util.UUID"    -> "java.util.UUID";
            case "java.lang.Long"    -> "Long";
            case "java.lang.Integer" -> "Integer";
            case "java.lang.Boolean" -> "Boolean";
            case "java.lang.Double"  -> "Double";
            default -> fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
        };
    }

    private static Path findFile(Path backendDir, String fileName) {
        try {
            return Files.walk(backendDir)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private record MethodMissing(String methodName, String paramTypes, String repoFqn) {}
}
