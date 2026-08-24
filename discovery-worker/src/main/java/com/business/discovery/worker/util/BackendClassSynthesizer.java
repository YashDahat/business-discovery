package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Enforcement Point B for the backend — the repair half, driven off {@code mvn compile} output.
 * {@code javac} already <em>detects</em> a missing producer as "cannot find symbol: class X"; this
 * resolves each such X <b>deterministically</b>, so the compile manifest handed to the ErrorFixAgent
 * shrinks monotonically instead of a naive fix <em>adding</em> errors (the F7 defect classes):
 *
 * <ol>
 *   <li><b>JDK/stdlib → import, don't synthesize.</b> A missing {@code LocalTime}/{@code BigDecimal}/…
 *       is not a project type — the file merely forgot the import. We inject the canonical
 *       {@code java.*} import into the referencer rather than fabricating a {@code dto.LocalTime}
 *       stub (which then collides with real usage and diverges the fix loop).</li>
 *   <li><b>One canonical package per simple name.</b> The same unresolved name referenced from two
 *       packages is synthesized <em>once</em> and every other referencer is pointed at that canonical
 *       FQN by an injected/reconciled import — never a dual-package duplicate.</li>
 *   <li><b>Kind inference (enum vs class).</b> A type used as a value set ({@code @Enumerated},
 *       {@code X.values()}/{@code valueOf}, {@code X.CONSTANT}) is synthesized as an {@code enum} with
 *       the observed constants, closing the {@code MenuCategory}-as-empty-class recurrence.</li>
 * </ol>
 *
 * <p>Unlike the frontend permissive stub (TS {@code any} closes everything), Java has no equivalent,
 * so a placeholder class resolves the type reference but not every getter/constructor a consumer may
 * call. That is still a real win: it turns an "author a whole file" problem (which the ErrorFixAgent
 * fails at — {@code AdminLayout} survived 3 attempts) into an "add methods to an existing class"
 * problem, which the agent handles well. See {@code docs/architecture-json-completeness-plan.md} §8
 * and {@code docs/frontend-error-patterns-abs-fitness.md} §F7.
 */
@Slf4j
public final class BackendClassSynthesizer {

    /** javac's {@code symbol: class X} followed by {@code location: class <FQN>}. */
    private static final Pattern MISSING_CLASS = Pattern.compile(
            "symbol:\\s+class\\s+(\\w+)\\s*\\R\\s*location:\\s+class\\s+([\\w.$]+)");

    /**
     * JDK/standard-library simple name → canonical FQN. When javac reports one of these as a missing
     * CLASS, the referencer merely forgot to import it — inject the import, never fabricate a stub.
     * Deliberately limited to UNAMBIGUOUS {@code java.*} types; jakarta/Spring annotation names map to
     * several packages and are handled at generation time (JavaImportResolver / generator imports), so
     * they are intentionally excluded here to avoid a wrong-package guess.
     */
    static final Map<String, String> JDK_TYPES = buildJdkTypes();

