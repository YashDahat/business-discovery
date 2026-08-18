package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured, machine-readable table of every FENCED foundation symbol, parsed from the hand-authored
 * contract cards on disk ({@code backend/FOUNDATION_CONTRACT.md} and
 * {@code frontend/FOUNDATION_CONTRACT.md}). Those cards are the deterministic, simplified projection of
 * the immutable auth / user / role / payment / exception spine (backend) and the auth-user / cart /
 * checkout / shell types (frontend).
 *
 * <p>This is the <b>SHAPE</b> registry — type → fields → exact types → enum values, plus the
 * cross-fence <b>reference convention</b> (how a domain model may point at a fenced aggregate). It is
 * deliberately DISTINCT from {@link JavaClassRegistry} / {@link TypeScriptExportRegistry}, which resolve
 * import <i>paths</i> (simple name → FQN) for the import fixers. This one carries no import paths.
 * Consumers:
 * <ul>
 *   <li>Phase 1 — fed to the planner as a closed fenced-symbol list ({@link #renderForPlanner()}).</li>
 *   <li>Phase 2 — the reconciler, which rewrites planned model fields to these exact shapes.</li>
 * </ul>
 *
 * <p>Parsing the contract cards (not the Lombok-annotated Java / TSX) is deliberate: the cards are the
 * stable projection the foundation already ships and regenerates with itself, so the registry
 * regenerates with the foundation. Absent/empty cards yield an empty registry (graceful degradation for
 * a foundation clone predating this feature) — downstream reconciliation then simply no-ops.
 */
@Slf4j
public final class FoundationSymbolRegistry {

    public enum Layer { BACKEND, FRONTEND }

    public enum Kind { CLASS, ENUM, INTERFACE, TYPE }

    /** One member of a fenced type. {@code optional} is the TS {@code ?} marker (always false on Java). */
    public record Field(String name, String type, boolean optional) {}

    public record Symbol(String name, Layer layer, Kind kind,
                         List<Field> fields, List<String> enumConstants) {
        public boolean isEnum() { return kind == Kind.ENUM; }
    }

    /** How a domain model references a fenced aggregate across the fence (no shared surrogate key). */
    public record ReferenceConvention(String aggregate, String handleField, String handleType, String note) {}

    private static final String BACKEND_REL  = "backend/FOUNDATION_CONTRACT.md";
    private static final String FRONTEND_REL = "frontend/FOUNDATION_CONTRACT.md";

    // Cross-fence reference conventions. Payment.referenceId is stated verbatim in the backend contract;
    // the User handle is Integer userId (= User.id, our logical unique key), resolved in controllers via
    // the foundation @CurrentUser resolver — NEVER a UUID, never email/phone stored as the link (those
    // are login + repository lookup keys only). Aligning to the Integer PK closes the Integer↔UUID
    // impedance. Encoded here so Phase 2 has a single, authoritative rewrite target (name + type).
    private static final List<ReferenceConvention> CONVENTIONS = List.of(
            new ReferenceConvention("User", "userId", "Integer",
                    "the platform user's id (= User.id); NEVER a UUID, never email/phone as the link — "
                  + "set it in controllers from @CurrentUser Integer userId; the frontend does not send it "
                  + "(the server derives the current user from the token); mirrors to number on the frontend"),
            new ReferenceConvention("Payment", "referenceId", "String",
                    "link a domain record to a payment by referenceId string; NO foreign key")
    );

    private final Map<String, Symbol> symbols;

    private FoundationSymbolRegistry(Map<String, Symbol> symbols) {
        this.symbols = symbols;
    }

    // ── Build ───────────────────────────────────────────────────────────────

    /** Builds the registry from the two contract cards in the cloned foundation workspace. */
    public static FoundationSymbolRegistry buildFromWorkspace(Path workspace) {
        Map<String, Symbol> out = new LinkedHashMap<>();
        parseContract(read(workspace.resolve(BACKEND_REL)),  Layer.BACKEND,  out);
        parseContract(read(workspace.resolve(FRONTEND_REL)), Layer.FRONTEND, out);
        if (out.isEmpty()) {
            log.warn("[FoundationSymbolRegistry] No fenced symbols parsed — foundation contract cards "
                    + "absent/empty under {} (registry empty; downstream reconciliation is a no-op)", workspace);
        } else {
            log.info("[FoundationSymbolRegistry] Parsed {} fenced symbols ({} backend, {} frontend)",
                    out.size(),
                    out.values().stream().filter(s -> s.layer() == Layer.BACKEND).count(),
                    out.values().stream().filter(s -> s.layer() == Layer.FRONTEND).count());
        }
        return new FoundationSymbolRegistry(out);
    }

    private static String read(Path file) {
        try {
            if (Files.isRegularFile(file)) return Files.readString(file);
        } catch (IOException e) {
            log.warn("[FoundationSymbolRegistry] Could not read {}: {}", file, e.getMessage());
        }
        return "";
    }

    // ── Parse ───────────────────────────────────────────────────────────────

    private static final Pattern FENCE = Pattern.compile("```[a-zA-Z]*\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern DECL  = Pattern.compile("\\b(class|interface|enum|type)\\s+([A-Za-z_]\\w*)");
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z_]\\w*$");
    private static final Pattern ENUM_CONST = Pattern.compile("^[A-Za-z_]\\w*");

    private static void parseContract(String md, Layer layer, Map<String, Symbol> out) {
        if (md == null || md.isBlank()) return;
        Matcher fence = FENCE.matcher(md);
        while (fence.find()) {
            parseCodeBlock(fence.group(1), layer, out);
        }
    }

    private static void parseCodeBlock(String code, Layer layer, Map<String, Symbol> out) {
        Matcher m = DECL.matcher(code);
        int from = 0;
        while (m.find(from)) {
            String keyword = m.group(1);
            String name = m.group(2);
            int brace = code.indexOf('{', m.end());
            if (brace < 0) { from = m.end(); continue; }
            int bodyEnd = matchingBrace(code, brace);
            if (bodyEnd < 0) { from = m.end(); continue; }
            String body = code.substring(brace + 1, bodyEnd);

            Kind kind = switch (keyword) {
                case "enum" -> Kind.ENUM;
                case "class" -> Kind.CLASS;
                case "interface" -> Kind.INTERFACE;
                default -> Kind.TYPE;
            };
            Symbol symbol = kind == Kind.ENUM
                    ? new Symbol(name, layer, kind, List.of(), parseEnumConstants(body))
                    : new Symbol(name, layer, kind,
                            layer == Layer.BACKEND ? parseJavaFields(body) : parseTsFields(body), List.of());
            out.putIfAbsent(name, symbol);   // first definition wins; the cards don't duplicate names
            from = bodyEnd + 1;
        }
    }

    private static List<String> parseEnumConstants(String body) {
        List<String> out = new ArrayList<>();
        for (String seg : splitTopLevel(body, ',')) {
            String s = stripComments(seg).trim();
            if (s.isEmpty()) continue;
            Matcher id = ENUM_CONST.matcher(s);
            if (id.find()) out.add(id.group());
        }
        return out;
    }

    private static List<Field> parseJavaFields(String body) {
        List<Field> out = new ArrayList<>();
        for (String seg : splitTopLevel(body, ';')) {
            String s = stripComments(seg).trim();
            if (s.isEmpty() || topLevelIndexOf(s, '(') >= 0) continue;   // skip methods/constructors
            String[] parts = s.split("\\s+");
            if (parts.length < 2) continue;
            String fname = parts[parts.length - 1];
            if (!IDENT.matcher(fname).matches()) continue;
            String ftype = String.join(" ", Arrays.copyOf(parts, parts.length - 1));
            out.add(new Field(fname, ftype, false));
        }
        return out;
    }

    private static List<Field> parseTsFields(String body) {
        List<Field> out = new ArrayList<>();
        for (String seg : splitTopLevel(body, ';')) {
            String s = stripComments(seg).trim();
            if (s.isEmpty()) continue;
            int colon = topLevelIndexOf(s, ':');
            if (colon < 0) continue;
            String fname = s.substring(0, colon).trim();
            boolean optional = fname.endsWith("?");
            if (optional) fname = fname.substring(0, fname.length() - 1).trim();
            if (!IDENT.matcher(fname).matches()) continue;
            out.add(new Field(fname, s.substring(colon + 1).trim(), optional));
        }
        return out;
    }

    // ── Depth-aware helpers (track () {} [] only — '<'/'>' avoided so TS '=>' is safe) ──

    private static List<String> splitTopLevel(String s, char delim) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{' || c == '(' || c == '[') depth++;
            else if (c == '}' || c == ')' || c == ']') { if (depth > 0) depth--; }
            else if (c == delim && depth == 0) { out.add(s.substring(start, i)); start = i + 1; }
        }
        if (start <= s.length()) out.add(s.substring(start));
        return out;
    }

    private static int topLevelIndexOf(String s, char target) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{' || c == '(' || c == '[') depth++;
            else if (c == '}' || c == ')' || c == ']') { if (depth > 0) depth--; }
            else if (c == target && depth == 0) return i;
        }
        return -1;
    }

    private static int matchingBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    private static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    public Optional<Symbol> get(String name) { return Optional.ofNullable(symbols.get(name)); }

    public boolean isFenced(String name) { return symbols.containsKey(name); }

    public Collection<Symbol> symbols() { return symbols.values(); }

    public List<ReferenceConvention> referenceConventions() { return CONVENTIONS; }

    public Optional<ReferenceConvention> referenceConventionFor(String aggregate) {
        return CONVENTIONS.stream().filter(c -> c.aggregate().equals(aggregate)).findFirst();
    }

    public boolean isEmpty() { return symbols.isEmpty(); }

    public int size() { return symbols.size(); }

    public long count(Layer layer) {
        return symbols.values().stream().filter(s -> s.layer() == layer).count();
    }

    // ── Render (Phase 1: feed the planner a closed fenced-symbol list) ──────────

    /** Compact, deterministic prompt block; {@code ""} when the registry is empty. */
    public String renderForPlanner() {
        if (symbols.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("── FENCED FOUNDATION SYMBOLS (closed set — bind to these EXACT shapes; ")
          .append("never re-declare them and never invent a divergent copy) ──\n");
        appendLayer(sb, Layer.BACKEND, "BACKEND");
        appendLayer(sb, Layer.FRONTEND, "FRONTEND");
        sb.append("CROSS-FENCE REFERENCE CONVENTION (no shared surrogate key crosses the fence):\n");
        for (ReferenceConvention c : CONVENTIONS) {
            sb.append("  - to reference ").append(c.aggregate()).append(" from a domain model, use `")
              .append(c.handleType()).append(' ').append(c.handleField()).append("` — ")
              .append(c.note()).append('\n');
        }
        return sb.toString();
    }

    private void appendLayer(StringBuilder sb, Layer layer, String label) {
        List<Symbol> layerSymbols = symbols.values().stream().filter(s -> s.layer() == layer).toList();
        if (layerSymbols.isEmpty()) return;
        sb.append(label).append(":\n");
        for (Symbol s : layerSymbols) {
            sb.append("  ").append(s.name()).append(" (").append(s.kind().name().toLowerCase()).append("): ");
            if (s.isEnum()) {
                sb.append(String.join(", ", s.enumConstants()));
            } else {
                List<String> members = new ArrayList<>();
                for (Field f : s.fields()) members.add(f.type() + " " + f.name() + (f.optional() ? "?" : ""));
                sb.append(String.join(", ", members));
            }
            sb.append('\n');
        }
    }
}
