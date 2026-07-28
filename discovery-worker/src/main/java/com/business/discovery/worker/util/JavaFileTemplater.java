package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.PublicFunction;
import com.business.discovery.worker.service.llm.PublicVariable;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Template-based generation for mechanical Java layers — ENUM, REPOSITORY, and DTO.
 *
 * These file types have no business logic. Generating them from templates instead of the
 * LLM eliminates hallucinated field names, wrong generics, and wrong annotations.
 * Uses only information already present in the FileSpec (publicVariables, fileName, filePath).
 */
@Slf4j
public final class JavaFileTemplater {

    public enum TemplateType { ENUM, REPOSITORY, DTO, JWT_UTIL, SPA_CONTROLLER, NONE }

    private JavaFileTemplater() {}

    // JWT util files by any common name the LLM might use
    private static final java.util.Set<String> JWT_UTIL_FILENAMES = java.util.Set.of(
            "JwtUtil.java", "JwtUtils.java", "JwtService.java",
            "JwtTokenUtil.java", "JwtTokenService.java", "JwtTokenProvider.java",
            "JwtHelper.java", "JwtProvider.java"
    );

    // SPA forwarding controllers by any common name the LLM might use
    private static final java.util.Set<String> SPA_CONTROLLER_FILENAMES = java.util.Set.of(
            "SpaController.java", "SPAController.java", "SpaForwardController.java",
            "ForwardController.java", "WebController.java", "ClientRoutingController.java"
    );

    public static TemplateType classify(FileSpec spec, int layerPriority) {
        if (spec == null) return TemplateType.NONE;

        // JWT_UTIL: always template — LLM consistently generates the wrong API version.
        // Template is pinned to JJWT 0.11.5 which matches ProjectPlanningNode.injectJwtDependencies().
        if (spec.getFileName() != null && JWT_UTIL_FILENAMES.contains(spec.getFileName())) {
            return TemplateType.JWT_UTIL;
        }

        // SPA_CONTROLLER: always template. The mapping is a one-liner with exactly one correct
        // shape, and the wrong shapes are silent — a `/**` suffix swallows /assets/index.js so
        // the page loads blank, and @RestController returns the literal string "forward:/index.html"
        // instead of forwarding, so every deep link renders that text.
        if (spec.getFileName() != null && SPA_CONTROLLER_FILENAMES.contains(spec.getFileName())) {
            return TemplateType.SPA_CONTROLLER;
        }

        // ENUM (priority 8): must have declared constants in publicVariables
        if (layerPriority == 8
                && spec.getPublicVariables() != null
                && !spec.getPublicVariables().isEmpty()) {
            return TemplateType.ENUM;
        }

        // REPOSITORY (priority 40): only standard CRUD methods — no custom query logic
        if (layerPriority == 40 && hasOnlyStandardCrud(spec)) {
            return TemplateType.REPOSITORY;
        }

        // DTO (priorities 18 and 20): field list must be declared in publicVariables
        if ((layerPriority == 18 || layerPriority == 20)
                && spec.getPublicVariables() != null
                && !spec.getPublicVariables().isEmpty()) {
            return TemplateType.DTO;
        }

        return TemplateType.NONE;
    }

    public static String generate(FileSpec spec, String basePackage, TemplateType type) {
        return switch (type) {
            case ENUM       -> generateEnum(spec, basePackage);
            case REPOSITORY -> generateRepository(spec, basePackage);
            case DTO        -> generateDto(spec, basePackage);
            case JWT_UTIL   -> generateJwtUtil(spec);
            case SPA_CONTROLLER -> generateSpaController(spec);
            case NONE       -> null;
        };
    }

    /**
     * Generates a complete, correct JwtUtil using JJWT 0.11.5 API.
     * Pinned to match ProjectPlanningNode.injectJwtDependencies() which always injects 0.11.5.
     *
     * JJWT 0.11.5 key API differences from 0.12.x:
     *   - Jwts.parserBuilder()  (not Jwts.parser())
     *   - .setSigningKey(key)   (not .verifyWith(key))
     *   - .parseClaimsJws(token) (not .parseSignedClaims(token))
     *   - .getBody()            (not .getPayload())
     *   - Jwts.builder().setSubject() / .setExpiration() (not .subject() / .expiration())
     *   - signWith(key, SignatureAlgorithm.HS256)
     */
    /**
     * Forwards client-side routes to index.html so a deep link (/menu, /admin/orders) reaches
     * the React router instead of 404ing. The regex excludes any segment containing a dot, which
     * is what keeps /assets/index.js on the static resource handler.
     */
    private static String generateSpaController(FileSpec spec) {
        String pkg = extractPackage(spec.getFilePath());
        String className = spec.getFileName().replace(".java", "");
        log.info("[JavaFileTemplater] Generated SPA_CONTROLLER template for {}", spec.getFileName());
        return """
                package %s;

                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.GetMapping;

                @Controller
                public class %s {

                    // Match the SPA fallback at ANY depth: "/{path}" alone only matches a single
                    // segment, so deep links like /admin/trainers or /trainers/:id 404 on direct
                    // load/refresh. The "/**/{path}" variant covers nested routes. The [^\\.] guard
                    // keeps real static assets (*.js, *.css, *.webp) served by the resource handler,
                    // and explicit @*Mapping controllers still win over this wildcard (so /api/** is unaffected).
                    @GetMapping(value = {"/", "/{path:[^\\\\.]*}", "/**/{path:[^\\\\.]*}"})
                    public String forward() {
                        return "forward:/index.html";
                    }
                }
                """.formatted(pkg, className);
    }

