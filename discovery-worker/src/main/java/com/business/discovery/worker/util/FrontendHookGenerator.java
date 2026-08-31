package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives a TanStack-Query hook file from a single derived {@code services/<domain>Service.ts}
 * file — one hook per exported service function, so components/pages consume hooks that provably
 * exist instead of an LLM inventing (or the enrichment naming) hooks with no backing file
 * (docs/frontend-hook-generation-and-prompt-segregation.md §3).
 *
 * <p>Text-parses the emitted service (a fixed deterministic template) rather than re-deriving from
 * {@link ApiInventory}, so the hook wraps the REAL, collision-resolved exported name. Pure and
 * decoupled — feed a service string, get a hook string.
 *
 * <p>Naming is via {@link HookNaming} (the single source shared with the cross-reference checker):
 * a GET → {@code useQuery} reader, a POST/PUT/PATCH/DELETE → {@code useMutation}. Emits the same
 * {@code // GENERATED from the backend API contract} marker the services carry, so
 * {@code FrontendGeneratorNode.shouldSkip} treats the hook file as a derived surface and the LLM
 * never regenerates it.
 */
@Slf4j
public final class FrontendHookGenerator {

    private FrontendHookGenerator() {}

    public static final String DERIVED_MARKER = "// GENERATED from the backend API contract — do not edit by hand.";

    /** {@code export const name = async (params): Promise<Ret> => {} — the fixed TsSdkGenerator shape. */
    private static final Pattern FN = Pattern.compile(
            "export\\s+const\\s+(\\w+)\\s*=\\s*async\\s*\\(([^)]*)\\)\\s*:\\s*Promise<(.+?)>\\s*=>\\s*\\{",
            Pattern.DOTALL);
    /** The HTTP verb inside a function body: {@code apiClient.get<...>(...)}. */
    private static final Pattern VERB = Pattern.compile("apiClient\\.(get|post|put|patch|delete)\\b");
    /** {@code import type { A, B } from '@/types/x';} */
    private static final Pattern TYPE_IMPORT = Pattern.compile(
            "import\\s+type\\s*\\{([^}]*)}\\s*from\\s*'([^']+)'");
    /** {@code export interface|type|enum Name} defined IN the service itself (import it from the service module). */
    private static final Pattern LOCAL_TYPE = Pattern.compile(
            "export\\s+(?:interface|type|enum)\\s+(\\w+)");

    /** A generated hook file: workspace-relative path + content. */
    public record HookFile(String path, String content) {}

    /** One parsed service function. */
    private record Fn(String name, List<String[]> params, String returnType, boolean query) {}

    /**
     * @param serviceRelPath workspace-relative path of the service, e.g. {@code frontend/src/services/bookingService.ts}
     * @param serviceContent the emitted service file text
     * @return the hook file to write, or empty if the service exports no usable functions
     */
    public static Optional<HookFile> generate(String serviceRelPath, String serviceContent) {
        if (serviceContent == null || serviceContent.isBlank()) return Optional.empty();

        String domain = domainOf(serviceRelPath);                 // bookingService → booking
        String serviceAlias = "@/services/" + baseName(serviceRelPath); // @/services/bookingService

        // name → module for every type we might use: those the service imports (from @/types/…) plus
        // types the service defines and exports itself (import those from the service module).
        Map<String, String> typeModule = new LinkedHashMap<>();
        Matcher ti = TYPE_IMPORT.matcher(serviceContent);
        while (ti.find()) {
            String module = ti.group(2);
            for (String n : ti.group(1).split(",")) {
                String name = n.trim();
                if (!name.isEmpty()) typeModule.put(name, module);
            }
        }
        Matcher lt = LOCAL_TYPE.matcher(serviceContent);
        while (lt.find()) typeModule.put(lt.group(1), serviceAlias);   // service-local exported type

        List<Fn> fns = new ArrayList<>();
        Matcher m = FN.matcher(serviceContent);
        while (m.find()) {
            String verb = firstVerbAfter(serviceContent, m.end());
            if (verb == null) continue;                            // not an apiClient call — skip
            fns.add(new Fn(m.group(1), parseParams(m.group(2)), m.group(3).trim(),
                    "get".equals(verb)));
        }
        if (fns.isEmpty()) return Optional.empty();

        // ── assemble body + collect what needs importing ──────────────────────
        Set<String> rqImports = new TreeSet<>();
        Set<String> usedFns = new LinkedHashSet<>();
        Set<String> usedTypes = new LinkedHashSet<>();
        StringBuilder body = new StringBuilder();

        Set<String> emittedHooks = new LinkedHashSet<>();
        for (Fn fn : fns) {
            String hook = HookNaming.hookFor(fn.name());
            if (hook == null || !emittedHooks.add(hook)) continue;  // dedupe name collisions
            usedFns.add(fn.name());
            collectTypes(fn, typeModule.keySet(), usedTypes);
            if (fn.query()) {
                rqImports.add("useQuery");
                body.append(emitQuery(fn, hook, domain));
            } else {
                rqImports.add("useMutation");
                rqImports.add("useQueryClient");
                body.append(emitMutation(fn, hook, domain));
            }
            body.append('\n');
        }
        if (usedFns.isEmpty()) return Optional.empty();

        StringBuilder out = new StringBuilder();
        out.append(DERIVED_MARKER).append('\n');
        out.append("// One TanStack hook per service function — generated by FrontendHookGenerator.\n\n");
        out.append("import { ").append(String.join(", ", rqImports)).append(" } from '@tanstack/react-query';\n");
        // MutateOptions types the optional second arg of mutate/mutateAsync (see emitMutation). Emitted as a
        // type-only import — separate from the value import above — to stay safe under verbatimModuleSyntax.
        if (rqImports.contains("useMutation")) {
            out.append("import type { MutateOptions } from '@tanstack/react-query';\n");
        }
        out.append("import { ").append(String.join(", ", usedFns)).append(" } from '").append(serviceAlias).append("';\n");
        // types grouped by their module, only the ones we used
        Map<String, TreeSet<String>> byModule = new TreeMap<>();
        for (String t : usedTypes) {
            String mod = typeModule.get(t);
            if (mod != null) byModule.computeIfAbsent(mod, k -> new TreeSet<>()).add(t);
        }
        byModule.forEach((mod, names) -> out.append("import type { ")
                .append(String.join(", ", names)).append(" } from '").append(mod).append("';\n"));
        out.append('\n').append(body.toString().stripTrailing()).append('\n');

        String hookPath = "frontend/src/hooks/" + domain + "Hooks.ts";
        return Optional.of(new HookFile(hookPath, out.toString()));
    }

    // ── emission ───────────────────────────────────────────────────────────────

    private static String emitQuery(Fn fn, String hook, String domain) {
        String data = fn.returnType();
        String params = fn.params().stream().map(p -> p[0] + p[2] + ": " + p[1]).reduce((a, b) -> a + ", " + b).orElse("");
        String keyArgs = fn.params().stream().map(p -> ", " + p[0]).reduce("", String::concat);
        String call = fn.params().isEmpty()
                ? fn.name()                                                    // queryFn: getAllBookings
                : "() => " + fn.name() + "(" + argNames(fn) + ")";             // queryFn: () => getGymClass(classId)
        String ret = "{ data: " + data + " | undefined; isLoading: boolean; isError: boolean; error: Error | null }";
        return "export function " + hook + "(" + params + "): " + ret + " {\n"
             + "  const { data, isLoading, isError, error } = useQuery({ queryKey: ['" + domain + "', '" + fn.name() + "'" + keyArgs + "], queryFn: " + call + " });\n"
             + "  return { data, isLoading, isError, error };\n"
             + "}\n";
    }

    private static String emitMutation(Fn fn, String hook, String domain) {
        String argType;      // the single argument type mutate/mutateAsync accept
        String mutationFn;   // the mutationFn arrow
        if (fn.params().isEmpty()) {
            argType = "void";
            mutationFn = "() => " + fn.name() + "()";
        } else if (fn.params().size() == 1) {
            String[] p = fn.params().get(0);
            argType = p[1];
            mutationFn = "(" + p[0] + ": " + p[1] + ") => " + fn.name() + "(" + p[0] + ")";
        } else {
            argType = "{ " + fn.params().stream().map(p -> p[0] + p[2] + ": " + p[1])
                    .reduce((a, b) -> a + "; " + b).orElse("") + " }";
            mutationFn = "({ " + argNames(fn) + " }: " + argType + ") => " + fn.name() + "(" + argNames(fn) + ")";
        }
        // mutate/mutateAsync mirror TanStack's real signatures: a variables arg PLUS an optional
        // MutateOptions (onSuccess/onError/onSettled). Omitting the options param is what made callers
        // writing the idiomatic mutate(vars, { onSuccess }) fail with TS2554 — 13 errors across 7 files
        // in brief 9312afa6 (docs/frontend-issue-solution-plan-9312afa6.md #1).
        String data = fn.returnType();
        String tvars = "void".equals(argType) ? "void" : argType;
        String varsParam = "void".equals(argType) ? "vars?: void" : "vars: " + argType;
        String sigArgs = "(" + varsParam + ", options?: MutateOptions<" + data + ", Error, " + tvars + ">)";
        String ret = "{ mutate: " + sigArgs + " => void; mutateAsync: " + sigArgs + " => Promise<" + data
                + ">; isPending: boolean; isError: boolean; error: Error | null }";
        return "export function " + hook + "(): " + ret + " {\n"
             + "  const queryClient = useQueryClient();\n"
             + "  const { mutate, mutateAsync, isPending, isError, error } = useMutation({\n"
             + "    mutationFn: " + mutationFn + ",\n"
             + "    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['" + domain + "'] }); },\n"
             + "  });\n"
             + "  return { mutate, mutateAsync, isPending, isError, error };\n"
             + "}\n";
    }

    // ── parsing helpers ──────────────────────────────────────────────────────────

    /**
     * Splits a param list into {bareName, type, opt} triples, respecting nested &lt;&gt; {} () [] so a
     * generic comma survives. A trailing {@code ?} on the name is captured as {@code opt} ("?" / "")
     * so it stays in TYPE positions (signature param, arg-object property) but never leaks into an
     * IDENTIFIER position (call argument, queryKey, destructuring) where {@code section?} is a syntax error.
     */
    private static List<String[]> parseParams(String raw) {
        List<String[]> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : splitTopLevel(raw)) {
            int colon = part.indexOf(':');
            if (colon < 0) continue;
            String name = part.substring(0, colon).trim();
            String type = part.substring(colon + 1).trim();
            String opt = "";
            if (name.endsWith("?")) { opt = "?"; name = name.substring(0, name.length() - 1).trim(); }
            out.add(new String[]{ name, type, opt });
        }
        return out;
    }

    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' || c == '{' || c == '(' || c == '[') depth++;
            else if (c == '>' || c == '}' || c == ')' || c == ']') depth = Math.max(0, depth - 1);
            else if (c == ',' && depth == 0) { add(out, s.substring(start, i)); start = i + 1; }
        }
        add(out, s.substring(start));
        return out;
    }

    private static void add(List<String> out, String v) { v = v.trim(); if (!v.isEmpty()) out.add(v); }

    private static String argNames(Fn fn) {
        return fn.params().stream().map(p -> p[0]).reduce((a, b) -> a + ", " + b).orElse("");
    }

    /** The first apiClient verb appearing after {@code from} in the content, or null. */
    private static String firstVerbAfter(String content, int from) {
        Matcher v = VERB.matcher(content);
        return v.find(from) ? v.group(1) : null;
    }

    /** Adds every known type name that appears in the fn's return type or param types to {@code used}. */
    private static void collectTypes(Fn fn, Set<String> known, Set<String> used) {
        List<String> exprs = new ArrayList<>();
        exprs.add(fn.returnType());
        for (String[] p : fn.params()) exprs.add(p[1]);
        for (String expr : exprs) {
            for (String name : known) {
                if (Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(expr).find()) used.add(name);
            }
        }
    }

    /** bookingService.ts → booking ; classService.ts → class */
    private static String domainOf(String serviceRelPath) {
        String base = baseName(serviceRelPath);
        return base.endsWith("Service") ? base.substring(0, base.length() - "Service".length()) : base;
    }

    private static String baseName(String relPath) {
        String p = relPath.replace('\\', '/');
        String file = p.substring(p.lastIndexOf('/') + 1);
        return file.replaceFirst("\\.(tsx?|jsx?)$", "");
    }
}
