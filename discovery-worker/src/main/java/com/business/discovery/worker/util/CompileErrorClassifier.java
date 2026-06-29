package com.business.discovery.worker.util;

import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies javac / tsc compile errors to route them to the right fix strategy.
 *
 * Structural errors (wrong import path, missing dep, missing import) → deterministic fix, no LLM.
 * Semantic errors (type mismatch, missing method, syntax error) → LLM fix.
 */
public final class CompileErrorClassifier {

    public enum ErrorCategory {
        MISSING_IMPORT,       // "cannot find symbol" → class known in registry, import missing
        WRONG_IMPORT_PATH,    // "package X does not exist" where X starts with basePackage
        MISSING_DEPENDENCY,   // "package X does not exist" where X is an external library
        LOGIC_ERROR,          // type mismatch, missing method, syntax error → needs LLM
        TS_MODULE_NOT_FOUND,  // TS2307 "Cannot find module" → TypeScriptImportFixer
        TS_TYPE_ERROR,        // TS2345, TS2339, etc. → LLM fix
        UNKNOWN
    }

    private static final Pattern PKG_NOT_EXIST =
            Pattern.compile("package ([\\w.]+) does not exist");
    private static final Pattern CANNOT_FIND_SYMBOL =
            Pattern.compile("cannot find symbol");
    // Extracts the class name from javac's "symbol:   class Xxx" line
    private static final Pattern SYMBOL_CLASS =
            Pattern.compile("symbol:\\s+class\\s+(\\w+)");
    private static final Pattern LOGIC_PATTERNS =
            Pattern.compile("incompatible types|cannot be converted|bad return type"
                    + "|illegal start of expression|';' expected|reached end of file"
                    + "|method .+ in class .+ cannot be applied"
                    + "|is not abstract and does not override");
    private static final Pattern TS2307 =
            Pattern.compile("error TS2307");
    private static final Pattern TS_TYPE =
            Pattern.compile("error TS2[0-9]{3}");

    private CompileErrorClassifier() {}

    public static Set<ErrorCategory> classify(String compileOutput, JavaClassRegistry registry) {
        Set<ErrorCategory> result = EnumSet.noneOf(ErrorCategory.class);
        if (compileOutput == null || compileOutput.isBlank()) return result;

        Matcher pkgMatcher = PKG_NOT_EXIST.matcher(compileOutput);
        while (pkgMatcher.find()) {
            String pkg = pkgMatcher.group(1);
            if (!registry.getBasePackage().isEmpty() && pkg.startsWith(registry.getBasePackage())) {
                result.add(ErrorCategory.WRONG_IMPORT_PATH);
            } else {
                result.add(ErrorCategory.MISSING_DEPENDENCY);
            }
        }

        // "cannot find symbol" — check if the missing class is project-internal or external.
        // Project-internal (in registry) → MISSING_IMPORT, deterministic fix via JavaImportResolver.
        // External/Spring/third-party (not in registry) → LOGIC_ERROR, requires LLM fix.
        if (CANNOT_FIND_SYMBOL.matcher(compileOutput).find()) {
            Matcher sm = SYMBOL_CLASS.matcher(compileOutput);
            boolean hasProjectMissing = false;
            boolean foundAnyClass = false;
            while (sm.find()) {
                foundAnyClass = true;
                String className = sm.group(1);
                if (registry.isKnown(className)) {
                    hasProjectMissing = true;
                } else {
                    // Spring, Jakarta, third-party — JavaImportResolver cannot help
                    result.add(ErrorCategory.LOGIC_ERROR);
                }
            }
            if (hasProjectMissing) result.add(ErrorCategory.MISSING_IMPORT);
            // "cannot find symbol" with no extractable class (e.g. missing method/variable)
            if (!foundAnyClass) result.add(ErrorCategory.LOGIC_ERROR);
        }

        if (LOGIC_PATTERNS.matcher(compileOutput).find())     result.add(ErrorCategory.LOGIC_ERROR);
        if (TS2307.matcher(compileOutput).find())             result.add(ErrorCategory.TS_MODULE_NOT_FOUND);
        if (TS_TYPE.matcher(compileOutput).find())            result.add(ErrorCategory.TS_TYPE_ERROR);

        if (result.isEmpty()) result.add(ErrorCategory.UNKNOWN);
        return result;
    }

    public static boolean needsLlmFix(Set<ErrorCategory> categories) {
        return categories.contains(ErrorCategory.LOGIC_ERROR)
                || categories.contains(ErrorCategory.UNKNOWN)
                || categories.contains(ErrorCategory.TS_TYPE_ERROR);
    }

    public static boolean needsDeterministicFix(Set<ErrorCategory> categories) {
        return categories.contains(ErrorCategory.MISSING_IMPORT)
                || categories.contains(ErrorCategory.WRONG_IMPORT_PATH)
                || categories.contains(ErrorCategory.MISSING_DEPENDENCY)
                || categories.contains(ErrorCategory.TS_MODULE_NOT_FOUND);
    }
}