    private static String generateJwtUtil(FileSpec spec) {
        String pkg = extractPackage(spec.getFilePath());
        String className = spec.getFileName().replace(".java", "");
        log.info("[JavaFileTemplater] Generated JWT_UTIL template for {} (JJWT 0.11.5 API)", spec.getFileName());
        return jwtUtilSource(pkg, className);
    }

    /**
     * Canonical JJWT 0.11.5 JwtUtil source — single source of truth, also used by the auth
     * scaffold module (AuthScaffoldModule). See generateJwtUtil above for the 0.11.5 vs 0.12.x
     * API notes that pin this template.
     */
    public static String jwtUtilSource(String pkg, String className) {
        return """
                package %s;

                import io.jsonwebtoken.Claims;
                import io.jsonwebtoken.Jwts;
                import io.jsonwebtoken.SignatureAlgorithm;
                import io.jsonwebtoken.security.Keys;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.security.core.userdetails.UserDetails;
                import org.springframework.stereotype.Component;

                import java.nio.charset.StandardCharsets;
                import java.security.Key;
                import java.util.Date;
                import java.util.HashMap;
                import java.util.Map;
                import java.util.function.Function;

                @Component
                public class %s {

                    @Value("${jwt.secret}")
                    private String secret;

                    @Value("${jwt.expiration-ms}")
                    private long expirationMs;

                    public String generateToken(UserDetails userDetails) {
                        return generateToken(new HashMap<>(), userDetails.getUsername());
                    }

                    public String generateToken(Map<String, Object> extraClaims, String subject) {
                        return Jwts.builder()
                                .setClaims(extraClaims)
                                .setSubject(subject)
                                .setIssuedAt(new Date())
                                .setExpiration(new Date(System.currentTimeMillis() + expirationMs))
                                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                                .compact();
                    }

                    public String extractUsername(String token) {
                        return extractClaim(token, Claims::getSubject);
                    }

                    public Date extractExpiration(String token) {
                        return extractClaim(token, Claims::getExpiration);
                    }

                    public <T> T extractClaim(String token, Function<Claims, T> resolver) {
                        return resolver.apply(extractAllClaims(token));
                    }

                    public boolean validateToken(String token, UserDetails userDetails) {
                        return extractUsername(token).equals(userDetails.getUsername())
                                && !extractExpiration(token).before(new Date());
                    }

                    private Claims extractAllClaims(String token) {
                        return Jwts.parserBuilder()
                                .setSigningKey(getSigningKey())
                                .build()
                                .parseClaimsJws(token)
                                .getBody();
                    }

                    private Key getSigningKey() {
                        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
                    }
                }
                """.formatted(pkg, className);
    }

    private static String generateEnum(FileSpec spec, String basePackage) {
        String pkg = extractPackage(spec.getFilePath());
        String className = spec.getFileName().replace(".java", "");
        String constants = spec.getPublicVariables().stream()
                .map(v -> "    " + v.getName().toUpperCase())
                .collect(Collectors.joining(",\n"));
        log.info("[JavaFileTemplater] Generated ENUM template for {}", spec.getFileName());
        return "package %s;\n\npublic enum %s {\n%s\n}\n".formatted(pkg, className, constants);
    }

    private static String generateRepository(FileSpec spec, String basePackage) {
        String pkg = extractPackage(spec.getFilePath());
        String className = spec.getFileName().replace(".java", "");
        String entityName = className.replace("Repository", "");
        String entityFqn = basePackage + ".model." + entityName;

        List<PublicFunction> custom = getCustomFunctions(spec);
        String customMethods = custom.isEmpty() ? "" :
                "\n" + custom.stream()
                        .map(f -> "    " + generateRepositoryMethodSignature(f, entityName) + ";")
                        .collect(Collectors.joining("\n")) + "\n";

        log.info("[JavaFileTemplater] Generated REPOSITORY template for {}", spec.getFileName());
        return """
                package %s;

                import %s;
                import org.springframework.data.jpa.repository.JpaRepository;
                import org.springframework.stereotype.Repository;
                import java.util.List;
                import java.util.Optional;
                import java.util.UUID;

                @Repository
                public interface %s extends JpaRepository<%s, UUID> {%s}
                """.formatted(pkg, entityFqn, className, entityName, customMethods);
    }

