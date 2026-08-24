package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Aligns granted authorities with role-based access checks — a RUNTIME bug the
 * compiler cannot see, so this runs unconditionally (not only on compile failure).
 *
 * Spring Security's hasRole("ADMIN") / hasAnyRole(...) / @PreAuthorize("hasRole('ADMIN')")
 * all match the authority "ROLE_ADMIN", but the LLM habitually grants authorities as the
 * bare enum name: new SimpleGrantedAuthority(role.name()) → "ADMIN". Every admin endpoint
 * then 403s even with a valid admin token (observed on multifit-aundh, and dormant in
 * log-house because @EnableMethodSecurity was absent there).
 *
 * Fix: when the codebase uses hasRole/hasAnyRole anywhere, ensure role-derived
 * SimpleGrantedAuthority arguments are ROLE_-prefixed. If the codebase uses hasAuthority
 * with bare names instead (no hasRole), authorities are left untouched — prefixing would
 * break that world.
 */
@Slf4j
public final class RolePrefixPatcher {

    private static final String NEW_AUTHORITY = "new SimpleGrantedAuthority(";

    private RolePrefixPatcher() {}

    /** Returns true if any file was modified. */
    public static boolean fix(Path backendSrcDir) {
        if (!Files.exists(backendSrcDir)) return false;

        List<Path> javaFiles;
        try (Stream<Path> s = Files.walk(backendSrcDir)) {
            javaFiles = s.filter(p -> p.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            log.warn("[RolePrefixPatcher] Walk failed: {}", e.getMessage());
            return false;
        }

        // Only prefix authorities when the app actually uses role-based checks.
        // hasAuthority(...) with bare names is the other valid world — leave it alone.
        boolean usesHasRole = javaFiles.stream().anyMatch(p -> {
            try {
                String c = Files.readString(p);
                return c.contains("hasRole(") || c.contains("hasAnyRole(");
            } catch (IOException e) {
                return false;
            }
        });
        if (!usesHasRole) {
            log.info("[RolePrefixPatcher] No hasRole/hasAnyRole usage — authorities left as-is");
            return false;
        }

        boolean changed = false;
        for (Path file : javaFiles) {
            // Skip foundation-owned files — they are closed for modification (OCP). The foundation's
            // User.java, SecurityConfig.java, and auth/payment spine already implement authorities
            // correctly for the hasAuthority("ADMIN") world. Patching them when the LLM happened
            // to generate hasRole() elsewhere corrupts User.java's Lombok processing and breaks
            // the SecurityConfig/hasAuthority contract the foundation relies on.
            if (isFoundationOwned(file)) continue;
            try {
                String content = Files.readString(file);
                if (!content.contains(NEW_AUTHORITY)) continue;
                String patched = prefixRoleAuthorities(content);
                if (!patched.equals(content)) {
                    Files.writeString(file, patched);
                    log.info("[RolePrefixPatcher] Added ROLE_ prefix to authorities in {}", file.getFileName());
                    changed = true;
                }
            } catch (IOException e) {
                log.warn("[RolePrefixPatcher] Could not process {}: {}", file, e.getMessage());
            }
        }
        return changed;
    }

    /**
     * Rewrites new SimpleGrantedAuthority(<roleExpr>) → new SimpleGrantedAuthority("ROLE_" + <roleExpr>)
     * for arguments that reference a role and are not already prefixed. Walks balanced parens so
     * arguments containing method calls (role.name(), user.getRole().name()) are handled correctly.
     */
    static String prefixRoleAuthorities(String content) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (true) {
            int idx = content.indexOf(NEW_AUTHORITY, i);
            if (idx < 0) {
                out.append(content, i, content.length());
                break;
            }
            int argStart = idx + NEW_AUTHORITY.length();
            int argEnd = matchingParen(content, argStart);
            if (argEnd < 0) { // unbalanced — bail, copy rest verbatim
                out.append(content, i, content.length());
                break;
            }
            String arg = content.substring(argStart, argEnd).trim();
            out.append(content, i, argStart);
            out.append(shouldPrefix(arg) ? "\"ROLE_\" + " + arg : arg);
            out.append(')');
            i = argEnd + 1;
        }
        return out.toString();
    }

    /** True when arg names a role and isn't already ROLE_-prefixed. */
    private static boolean shouldPrefix(String arg) {
        if (arg.contains("\"ROLE_\"") || arg.contains("ROLE_")) return false;
        boolean referencesRole = arg.contains(".name()")
                || arg.contains("getRole()")
                || arg.matches(".*\\brole\\b.*");
        return referencesRole;
    }

    /**
     * Returns true for files that belong to the foundation's auth/payment spine and must
     * never be modified by this patcher. These files are correct by construction — patching
     * them when the LLM used hasRole() elsewhere breaks the hasAuthority() contract the
     * foundation's SecurityConfig relies on and can corrupt Lombok processing.
     */
    private static boolean isFoundationOwned(Path file) {
        String name = file.getFileName().toString();
        // Auth spine
        if (name.equals("User.java") || name.equals("Role.java")
                || name.equals("UserService.java") || name.equals("SecurityConfig.java")
                || name.equals("JwtAuthFilter.java") || name.equals("AdminInitializer.java")
                || name.startsWith("Jwt") || name.startsWith("PasswordEncoder")
                || name.startsWith("Auth") || name.equals("UserRepository.java")) return true;
        // Payment spine
        if (name.startsWith("Payment") || name.equals("GatewayWebhookEvent.java")
                || name.endsWith("PaymentGateway.java")) return true;
        return false;
    }

    /** Index of the paren that closes the one opened just before startInclusive. */
    private static int matchingParen(String s, int startInclusive) {
        int depth = 1;
        boolean inString = false;
        char stringChar = 0;
        for (int i = startInclusive; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == stringChar && s.charAt(i - 1) != '\\') inString = false;
                continue;
            }
            if (c == '"' || c == '\'') { inString = true; stringChar = c; }
            else if (c == '(') depth++;
            else if (c == ')') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }
}
