package com.business.discovery.worker.util;

import com.business.discovery.worker.service.BuildToolService;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses "Cannot find module 'X'" TypeScript build errors and fixes them mechanically:
 *   1. RENAME: rewrites import statements for packages that have moved (e.g. react-query →
 *      @tanstack/react-query). No install needed — the correct package is already present.
 *   2. INSTALL: runs npm install for packages the LLM referenced but weren't in package.json.
 *
 * Runs before ErrorFixAgent in FrontendValidationNode. A missing package burns every round
 * of ErrorFixAgent because the agent can't install packages — it can only edit source files.
 * Fixing the package layer here gives ErrorFixAgent a clean baseline.
 */
@Slf4j
public final class NpmPackageFixer {

    private NpmPackageFixer() {}

    // Matches: error TS2307: Cannot find module 'react-toastify' or its corresponding type declarations.
    private static final Pattern MISSING_MODULE =
            Pattern.compile("Cannot find module '([^']+)' or its corresponding type declarations");

    // Packages that have been renamed — rewrite the import, don't install.
    // Key = what LLM generates, Value = correct installed package name.
    private static final Map<String, String> RENAMED_PACKAGES = new LinkedHashMap<>();

    // Packages that can be installed directly via npm install.
    private static final Set<String> INSTALLABLE_PACKAGES = new LinkedHashSet<>();

    static {
        // Common LLM mistakes — old or wrong package names
        RENAMED_PACKAGES.put("react-query",              "@tanstack/react-query");
        RENAMED_PACKAGES.put("react-router",             "react-router-dom");

        // Packages the LLM frequently imports but aren't scaffolded by default
        INSTALLABLE_PACKAGES.add("react-toastify");
        INSTALLABLE_PACKAGES.add("react-hot-toast");
        INSTALLABLE_PACKAGES.add("sonner");
        INSTALLABLE_PACKAGES.add("date-fns");
        INSTALLABLE_PACKAGES.add("framer-motion");
        INSTALLABLE_PACKAGES.add("react-icons");
        INSTALLABLE_PACKAGES.add("recharts");
        INSTALLABLE_PACKAGES.add("react-datepicker");
        INSTALLABLE_PACKAGES.add("react-select");
        INSTALLABLE_PACKAGES.add("react-dropzone");
        INSTALLABLE_PACKAGES.add("react-image-crop");
        INSTALLABLE_PACKAGES.add("@types/react-datepicker");
    }

    /**
     * Parses build output for missing modules, applies renames and installs.
     * Returns true if any fixes were applied (caller should re-run npm build).
     */
    public static boolean fix(Path frontendDir, String buildOutput, BuildToolService buildTool) {
        if (buildOutput == null || buildOutput.isBlank()) return false;

        Set<String> missing = extractMissingModules(buildOutput);
        if (missing.isEmpty()) return false;

        boolean anyFixed = false;

        for (String pkg : missing) {
            if (RENAMED_PACKAGES.containsKey(pkg)) {
                String correct = RENAMED_PACKAGES.get(pkg);
                boolean rewritten = rewriteImports(frontendDir.resolve("src"), pkg, correct);
                if (rewritten) {
                    log.info("[NpmPackageFixer] Rewrote '{}' → '{}' in source files", pkg, correct);
                    anyFixed = true;
                }
            } else if (INSTALLABLE_PACKAGES.contains(pkg)) {
                BuildToolService.BuildResult result = buildTool.runNpmInstallPackage(frontendDir, pkg);
                if (result.success()) {
                    log.info("[NpmPackageFixer] Installed missing package: {}", pkg);
                    anyFixed = true;
                } else {
                    log.warn("[NpmPackageFixer] Failed to install {} (exit={})", pkg, result.exitCode());
                }
            } else {
                log.warn("[NpmPackageFixer] Unknown package '{}' — cannot fix mechanically", pkg);
            }
        }

        return anyFixed;
    }

    private static Set<String> extractMissingModules(String output) {
        Set<String> modules = new LinkedHashSet<>();
        Matcher m = MISSING_MODULE.matcher(output);
        while (m.find()) {
            String mod = m.group(1);
            // Skip relative imports and @/ aliases — those are path issues, not missing packages
            if (!mod.startsWith(".") && !mod.startsWith("@/")) {
                modules.add(mod);
            }
        }
        return modules;
    }

    /**
     * Rewrites all occurrences of `from 'oldPkg'` to `from 'newPkg'` across all
     * .ts and .tsx files under srcDir.
     */
    private static boolean rewriteImports(Path srcDir, String oldPkg, String newPkg) {
        if (!Files.exists(srcDir)) return false;
        boolean[] anyChanged = {false};
        try {
            Files.walk(srcDir)
                    .filter(p -> p.toString().endsWith(".ts") || p.toString().endsWith(".tsx"))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            // Match both single and double quotes
                            String updated = content
                                    .replace("from '" + oldPkg + "'", "from '" + newPkg + "'")
                                    .replace("from \"" + oldPkg + "\"", "from \"" + newPkg + "\"");
                            if (!updated.equals(content)) {
                                Files.writeString(p, updated);
                                log.info("[NpmPackageFixer] Rewrote import in {}", srcDir.relativize(p));
                                anyChanged[0] = true;
                            }
                        } catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            log.warn("[NpmPackageFixer] Failed to walk src dir: {}", e.getMessage());
        }
        return anyChanged[0];
    }
}
