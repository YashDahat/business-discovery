package com.business.discovery.worker.util;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Single source of the CRUD-hook naming convention
 * (docs/frontend-hook-generation-and-prompt-segregation.md §3.2).
 *
 * <p>The mechanical hook generator emits one TanStack hook per derived service function using this
 * forward map; {@link ManifestCompletenessChecker} uses the SAME map to know which hook symbols a
 * service function backs, so a hook named in enrichment prose is flagged as dangling only when NO
 * service function yields it (and no file declares it, and the foundation does not ship it). Keeping
 * both callers on one definition guarantees a hook the generator will emit is never mis-flagged as
 * missing, and a hook the checker accepts is exactly one the generator can produce.
 *
 * <p>Forward only (serviceFn → hookName).
 */
public final class HookNaming {

    private HookNaming() {}

    /** Query-ish prefixes map to a plain {@code use<Entity>} reader; everything else is a mutation. */
    private static final String[] QUERY_PREFIXES = { "getAll", "get", "list", "fetch", "find" };

    /**
     * The hook name the generator emits for a service function:
     * <pre>
     *   getAllGymClasses → useGymClasses   (getAll&lt;X&gt; → use&lt;X&gt;)
     *   getGymClass      → useGymClass     (get&lt;X&gt;    → use&lt;X&gt;)
     *   listTrainers     → useTrainers     (list&lt;X&gt;   → use&lt;X&gt;)
     *   createGymClass   → useCreateGymClass  (&lt;write&gt;&lt;X&gt; → use&lt;Write&gt;&lt;X&gt;)
     * </pre>
     * Returns {@code null} for a null/blank name.
     */
    public static String hookFor(String serviceFn) {
        if (serviceFn == null) return null;
        String fn = serviceFn.trim();
        if (fn.isEmpty()) return null;
        for (String q : QUERY_PREFIXES) {
            // getAll is listed before get so the longer prefix wins; require an UpperCase entity char
            // after the prefix so "getaway" is not treated as get + "away".
            if (fn.length() > q.length() && fn.startsWith(q) && Character.isUpperCase(fn.charAt(q.length()))) {
                return "use" + fn.substring(q.length());
            }
        }
        return "use" + Character.toUpperCase(fn.charAt(0)) + fn.substring(1);
    }

    /** Every hook symbol the generator would emit for the given service-function names. */
    public static Set<String> hooksFor(Iterable<String> serviceFns) {
        Set<String> out = new LinkedHashSet<>();
        for (String fn : serviceFns) {
            String h = hookFor(fn);
            if (h != null) out.add(h);
        }
        return out;
    }
}