    private static Map<String, String> buildJdkTypes() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String t : new String[]{"LocalDate", "LocalTime", "LocalDateTime", "Instant", "Duration",
                "Period", "ZonedDateTime", "OffsetDateTime", "OffsetTime", "ZoneId", "ZoneOffset",
                "DayOfWeek", "Month", "MonthDay", "Year", "YearMonth"}) m.put(t, "java.time." + t);
        m.put("DateTimeFormatter", "java.time.format.DateTimeFormatter");
        m.put("BigDecimal", "java.math.BigDecimal");
        m.put("BigInteger", "java.math.BigInteger");
        for (String t : new String[]{"List", "ArrayList", "LinkedList", "Map", "HashMap", "LinkedHashMap",
                "TreeMap", "Set", "HashSet", "LinkedHashSet", "TreeSet", "Collection", "Optional", "UUID",
                "Objects", "Arrays", "Collections", "Comparator", "Iterator", "Queue", "Deque",
                "EnumMap", "EnumSet", "Currency"}) m.put(t, "java.util." + t);
        m.put("Stream", "java.util.stream.Stream");
        m.put("Collectors", "java.util.stream.Collectors");
        m.put("IntStream", "java.util.stream.IntStream");
        m.put("CompletableFuture", "java.util.concurrent.CompletableFuture");
        m.put("ConcurrentHashMap", "java.util.concurrent.ConcurrentHashMap");
        m.put("TimeUnit", "java.util.concurrent.TimeUnit");
        return m;
    }

    /** Package segments whose last element marks a "types live here" package — canonical-package tiebreak. */
    private static final Set<String> TYPE_PACKAGE_SEGMENTS = Set.of(
            "dto", "dtos", "model", "models", "domain", "entity", "entities",
            "response", "responses", "request", "requests", "vo", "payload", "payloads");

    private BackendClassSynthesizer() {}

    /**
     * Resolves every "cannot find symbol: class X" from the compile output — by injecting a JDK import,
     * pointing a referencer at the canonical project package, or synthesizing a single placeholder
     * class/enum where none exists. Returns the number of file mutations made (classes/enums written +
     * imports injected). Never throws.
     */
    public static int synthesize(Path backendSrcJava, String compileOutput) {
        if (compileOutput == null || compileOutput.isBlank() || !Files.exists(backendSrcJava)) return 0;

        // 1. Collect: missing simple name → referencer FQNs (sorted keys for deterministic processing).
        Map<String, Set<String>> missing = new TreeMap<>();
        Matcher m = MISSING_CLASS.matcher(compileOutput);
        while (m.find()) {
            missing.computeIfAbsent(m.group(1), k -> new LinkedHashSet<>()).add(m.group(2));
        }
        if (missing.isEmpty()) return 0;

        // 2. One-time source scan — existing-file lookup, enum-usage inference, canonical resolution.
        List<SrcFile> sources = scanSources(backendSrcJava);

        int changes = 0;
        for (Map.Entry<String, Set<String>> e : missing.entrySet()) {
            String name = e.getKey();
            Set<String> referencers = e.getValue();

            // 2a. JDK/stdlib type → inject the canonical import; never synthesize (kills dto.LocalTime).
            // A project class of the same name shadows the JDK type (a domain `Month`), so only take the
            // JDK path when no project file declares it — otherwise fall through to canonicalization.
            String jdkFqn = JDK_TYPES.get(name);
            if (jdkFqn != null && sources.stream().noneMatch(s -> s.className().equals(name))) {
                for (String ref : sorted(referencers)) {
                    if (addImportToReferencer(backendSrcJava, ref, jdkFqn, name)) changes++;
                }
                continue;
            }

            // 2b. Project type → one canonical package; synthesize once, point everyone else at it.
            String canonicalPkg = canonicalPackage(name, referencers, sources, backendSrcJava);
            if (canonicalPkg.isEmpty()) continue;                  // no discernible project package
            String canonicalFqn = canonicalPkg + "." + name;

            Path target = backendSrcJava.resolve(canonicalPkg.replace('.', '/')).resolve(name + ".java");
            if (!Files.exists(target) && writeSynthesized(target, canonicalPkg, name, sources)) changes++;

            for (String ref : sorted(referencers)) {
                String refPkg = packageOf(ref);
                if (refPkg.isEmpty() || refPkg.equals(canonicalPkg)) continue;   // same package → no import
                if (addImportToReferencer(backendSrcJava, ref, canonicalFqn, name)) changes++;
            }
        }
        return changes;
    }

    // ── Canonical-package resolution ──────────────────────────────────────────

    /**
     * The single package a project simple name resolves to: an existing file with that name wins;
     * else a referencer's import of it; else a "types" package among the referencers; else the
     * lexicographically smallest referencer package. Empty when no referencer has a project package.
     */
    private static String canonicalPackage(String name, Set<String> referencers,
                                           List<SrcFile> sources, Path root) {
        for (SrcFile s : sources) {                                // (1) existing declaration wins
            if (s.className().equals(name)) return s.pkg();
        }
        for (String ref : sorted(referencers)) {                   // (2) a referencer's explicit import
            String imported = importedPackageOf(root, ref, name);
            if (imported != null) return imported;
        }
        TreeSet<String> pkgs = new TreeSet<>();                     // (3) among referencer packages
        for (String ref : referencers) {
            String p = packageOf(ref);
            if (!p.isEmpty()) pkgs.add(p);
        }
        for (String p : pkgs) if (isTypePackage(p)) return p;       // prefer a dto/model/... package
        return pkgs.isEmpty() ? "" : pkgs.first();                  // else deterministic fallback
    }

    private static boolean isTypePackage(String pkg) {
        int dot = pkg.lastIndexOf('.');
        return TYPE_PACKAGE_SEGMENTS.contains(dot < 0 ? pkg : pkg.substring(dot + 1));
    }

    /** The package a referencer imports {@code name} from, if it declares such an import; else null. */
    private static String importedPackageOf(Path root, String refFqn, String name) {
        Path file = referencerFile(root, refFqn);
        if (file == null) return null;
        try {
            Matcher im = Pattern.compile("import\\s+([\\w.]+)\\." + Pattern.quote(name) + "\\s*;")
                    .matcher(Files.readString(file));
            if (im.find()) return im.group(1);
        } catch (IOException ignored) {
            // fall through
        }
        return null;
    }

    // ── Import injection / reconciliation ─────────────────────────────────────

    /**
     * Ensures the referencer imports {@code fqn}. Adds the import when absent; reconciles a
     * wrong-package import of the same simple name to {@code fqn} (a dual-package reference).
     * Returns true iff the file was changed.
     */
    private static boolean addImportToReferencer(Path root, String refFqn, String fqn, String simpleName) {
        Path file = referencerFile(root, refFqn);
        if (file == null) return false;
        try {
            String content = Files.readString(file);
            String updated = insertOrReplaceImport(content, fqn, simpleName);
            if (updated.equals(content)) return false;
            Files.writeString(file, updated);
            log.info("[BackendClassSynthesizer] Import {} into {}", fqn, refFqn);
            return true;
        } catch (IOException ex) {
            log.warn("[BackendClassSynthesizer] Could not add import to {}: {}", refFqn, ex.getMessage());
            return false;
        }
    }

    /** Adds {@code import fqn;} after the last import (or the package line); reconciles a same-name import. */
    static String insertOrReplaceImport(String content, String fqn, String simpleName) {
        List<String> lines = new ArrayList<>(List.of(content.split("\n", -1)));
        Pattern sameName = Pattern.compile("^\\s*import\\s+([\\w.]+)\\." + Pattern.quote(simpleName) + "\\s*;\\s*$");
        String fqnPkg = fqnPkg(fqn);
        int lastImport = -1, pkgLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            Matcher sm = sameName.matcher(lines.get(i));
            if (sm.matches()) {
                if (sm.group(1).equals(fqnPkg)) return content;    // already imported from the canonical package
                lines.set(i, "import " + fqn + ";");               // reconcile a wrong-package import
                return String.join("\n", lines);
            }
            String t = lines.get(i).trim();
            if (t.startsWith("import ") && t.endsWith(";")) lastImport = i;
            else if (pkgLine < 0 && t.startsWith("package ")) pkgLine = i;
        }
        int insertAt = lastImport >= 0 ? lastImport + 1 : (pkgLine >= 0 ? pkgLine + 1 : 0);
        lines.add(insertAt, "import " + fqn + ";");
        return String.join("\n", lines);
    }

    // ── Synthesis (class / enum / exception) ──────────────────────────────────

    private static boolean writeSynthesized(Path target, String pkg, String name, List<SrcFile> sources) {
        List<String> constants = enumConstants(name, sources);
        String source = constants.isEmpty() ? classSource(pkg, name) : enumSource(pkg, name, constants);
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target, source);
            log.info("[BackendClassSynthesizer] Synthesized placeholder {} {}.{}",
                    constants.isEmpty() ? "class" : "enum", pkg, name);
            return true;
        } catch (IOException e) {
            log.warn("[BackendClassSynthesizer] Could not write {}.{}: {}", pkg, name, e.getMessage());
            return false;
        }
    }

    /**
     * Enum constants observed for {@code name}, or empty when it is not used as a value set. Requires an
     * explicit enum signal ({@code @Enumerated} on a field of this type, or {@code name.values()} /
     * {@code name.valueOf(}) AND at least one {@code name.CONSTANT} reference, so a constants-holder
     * class is not misread as an enum. Never treats a {@code *Exception} as an enum.
     */
    static List<String> enumConstants(String name, List<SrcFile> sources) {
        if (name.endsWith("Exception")) return List.of();
        Pattern enumerated = Pattern.compile("@Enumerated[\\s\\S]{0,80}?\\b" + Pattern.quote(name) + "\\b");
        Pattern staticCall = Pattern.compile("\\b" + Pattern.quote(name) + "\\.(?:values|valueOf)\\s*\\(");
        Pattern constRef  = Pattern.compile("\\b" + Pattern.quote(name) + "\\.([A-Z][A-Z0-9_]+)\\b");

        boolean enumSignal = false;
        Set<String> constants = new LinkedHashSet<>();
        for (SrcFile s : sources) {
            if (!s.content().contains(name)) continue;
            if (enumerated.matcher(s.content()).find() || staticCall.matcher(s.content()).find()) enumSignal = true;
            Matcher cm = constRef.matcher(s.content());
            while (cm.find()) constants.add(cm.group(1));
        }
        return (enumSignal && !constants.isEmpty()) ? new ArrayList<>(constants) : List.of();
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

    static String enumSource(String pkg, String name, List<String> constants) {
        return "package " + pkg + ";\n\n"
                + "// AUTO-GENERATED placeholder enum — referenced as a value set but never generated (Point B synthesis).\n"
                + "// Constants inferred from usage; add/adjust to match the real type.\n"
                + "public enum " + name + " {\n    " + String.join(",\n    ", constants) + "\n}\n";
    }

    // ── Path / package helpers ────────────────────────────────────────────────

    /** The .java file that declares the top-level class of a referencer FQN, or null if it is absent. */
    private static Path referencerFile(Path root, String refFqn) {
        String pkg = packageOf(refFqn);
        if (pkg.isEmpty()) return null;
        Path file = root.resolve(pkg.replace('.', '/')).resolve(topClass(refFqn, pkg) + ".java");
        return Files.exists(file) ? file : null;
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

    private static String fqnPkg(String fqn) {
        int d = fqn.lastIndexOf('.');
        return d < 0 ? "" : fqn.substring(0, d);
    }

    private static List<String> sorted(Set<String> s) {
        List<String> out = new ArrayList<>(s);
        out.sort(null);
        return out;
    }

    // ── Source scan ───────────────────────────────────────────────────────────

    private record SrcFile(String pkg, String className, String content) {}

    private static List<SrcFile> scanSources(Path root) {
        List<SrcFile> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java"))
                 .sorted()
                 .forEach(p -> {
                     try {
                         String content = Files.readString(p);
                         out.add(new SrcFile(readPackage(content), fileClassName(p), content));
                     } catch (IOException ignored) {
                         // unreadable file — skip
                     }
                 });
        } catch (IOException e) {
            log.warn("[BackendClassSynthesizer] Could not scan sources: {}", e.getMessage());
        }
        return out;
    }

    private static final Pattern PACKAGE_DECL = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    private static String readPackage(String content) {
        Matcher m = PACKAGE_DECL.matcher(content);
        return m.find() ? m.group(1) : "";
    }

    private static String fileClassName(Path p) {
        String f = p.getFileName().toString();
        return f.endsWith(".java") ? f.substring(0, f.length() - 5) : f;
    }
}
