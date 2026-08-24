package com.business.discovery.worker.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clusters a compiler's FULL error output by root cause, so the ErrorFixAgent sees every
 * error in compact, groupable form instead of a truncated slice.
 *
 * <p>The 6000-char seed window on raw tsc output routinely truncated to "8 of 57" — and the
 * agent cannot cluster or bulk-fix errors it never sees, so the loop exhausted 30 rounds
 * without converging (farmaaish-restaurant 2026-08). This groups errors by
 * {@code (error code + subject symbol)} and lists the full set of affected files per cluster,
 * which maps directly onto the agent's {@code bulk_str_replace} tool. Under a tight budget the
 * per-cluster detail collapses to a one-line summary rather than dropping clusters — every
 * root cause is always represented (completeness over depth).
 *
 * <p>tsc format only ({@code path(line,col): error TSxxxx: msg}). Non-tsc output (javac / mvn /
 * vite) yields no clusters, so callers fall back to {@code ErrorFixAgent.prioritizeCompilerOutput}.
 */
public final class CompileErrorClusterer {

    /** {@code path(line,col): error TSxxxx: message} — the first line of every tsc diagnostic. */
    private static final Pattern TSC_ERROR = Pattern.compile(
            "^(\\S.*?)\\((\\d+),(\\d+)\\): error (TS\\d+): (.*)$");
    /** The first 'quoted' token in a message is the subject symbol / module / type. */
    private static final Pattern FIRST_QUOTED = Pattern.compile("'([^']+)'");

    private CompileErrorClusterer() {}

    private record TscError(String file, String loc, String code, String message, String subject) {}

    /** True when the output contains at least one tsc-format error line. */
    public static boolean hasTscErrors(String output) {
        return !parse(output).isEmpty();
    }

    private static List<TscError> parse(String output) {
        List<TscError> errors = new ArrayList<>();
        if (output == null) return errors;
        for (String line : output.lines().toList()) {
            Matcher m = TSC_ERROR.matcher(line);
            if (!m.matches()) continue;                    // continuation/type-detail lines are indented → skipped
            String file = m.group(1).trim();
            String loc = file + "(" + m.group(2) + "," + m.group(3) + ")";
            String code = m.group(4);
            String message = m.group(5).trim();
            Matcher q = FIRST_QUOTED.matcher(message);
            String subject = q.find() ? q.group(1)
                    : (message.length() > 48 ? message.substring(0, 48) : message);
            errors.add(new TscError(file, loc, code, message, subject));
        }
        return errors;
    }

    /**
     * Renders the complete error set grouped by root cause. Returns "" when there are no tsc
     * errors (caller should fall back). {@code maxChars} bounds the render; clusters are emitted
     * biggest-first and, once the budget is exhausted, remaining clusters collapse to a one-line
     * summary so EVERY root cause is still represented.
     */
    public static String cluster(String output, int maxChars) {
        List<TscError> errors = parse(output);
        if (errors.isEmpty()) return "";

        Map<String, List<TscError>> groups = new LinkedHashMap<>();
        for (TscError e : errors) {
            groups.computeIfAbsent(e.code() + " '" + e.subject() + "'", k -> new ArrayList<>()).add(e);
        }
        List<Map.Entry<String, List<TscError>>> clusters = new ArrayList<>(groups.entrySet());
        clusters.sort((a, b) -> {
            int byCount = Integer.compare(b.getValue().size(), a.getValue().size());
            return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
        });

        boolean touchesDerived = errors.stream().anyMatch(e -> e.file().contains("src/types/"));

        StringBuilder sb = new StringBuilder();
        sb.append(errors.size()).append(" error(s) in ").append(clusters.size())
          .append(" root-cause cluster(s) — COMPLETE list, grouped (fix by cluster, not by file):\n");
        if (touchesDerived) {
            sb.append("NOTE: some errors are in DERIVED files (src/types/*) — regenerated every attempt "
                    + "and NOT editable; if a derived type itself is malformed, say so and stop rather "
                    + "than patching consumers.\n");
        }

        int n = 0;
        for (Map.Entry<String, List<TscError>> cluster : clusters) {
            n++;
            List<TscError> es = cluster.getValue();
            List<String> files = es.stream().map(TscError::file).distinct().toList();
            String full = "[" + n + "] " + cluster.getKey() + " — " + es.size()
                    + " occurrence(s) in " + files.size() + " file(s)\n"
                    + "    files: " + previewList(files, 12) + "\n"
                    + "    e.g. " + es.get(0).loc() + ": " + es.get(0).message() + "\n";
            String oneLine = "[" + n + "] " + cluster.getKey() + " — " + es.size()
                    + " in " + files.size() + " file(s)\n";
            sb.append(sb.length() + full.length() <= maxChars ? full : oneLine);
        }
        return sb.toString().stripTrailing();
    }

    private static String previewList(List<String> items, int cap) {
        if (items.size() <= cap) return String.join(", ", items);
        return String.join(", ", items.subList(0, cap)) + ", +" + (items.size() - cap) + " more";
    }
}
