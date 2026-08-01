package com.business.discovery.worker.util;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.stream.Stream;

/**
 * Harvests every {@code data-testid} literal the frontend renders into docs/E2E_SELECTORS.json,
 * grouped by source file (relative to frontend/src). This is the "html elements to navigate"
 * context {@link com.business.discovery.worker.service.PlaywrightSpecGenerator} feeds the spec
 * LLM — the exact stable selectors available on each page/component — and ships as a debugging
 * artifact. Deterministic, zero LLM. Modeled on {@link UiComponentInventory}.
 *
 * <p>Only literal testids are captured. Dynamic ones ({@code data-testid={`plan-card-${i}`}}) are
 * not — but the generation rule assigns repeated list/card items the SAME literal testid (tests
 * select by index), so the literal stem is what appears here.
 */
@Slf4j
public final class TestIdInventory {

    private TestIdInventory() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // data-testid="x" | data-testid='x' | data-testid={"x"} | data-testid={'x'} | data-testid={`x`}
    private static final Pattern TESTID = Pattern.compile(
            "data-testid\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)'|\\{\\s*[\"'`]([^\"'`]+)[\"'`]\\s*})");

    /** @return file (relative to frontend/src) → the testids it renders, insertion-ordered. */
    public static Map<String, Set<String>> build(Path frontendSrc) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        if (frontendSrc == null || !Files.isDirectory(frontendSrc)) return out;
        try (Stream<Path> files = Files.walk(frontendSrc)) {
            files.filter(p -> p.toString().endsWith(".tsx") || p.toString().endsWith(".ts"))
                 .sorted()
                 .forEach(p -> {
                     try {
                         Set<String> ids = extract(Files.readString(p));
                         if (!ids.isEmpty()) out.put(frontendSrc.relativize(p).toString(), ids);
                     } catch (IOException ignored) {
                         // unreadable file — skip
                     }
                 });
        } catch (IOException e) {
            log.warn("[TestIdInventory] walk failed: {}", e.getMessage());
        }
        return out;
    }

    static Set<String> extract(String content) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = TESTID.matcher(content);
        while (m.find()) {
            String id = m.group(1) != null ? m.group(1)
                      : m.group(2) != null ? m.group(2)
                      : m.group(3);
            if (id != null && !id.isBlank()) ids.add(id.trim());
        }
        return ids;
    }

    /** Writes docs/E2E_SELECTORS.json under the workspace; returns the harvested map. */
    public static Map<String, Set<String>> writeJson(Path workspace) {
        Map<String, Set<String>> inv = build(workspace.resolve("frontend/src"));
        try {
            Path out = workspace.resolve("docs/E2E_SELECTORS.json");
            Files.createDirectories(out.getParent());
            Files.writeString(out, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(inv));
            log.info("[TestIdInventory] Wrote docs/E2E_SELECTORS.json ({} files with testids)", inv.size());
        } catch (IOException e) {
            log.warn("[TestIdInventory] Could not write E2E_SELECTORS.json: {}", e.getMessage());
        }
        return inv;
    }
}
