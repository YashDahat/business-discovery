package com.business.discovery.worker.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Ground-truth model of the backend's API surface, extracted statically from the
 * generated (and compile-validated) Java source. The frontend's API-facing types and
 * services are DERIVED from this — never LLM-generated — so a second, hallucinated
 * copy of the contract cannot exist (the plan.features / wrong-endpoint / prefix-doubling
 * class of bug on multifit-aundh).
 *
 * Parsing relies on the regularity of our own generated code (templates + Lombok):
 * flat `private Type name;` fields, plain enums, `ResponseEntity<List<XDto>>` handler
 * signatures — verified against real multifit output. Anything unparseable is skipped
 * with a warning rather than guessed at.
 */
@Slf4j
public final class ApiInventory {

    // ── Model ─────────────────────────────────────────────────────────────

    public record Param(String name, String javaType) {}

    public record Endpoint(String httpMethod, String path, String handlerName,
                           String requestType,   // Java type name of @RequestBody, or null
                           String responseType,  // Java element type name, or null (void)
                           boolean responseIsList,
                           List<Param> pathParams) {}

    public record Field(String name, String javaType, boolean required) {}

    public record TypeDef(String name, boolean isEnum, List<Field> fields, List<String> enumConstants) {}

    // Foundation-spine controllers whose endpoints must NOT be turned into TypeScript SDK
    // functions. AuthController's login is handled by AuthContext directly; PaymentController's
    // endpoints are called by the payment checkout UI using the raw ApiContractCard (the LLM
    // sees the path + type info without needing a derived service wrapper here). Deriving
    // authService.ts would conflict with AuthContext's self-contained login logic.
    private static final java.util.Set<String> FOUNDATION_CONTROLLERS = java.util.Set.of(
            "AuthController.java",
            "PaymentController.java",
            "SpaController.java"
    );

    private final List<Endpoint> endpoints;
    private final Map<String, TypeDef> types; // by simple name

    private ApiInventory(List<Endpoint> endpoints, Map<String, TypeDef> types) {
        this.endpoints = endpoints;
        this.types = types;
    }

    public List<Endpoint> endpoints() { return endpoints; }
    public Map<String, TypeDef> types() { return types; }
    public boolean isEmpty() { return endpoints.isEmpty(); }

    // ── Extraction ────────────────────────────────────────────────────────

