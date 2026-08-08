package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enforcement Point B for the backend — the repair half, driven off {@code mvn compile} output.
 * {@code javac} already <em>detects</em> a missing producer as "cannot find symbol: class X"; this
 * synthesizes a minimal placeholder class for each such X that was never generated (the
 * {@code OrderItemResponse} case), so the missing-<em>type</em> error is resolved.
 *
 * <p>Unlike the frontend permissive stub (TS {@code any} closes everything), Java has no equivalent,
 * so a placeholder class resolves the type reference but not every getter/constructor a consumer may
 * call. That is still a real win: it turns an "author a whole file" problem (which the ErrorFixAgent
 * fails at — {@code AdminLayout} survived 3 attempts) into an "add methods to an existing class"
 * problem, which the agent handles well. See {@code docs/architecture-json-completeness-plan.md} §8.
 */
@Slf4j
public final class BackendClassSynthesizer {

    /** javac's {@code symbol: class X} followed by {@code location: class <FQN>}. */
    private static final Pattern MISSING_CLASS = Pattern.compile(
            "symbol:\\s+class\\s+(\\w+)\\s*\\R\\s*location:\\s+class\\s+([\\w.$]+)");

    private BackendClassSynthesizer() {}

    /**
     * Writes a placeholder class for each "cannot find symbol: class X" whose target file does not
     * exist. Target package = the class's import in the referencer, else the referencer's own package.
     * Returns the number written. Never throws.
     */
    public static int synthesize(Path backendSrcJava, String compileOutput) {
        if (compileOutput == null || compileOutput.isBlank() || !Files.exists(backendSrcJava)) return 0;

        Set<String> done = new HashSet<>();
        int written = 0;
        Matcher m = MISSING_CLASS.matcher(compileOutput);
        while (m.find()) {
            String missing = m.group(1);
            String referencerFqn = m.group(2);
            String referencerPkg = packageOf(referencerFqn);
            if (referencerPkg.isEmpty()) continue;                 // no discernible project package

            String targetPkg = resolveTargetPackage(backendSrcJava, referencerFqn, referencerPkg, missing);
            if (!done.add(targetPkg + "." + missing)) continue;    // dedupe repeated errors

            Path target = backendSrcJava.resolve(targetPkg.replace('.', '/')).resolve(missing + ".java");
            if (Files.exists(target)) continue;
            try {
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                Files.writeString(target, classSource(targetPkg, missing));
                written++;
                log.info("[BackendClassSynthesizer] Synthesized placeholder class {}.{}", targetPkg, missing);
            } catch (IOException e) {
                log.warn("[BackendClassSynthesizer] Could not write {}.{}: {}", targetPkg, missing, e.getMessage());
            }
        }
        return written;
    }

    /** Package = leading segments up to the first that starts uppercase (packages lowercase, classes Upper). */
    static String packageOf(String fqn) {
        StringBuilder pkg = new StringBuilder();
        for (String part : fqn.split("\\.")) {
            if (part.isEmpty() || Character.isUpperCase(part.charAt(0))) break;
            if (pkg.length() > 0) pkg.append('.');
            pkg.append(part);
        }
        return pkg.toString();
    }

    private static String topClass(String fqn, String pkg) {
        String rest = pkg.isEmpty() ? fqn : fqn.substring(pkg.length() + 1);
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    /** If the referencer imports the missing class from another package, use that; else same package. */
    private static String resolveTargetPackage(Path backendSrcJava, String referencerFqn,
                                               String referencerPkg, String missing) {
        try {
            Path refFile = backendSrcJava.resolve(referencerPkg.replace('.', '/'))
                    .resolve(topClass(referencerFqn, referencerPkg) + ".java");
            if (Files.exists(refFile)) {
                Matcher im = Pattern.compile("import\\s+([\\w.]+)\\." + Pattern.quote(missing) + "\\s*;")
                        .matcher(Files.readString(refFile));
                if (im.find()) return im.group(1);
            }
        } catch (IOException ignored) {
            // fall through to same-package
        }
        return referencerPkg;
    }

    static String classSource(String pkg, String name) {
        String header = "package " + pkg + ";\n\n"
                + "// AUTO-GENERATED placeholder — referenced but never generated (Point B synthesis). Replace with the real type.\n";
        if (name.endsWith("Exception")) {
            return header
                + "public class " + name + " extends RuntimeException {\n"
                + "    public " + name + "(String message) { super(message); }\n"
                + "    public " + name + "(String message, Throwable cause) { super(message, cause); }\n"
                + "}\n";
        }
        return header + "public class " + name + " {\n}\n";
    }
}
