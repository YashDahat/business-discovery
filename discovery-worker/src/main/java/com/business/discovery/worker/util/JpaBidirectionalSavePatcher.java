package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Fixes the JPA bidirectional-relationship save bug before compile, deterministically and with no
 * LLM cost. When a service populates a parent's {@code @OneToMany(cascade=…)} collection and then
 * saves the parent WITHOUT first setting each child's owning-side {@code @ManyToOne} back-reference,
 * the cascade inserts the children with a null foreign key and the request 500s at runtime
 * (circuit-house: {@code null value in column "order_id" of relation "order_items"}). The bug
 * compiles and boots fine, so only a real write path hits it.
 *
 * <p>The patcher reads the entity graph to learn each bidirectional pair (parent collection setter +
 * child back-reference setter), then finds {@code parent.setChildren(list); … repo.save(parent)} in
 * the services and injects {@code list.forEach(c -> c.setParent(parent))} immediately before the
 * save. Idempotent, and it leaves already-correct code untouched.
 */
@Slf4j
public final class JpaBidirectionalSavePatcher {

    private JpaBidirectionalSavePatcher() {}

    // @OneToMany(... mappedBy = "x" ...) [other annotations] private List<Child> field;
    private static final Pattern ONE_TO_MANY = Pattern.compile(
            "@OneToMany\\s*\\(([^)]*)\\)[\\s\\S]{0,200}?(?:List|Set|Collection)\\s*<\\s*(\\w+)\\s*>\\s+(\\w+)\\s*;");
    private static final Pattern MAPPED_BY = Pattern.compile("mappedBy\\s*=\\s*\"(\\w+)\"");

    /** One bidirectional pair: parent exposes {@code collectionSetter}; child exposes {@code backRefSetter}. */
    private record Relationship(String collectionSetter, String backRefSetter) {}

    /** @return true if any service file was changed. */
    public static boolean fix(Path backendSrcJava) {
        if (backendSrcJava == null || !Files.isDirectory(backendSrcJava)) return false;

        List<Relationship> relationships = scanRelationships(backendSrcJava);
        if (relationships.isEmpty()) return false;

        boolean[] changed = {false};
        try (Stream<Path> files = Files.walk(backendSrcJava)) {
            files.filter(p -> p.toString().endsWith(".java"))
                 .filter(p -> p.getFileName().toString().contains("Service"))
                 .forEach(p -> {
                     if (patchFile(p, relationships)) changed[0] = true;
                 });
        } catch (IOException e) {
            log.warn("[JpaBidirectionalSavePatcher] walk failed: {}", e.getMessage());
        }
        return changed[0];
    }

    private static List<Relationship> scanRelationships(Path root) {
        List<Relationship> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String content;
                try {
                    content = Files.readString(p);
                } catch (IOException e) {
                    return;
                }
                Matcher m = ONE_TO_MANY.matcher(content);
                while (m.find()) {
                    Matcher mb = MAPPED_BY.matcher(m.group(1));
                    if (!mb.find()) continue; // unidirectional @OneToMany — not this bug
                    String collectionSetter = setter(m.group(3)); // field name -> setItems
                    String backRefSetter = setter(mb.group(1));   // mappedBy -> setOrder
                    if (seen.add(collectionSetter + "|" + backRefSetter)) {
                        out.add(new Relationship(collectionSetter, backRefSetter));
                    }
                }
            });
        } catch (IOException e) {
            log.warn("[JpaBidirectionalSavePatcher] entity scan failed: {}", e.getMessage());
        }
        return out;
    }

    private static boolean patchFile(Path file, List<Relationship> relationships) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            return false;
        }
        String original = content;
        for (Relationship rel : relationships) {
            content = applyRelationship(content, rel);
        }
        if (content.equals(original)) return false;
        try {
            Files.writeString(file, content);
            log.info("[JpaBidirectionalSavePatcher] Set child back-reference before save in {}", file.getFileName());
            return true;
        } catch (IOException e) {
            log.warn("[JpaBidirectionalSavePatcher] write failed for {}: {}", file, e.getMessage());
            return false;
        }
    }

    private static String applyRelationship(String content, Relationship rel) {
        Pattern setCollection = Pattern.compile(
                "(\\w+)\\." + Pattern.quote(rel.collectionSetter()) + "\\(\\s*(\\w+)\\s*\\)");
        Matcher m = setCollection.matcher(content);
        while (m.find()) {
            String parentVar = m.group(1);
            String listVar = m.group(2);

            Pattern save = Pattern.compile("\\b\\w+\\.save\\(\\s*" + Pattern.quote(parentVar) + "\\s*\\)");
            Matcher sm = save.matcher(content);
            if (!sm.find(m.end())) continue;
            int saveStart = sm.start();

            // Already set (hand-written correct code, or a previous run of this patcher)? leave it.
            Pattern alreadySet = Pattern.compile(
                    Pattern.quote(rel.backRefSetter()) + "\\(\\s*" + Pattern.quote(parentVar) + "\\s*\\)");
            if (alreadySet.matcher(content.substring(0, saveStart)).find()) continue;

            int lineStart = content.lastIndexOf('\n', saveStart) + 1;
            String indent = leadingWhitespace(content.substring(lineStart, saveStart));
            String injection = indent + listVar + ".forEach(__child -> __child."
                    + rel.backRefSetter() + "(" + parentVar + "));\n";
            return content.substring(0, lineStart) + injection + content.substring(lineStart);
        }
        return content;
    }

    private static String leadingWhitespace(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(0, i);
    }

    private static String setter(String field) {
        if (field.isEmpty()) return "set";
        return "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }
}