    private static final Pattern CLASS_MAPPING =
            Pattern.compile("@RequestMapping\\(\\s*(?:value\\s*=\\s*)?[\"']([^\"']*)[\"']");
    // annotation, then (across lines) the handler signature
    private static final Pattern HANDLER = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch)Mapping(?:\\(\\s*(?:value\\s*=\\s*)?[\"']([^\"']*)[\"'][^)]*\\)|\\(\\s*\\))?"
            + "[^{;]*?public\\s+([\\w<>,\\s\\[\\]?]+?)\\s+(\\w+)\\s*\\(([^)]*)\\)",
            Pattern.DOTALL);
    private static final Pattern REQUEST_BODY =
            Pattern.compile("@RequestBody\\s+(?:@\\w+\\s+)*([A-Z][\\w]*)");
    private static final Pattern FIELD = Pattern.compile(
            "((?:@\\w+(?:\\([^)]*\\))?\\s+)*)private\\s+([\\w<>,\\s\\[\\]?.]+?)\\s+(\\w+)\\s*;");
    private static final Pattern ENUM_DECL =
            Pattern.compile("public\\s+enum\\s+(\\w+)\\s*\\{([^}]*)}", Pattern.DOTALL);
    private static final Pattern CLASS_DECL = Pattern.compile("(?:class|record)\\s+(\\w+)");
    private static final Pattern PATH_PARAM = Pattern.compile("\\{(\\w+)}");
    private static final Pattern PATH_VARIABLE_ARG =
            Pattern.compile("@PathVariable(?:\\([^)]*\\))?\\s+(?:@\\w+\\s+)*([A-Za-z][\\w]*)\\s+(\\w+)");

    public static ApiInventory extract(Path backendSrcJava) {
        List<Endpoint> endpoints = new ArrayList<>();
        Map<String, TypeDef> types = new LinkedHashMap<>();
        if (backendSrcJava == null || !Files.exists(backendSrcJava)) {
            return new ApiInventory(endpoints, types);
        }
        try (Stream<Path> s = Files.walk(backendSrcJava)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".java")).toList()) {
                String content;
                try {
                    content = Files.readString(f);
                } catch (IOException e) {
                    continue;
                }
                String fileName = f.getFileName().toString();
                if (fileName.endsWith("Controller.java")) {
                    // Skip foundation-spine controllers — their endpoints are handled by
                    // AuthContext (login) and the payment scaffold (createOrder/verify/webhook).
                    // Deriving an authService.ts or paymentService.ts from these would conflict
                    // with the foundation's self-contained AuthContext and the payment spine.
                    if (FOUNDATION_CONTROLLERS.contains(fileName)) continue;
                    parseController(content, endpoints);
                }
                // DTOs and model classes/enums both feed the type table.
                // Foundation DTOs (AuthRequest, AuthResponse, payment DTOs) ARE included so
                // generated code that depends on them gets correct type info — they're just not
                // exposed as standalone service SDK functions.
                String dir = f.getParent().getFileName() != null ? f.getParent().getFileName().toString() : "";
                if (dir.equals("dto") || dir.equals("model")) {
                    parseType(content, types);
                }
            }
        } catch (IOException e) {
            log.warn("[ApiInventory] Walk failed: {}", e.getMessage());
        }
        log.info("[ApiInventory] Extracted {} endpoints, {} types", endpoints.size(), types.size());
        return new ApiInventory(endpoints, types);
    }

    private static void parseController(String content, List<Endpoint> endpoints) {
        Matcher cm = CLASS_MAPPING.matcher(content);
        String base = cm.find() ? cm.group(1) : "";

        Matcher h = HANDLER.matcher(content);
        while (h.find()) {
            String httpMethod = h.group(1).toUpperCase();
            String subPath = h.group(2) != null ? h.group(2) : "";
            String returnDecl = h.group(3).trim();
            String handlerName = h.group(4);
            String args = h.group(5);

            String fullPath = joinPath(base, subPath);

            // Unwrap ResponseEntity<...>, then List<...>/Set<...>
            String response = returnDecl;
            Matcher re = Pattern.compile("ResponseEntity\\s*<(.+)>", Pattern.DOTALL).matcher(response);
            if (re.find()) response = re.group(1).trim();
            boolean isList = false;
            Matcher li = Pattern.compile("(?:List|Set|Collection)\\s*<(.+)>", Pattern.DOTALL).matcher(response);
            if (li.find()) { isList = true; response = li.group(1).trim(); }
            response = response.replaceAll("\\s", "");
            if (response.equals("Void") || response.equals("void") || response.equals("?")) response = null;
            if (response != null && response.contains("<")) response = null; // nested generics — skip typing

            Matcher rb = REQUEST_BODY.matcher(args);
            String requestType = rb.find() ? rb.group(1) : null;

            // Path params: names from the path, Java types from @PathVariable args (default String)
            Map<String, String> argTypes = new LinkedHashMap<>();
            Matcher pv = PATH_VARIABLE_ARG.matcher(args);
            while (pv.find()) argTypes.put(pv.group(2), pv.group(1));

            List<Param> pathParams = new ArrayList<>();
            Matcher pp = PATH_PARAM.matcher(fullPath);
            while (pp.find()) {
                String pname = pp.group(1);
                pathParams.add(new Param(pname, argTypes.getOrDefault(pname, "String")));
            }

            if (args.contains("@RequestParam")) {
                log.warn("[ApiInventory] {} {} uses @RequestParam — query params are not yet "
                        + "represented in the generated SDK", httpMethod, fullPath);
            }

            endpoints.add(new Endpoint(httpMethod, fullPath, handlerName, requestType,
                    response, isList, pathParams));
        }
    }

    private static void parseType(String content, Map<String, TypeDef> types) {
        Matcher en = ENUM_DECL.matcher(content);
        if (en.find()) {
            String name = en.group(1);
            List<String> constants = new ArrayList<>();
            // constants end at the first ';' (methods/fields may follow) or the body end
            String body = en.group(2);
            int semi = body.indexOf(';');
            if (semi >= 0) body = body.substring(0, semi);
            for (String c : body.split(",")) {
                String t = c.trim().replaceAll("\\(.*", "").trim(); // strip constructor args
                if (t.matches("[A-Z][A-Z0-9_]*")) constants.add(t);
            }
            if (!constants.isEmpty()) {
                types.put(name, new TypeDef(name, true, List.of(), constants));
            }
            return;
        }

        Matcher cd = CLASS_DECL.matcher(content);
        if (!cd.find()) return;
        String name = cd.group(1);

        List<Field> fields = new ArrayList<>();
        Matcher fm = FIELD.matcher(content);
        while (fm.find()) {
            String annotations = fm.group(1) != null ? fm.group(1) : "";
            String javaType = fm.group(2).trim();
            String fieldName = fm.group(3);
            // static/serial fields and logger-ish members don't cross the wire
            if (fieldName.equals("serialVersionUID") || javaType.contains("Logger")) continue;
            boolean required = annotations.contains("@NotNull")
                    || annotations.contains("@NotBlank")
                    || annotations.contains("@NotEmpty")
                    || annotations.contains("@Id");
            fields.add(new Field(fieldName, javaType, required));
        }
        if (!fields.isEmpty()) {
            types.put(name, new TypeDef(name, false, fields, List.of()));
        }
    }

    static String joinPath(String base, String sub) {
        String b = base == null ? "" : base.trim();
        String s = sub == null ? "" : sub.trim();
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (!s.isEmpty() && !s.startsWith("/")) s = "/" + s;
        String joined = b + s;
        return joined.isEmpty() ? "/" : joined;
    }

    // ── Evidence ──────────────────────────────────────────────────────────

    public void writeJson(Path workspace) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            ArrayNode eps = root.putArray("endpoints");
            for (Endpoint e : endpoints) {
                ObjectNode n = eps.addObject();
                n.put("method", e.httpMethod());
                n.put("path", e.path());
                n.put("handler", e.handlerName());
                n.put("request", e.requestType());
                n.put("response", e.responseType() == null ? null
                        : e.responseType() + (e.responseIsList() ? "[]" : ""));
            }
            ObjectNode ts = root.putObject("types");
            types.forEach((name, def) -> {
                ObjectNode n = ts.putObject(name);
                if (def.isEnum()) {
                    ArrayNode c = n.putArray("constants");
                    def.enumConstants().forEach(c::add);
                } else {
                    ObjectNode fs = n.putObject("fields");
                    def.fields().forEach(fl -> fs.put(fl.name(),
                            fl.javaType() + (fl.required() ? "" : "?")));
                }
            });
            Path out = workspace.resolve("docs/API_INVENTORY.json");
            Files.createDirectories(out.getParent());
            Files.writeString(out, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (IOException e) {
            log.warn("[ApiInventory] Could not write API_INVENTORY.json: {}", e.getMessage());
        }
    }
}