    private static String generateRepositoryMethodSignature(PublicFunction f, String entityName) {
        String name = f.getName();
        if (name == null || name.isBlank()) return "// unknown method";
        String lower = name.toLowerCase();
        String returnType;
        if (lower.startsWith("findallby") || lower.startsWith("getallby")) returnType = "List<" + entityName + ">";
        else if (lower.startsWith("findby") || lower.startsWith("getby")) returnType = "Optional<" + entityName + ">";
        else if (lower.startsWith("existsby")) returnType = "boolean";
        else if (lower.startsWith("countby")) returnType = "long";
        else if (lower.startsWith("deleteby") || lower.startsWith("removeby")) returnType = "void";
        else returnType = "Optional<" + entityName + ">";

        // Derive param name from method name (findByEmail → email)
        String paramName = "value";
        for (String prefix : List.of("findAllBy", "findBy", "getBy", "getAllBy",
                "existsBy", "countBy", "deleteBy", "removeBy")) {
            if (name.startsWith(prefix) && name.length() > prefix.length()) {
                String field = name.substring(prefix.length());
                paramName = Character.toLowerCase(field.charAt(0)) + field.substring(1);
                break;
            }
        }

        // Param type: use returnType declared in spec if available, else String
        String paramType = (f.getReturnType() != null && !f.getReturnType().isBlank())
                ? f.getReturnType() : "String";

        return returnType + " " + name + "(" + paramType + " " + paramName + ")";
    }

    private static String generateDto(FileSpec spec, String basePackage) {
        String pkg = extractPackage(spec.getFilePath());
        String className = spec.getFileName().replace(".java", "");
        String fields = spec.getPublicVariables().stream()
                .map(v -> "    private " + resolveJavaType(v.getType()) + " " + sanitizeFieldName(v.getName()) + ";")
                .collect(Collectors.joining("\n"));
        log.info("[JavaFileTemplater] Generated DTO template for {}", spec.getFileName());
        return """
                package %s;

                import jakarta.validation.constraints.*;
                import java.util.List;
                import java.util.UUID;
                import java.time.LocalDateTime;
                import lombok.Data;
                import lombok.Builder;
                import lombok.NoArgsConstructor;
                import lombok.AllArgsConstructor;

                @Data
                @Builder
                @NoArgsConstructor
                @AllArgsConstructor
                public class %s {
                %s
                }
                """.formatted(pkg, className, fields);
    }

    // Methods already provided by JpaRepository — no need to declare in the interface.
    // Derived queries (findByEmail, findByRazorpayOrderId, existsByUsername, etc.) are NOT
    // provided by JpaRepository and must be declared explicitly, even though they start with
    // findBy/existsBy. We use exact-name matching here to avoid incorrectly treating
    // findByRazorpayOrderId as a standard method.
    private static final java.util.Set<String> JPAREPOSITORY_PROVIDED = java.util.Set.of(
            "findAll", "findById", "save", "saveAll", "deleteById", "delete",
            "existsById", "count", "getById", "getReferenceById",
            "flush", "saveAndFlush", "deleteAll", "deleteAllById"
    );

    private static boolean hasOnlyStandardCrud(FileSpec spec) {
        if (spec.getPublicFunctions() == null || spec.getPublicFunctions().isEmpty()) return true;
        return spec.getPublicFunctions().stream()
                .allMatch(f -> f.getName() == null || JPAREPOSITORY_PROVIDED.contains(f.getName()));
    }

    private static List<PublicFunction> getCustomFunctions(FileSpec spec) {
        if (spec.getPublicFunctions() == null) return List.of();
        return spec.getPublicFunctions().stream()
                .filter(f -> f.getName() != null && !JPAREPOSITORY_PROVIDED.contains(f.getName()))
                .toList();
    }

    private static String extractPackage(String filePath) {
        int idx = filePath.indexOf("java/");
        if (idx < 0) return "";
        String after = filePath.substring(idx + 5);
        int lastSlash = after.lastIndexOf('/');
        return lastSlash < 0 ? "" : after.substring(0, lastSlash).replace('/', '.');
    }

    private static String resolveJavaType(String type) {
        if (type == null || type.isBlank()) return "String";
        return switch (type.toLowerCase().trim()) {
            case "string"                 -> "String";
            case "int", "integer"         -> "Integer";
            case "long"                   -> "Long";
            case "double"                 -> "Double";
            case "float"                  -> "Float";
            case "boolean"                -> "Boolean";
            case "uuid"                   -> "UUID";
            case "localdatetime", "timestamp" -> "LocalDateTime";
            case "localdate", "date"      -> "java.time.LocalDate";
            case "bigdecimal", "decimal"  -> "java.math.BigDecimal";
            default                       -> type; // enum types, nested types — keep as-is
        };
    }

    private static String sanitizeFieldName(String name) {
        if (name == null || name.isBlank()) return "value";
        // Convert UPPER_CASE to camelCase (LLM sometimes outputs enum-style names in publicVariables)
        if (name.contains("_")) {
            String[] parts = name.toLowerCase().split("_");
            StringBuilder sb = new StringBuilder(parts[0]);
            for (int i = 1; i < parts.length; i++) {
                if (!parts[i].isEmpty()) sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
            }
            return sb.toString();
        }
        return name;
    }
}
