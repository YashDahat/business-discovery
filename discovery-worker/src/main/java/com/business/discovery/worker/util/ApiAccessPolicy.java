package com.business.discovery.worker.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The single source of truth for "who is allowed through which door" — one access policy
 * that {@link SecurityConfigPatcher} (writes the matchers) and the smoke flows gate (probes
 * them) both consume, so the two can never disagree. When they DID disagree, circuit-house
 * shipped a SecurityConfig that 403'd its own public menu while the flows gate demanded
 * anonymous access to personal order data.
 *
 * Three tiers, decided from the path's domain segment:
 *   PUBLIC        — catalog/marketing content a visitor browses before logging in (GET only)
 *   AUTHENTICATED — a customer's own data (orders, reservations, loyalty, profile), any method
 *   ADMIN         — anything under /api/*&#47;admin/**
 *
 * Deny-by-default: only an explicit allowlist of catalog domains opens up as public GET.
 * Everything else stays locked. A domain we forget stays authenticated (a visitor can't see
 * it) — the safe direction to be wrong, and the prompt covers the general case with judgment.
 */
public final class ApiAccessPolicy {

    public enum Tier { PUBLIC, AUTHENTICATED, ADMIN }

    /**
     * FALLBACK catalog domains, singular form — the marketing surface of the verticals this
     * platform targets, used only when the plan did not declare an endpoint's access. Matched
     * after de-pluralising the path's domain segment, so "classes"/"categories"/"properties"
     * all hit. Every entry is content a stranger is meant to browse; nothing here is one
     * customer's own data.
     */
    private static final Set<String> CATALOG_DOMAINS = Set.of(
            // food
            "menu", "menuitem", "cuisine", "dish", "special",
            // fitness / studio
            "class", "trainer", "coach", "instructor", "workout", "membership",
            // salon / spa / clinic
            "service", "treatment", "stylist", "therapist", "doctor", "specialist",
            "speciality", "specialty", "department",
            // education / coaching
            "course", "batch", "faculty", "curriculum", "syllabus", "program", "programme",
            // real estate / projects
            "property", "listing", "project", "portfolio", "casestudy", "amenity",
            // retail
            "product", "collection", "brand", "catalog", "catalogue", "category",
            // cross-industry marketing surface
            "event", "testimonial", "review", "gallery", "photo", "image", "video",
            "about", "contact", "hour", "timing", "schedule", "location", "branch",
            "outlet", "store", "venue", "facility", "team", "staff", "info", "faq",
            "blog", "article", "news", "post", "page", "plan", "package", "pricing",
            "promotion", "offer", "home", "feature", "banner");

    private ApiAccessPolicy() {}

    /** The tier a request path belongs to, using the fallback heuristic only. */
    public static Tier classify(String method, String path) {
        return classify(method, path, null);
    }

    /**
     * The tier a request path belongs to.
     *
     * @param method        HTTP method; may be null (treated as non-GET)
     * @param path          the request path
     * @param declaredAccess the plan's own {@code access} for this endpoint
     *                       ("public"/"authenticated"/"admin"), or null when unspecified
     *
     * Precedence: an /admin/ path is ADMIN no matter what the plan says (a declaration cannot
     * open the owner's surface), then the plan's declaration — it knows the business and this
     * is what makes the policy work outside restaurants — then the catalog heuristic.
     */
    public static Tier classify(String method, String path, String declaredAccess) {
        List<String> segs = segments(path);
        for (String s : segs) {
            if (s.equalsIgnoreCase("admin")) return Tier.ADMIN;
        }
        Tier declared = parseTier(declaredAccess);
        if (declared != null) return declared;

        int di = domainIndex(segs);
        if (di < 0) return Tier.AUTHENTICATED;
        String domain = segs.get(di).toLowerCase();
        if (domain.equals("auth")) return Tier.PUBLIC; // login/register/refresh must be reachable
        boolean isGet = method != null && method.trim().equalsIgnoreCase("GET");
        if (isGet && CATALOG_DOMAINS.contains(depluralize(domain))) return Tier.PUBLIC;
        return Tier.AUTHENTICATED;
    }

    /** Parses the plan's access string; null when absent or unrecognised. */
    static Tier parseTier(String declaredAccess) {
        if (declaredAccess == null) return null;
        return switch (declaredAccess.trim().toLowerCase()) {
            case "public", "anonymous", "permitall" -> Tier.PUBLIC;
            case "authenticated", "auth", "user", "customer" -> Tier.AUTHENTICATED;
            case "admin", "owner", "role_admin" -> Tier.ADMIN;
            default -> null;
        };
    }

    /**
     * The exact Spring matcher for one endpoint, with path variables widened to a single
     * segment: {@code /api/v1/classes/{id}} → {@code /api/v1/classes/*}. Used for endpoints the
     * plan explicitly declared, so a declaration opens only what it names — unlike the domain
     * glob, which is right for a wholly-public catalog but would over-open a mixed domain.
     */
    public static String exactMatcherPattern(String path) {
        if (path == null || path.isBlank()) return null;
        String p = path.trim();
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (!p.startsWith("/")) p = "/" + p;
        return p.replaceAll("\\{[^}]*}", "*");
    }

    /**
     * The Spring matcher glob covering a path's whole domain, e.g.
     * {@code /api/v1/menus/items} → {@code /api/v1/menus/**}. Null when the path has no
     * recognisable {@code /api[/vN]/<domain>} shape.
     */
    public static String publicPathPattern(String path) {
        List<String> segs = segments(path);
        int di = domainIndex(segs);
        if (di < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= di; i++) sb.append('/').append(segs.get(i));
        sb.append("/**");
        return sb.toString();
    }

    // ── Internals ───────────────────────────────────────────────────────────

    static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        if (path == null) return out;
        String p = path;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        for (String s : p.split("/")) {
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    /** Index of the domain segment: the one after {@code api} and an optional {@code vN}. */
    static int domainIndex(List<String> segs) {
        int api = -1;
        for (int i = 0; i < segs.size(); i++) {
            if (segs.get(i).equalsIgnoreCase("api")) { api = i; break; }
        }
        if (api < 0) return -1;
        int idx = api + 1;
        if (idx < segs.size() && segs.get(idx).matches("(?i)v\\d+")) idx++;
        return idx < segs.size() ? idx : -1;
    }

    /**
     * menus→menu, categories→category, classes→class, dishes→dish, boxes→box.
     * The -es forms matter: "classes" naively loses one s and becomes "classe", which matches
     * nothing — a gym's whole public catalogue would stay locked. Singular "address" (-ss) is
     * left alone, and "addresses" folds onto it, so neither is ever mistaken for a catalogue.
     */
    static String depluralize(String s) {
        if (s.endsWith("ies") && s.length() > 3) return s.substring(0, s.length() - 3) + "y";
        if (s.endsWith("sses") || s.endsWith("ches") || s.endsWith("shes")
                || s.endsWith("xes") || s.endsWith("zes")) {
            return s.substring(0, s.length() - 2);
        }
        if (s.endsWith("s") && !s.endsWith("ss") && s.length() > 1) return s.substring(0, s.length() - 1);
        return s;
    }
}
