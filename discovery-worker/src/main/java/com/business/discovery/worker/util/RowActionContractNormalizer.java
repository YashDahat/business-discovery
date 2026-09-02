package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.FileContract;
import com.business.discovery.worker.service.llm.FileContract.Member;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic post-pass over the reconciled contracts for issue #5 (handler signature drift —
 * object vs id). Runs immediately after {@link ContractReconciler} obtains the Pro-reconciled
 * {@link FileContract}s and BEFORE they are written back into the spec, so the normalized shape
 * flows into both {@code fileRole} and the structured prop fields in one place.
 *
 * <p><b>Why this exists.</b> The reconciler can pin a child table's declared props, but a parent
 * page's row-action handler is an <i>internal implementation</i>, not a declared interface — there is
 * no contract slot to pin it to. So the reconciler cannot force the parent to match the child; it can
 * only make the child self-consistent and hope the model mirrors it. Worse, the Pro call routinely
 * emits an <i>intra-component</i> asymmetry ({@code onEdit: (x: XDto)} but {@code onDelete: (id: number)}),
 * and the model — writing both page handlers symmetrically — then trips TS2322 on whichever side
 * disagrees. See {@code docs/frontend-issue-solution-plan-9312afa6.md} §5.
 *
 * <p><b>The fix.</b> Kill the asymmetry rather than hope the model honors it: make every
 * <i>row-action</i> callback on a list/table component take the full row object — the shape the model
 * naturally writes and the table trivially has in hand. Normalizing the child contract to the shape
 * the parent inevitably produces is how we make the reconciler effectively "patch the sibling
 * contract" without a lever on the parent handler.
 *
 * <p><b>Bias = skip, not rewrite.</b> Over-rewriting silently turns a correct contract
 * ({@code onSort: (column: string)}) into a nonsensical one whose type error blames the component. So
 * a callback is rewritten only when BOTH gates pass: (1) its name clears a deny-list and exactly
 * matches a row-action allowlist, and (2) its single parameter is an id-like primitive. Anything
 * uncertain is left to the prompt + ErrorFixAgent. Idempotent: an already-{@code (item: XDto)}
 * callback is a byte-identical no-op.
 */
@Slf4j
public final class RowActionContractNormalizer {

    /** Checked FIRST — table controls that legitimately take a non-row primitive/boolean. */
    private static final Set<String> DENY = Set.of(
            "onsort", "onsortchange", "oncolumnsort", "onpage", "onpagechange",
            "onrowsperpagechange", "onsearch", "onfilter", "onselectall");

    /** Row-action verbs — matched EXACTLY (so {@code onSelectAll} can never slip past {@code onSelect}). */
    private static final Set<String> ALLOW = Set.of(
            "onedit", "ondelete", "onview", "onselect", "onrowclick",
            "onarchive", "onapprove", "onreject");

    /** Capitalized tokens that are arrays but not domain DTOs — never a row type. */
    private static final Set<String> NOT_DTO = Set.of(
            "Date", "String", "Number", "Boolean", "Object", "Array", "Map", "Set", "Promise");

    private RowActionContractNormalizer() {}

    /**
     * Rewrites in place (by replacing entries) the row-action callback members of every component
     * contract so each takes the row DTO. Non-component contracts and non-matching members are left
     * exactly as-is.
     *
     * @return number of component contracts changed.
     */
    static int normalize(List<FileContract> contracts) {
        if (contracts == null) return 0;
        int changed = 0;
        for (int i = 0; i < contracts.size(); i++) {
            FileContract c = contracts.get(i);
            if (c == null || !ContractReconciler.isComponentOrPage(c.module())) continue;
            if (c.members() == null || c.members().isEmpty()) continue;

            String rowDto = findRowDto(c.members());
            if (rowDto == null) continue;   // not a list/table component (no unambiguous row type)

            List<Member> out = new ArrayList<>(c.members().size());
            boolean fileChanged = false;
            for (Member m : c.members()) {
                Member nm = rewriteRowAction(m, rowDto);
                if (nm != m) fileChanged = true;
                out.add(nm);
            }
            if (fileChanged) {
                contracts.set(i, new FileContract(c.module(), out, c.methods()));
                changed++;
                log.info("[RowActionContractNormalizer] Normalized row-action callbacks in {} → (item: {})",
                        c.module(), rowDto);
            }
        }
        return changed;
    }

    // ── row DTO anchor ────────────────────────────────────────────────────────

    /**
     * The row type = the element of the component's list prop. Prefer a prop literally named
     * {@code items}; otherwise the sole array-of-DTO prop. Ambiguous (0 or >1 distinct) → null (skip).
     */
    private static String findRowDto(List<Member> members) {
        Set<String> candidates = new LinkedHashSet<>();
        String named = null;
        for (Member m : members) {
            String elem = arrayElementType(m.type());
            if (elem == null || !isDtoLike(elem)) continue;
            candidates.add(elem);
            if ("items".equalsIgnoreCase(m.name())) named = elem;
        }
        if (named != null) return named;
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    /** {@code XDto[]} or {@code Array<XDto>} → {@code XDto}; anything else → null. Clean token only. */
    private static String arrayElementType(String type) {
        if (type == null) return null;
        String t = type.trim();
        String elem;
        if (t.endsWith("[]")) {
            elem = t.substring(0, t.length() - 2).trim();
        } else if (t.startsWith("Array<") && t.endsWith(">")) {
            elem = t.substring(6, t.length() - 1).trim();
        } else {
            return null;
        }
        return elem.matches("[A-Za-z_][A-Za-z0-9_]*") ? elem : null;
    }

    private static boolean isDtoLike(String elem) {
        return elem.matches("[A-Z][A-Za-z0-9_]*") && !NOT_DTO.contains(elem);
    }

    // ── per-callback rewrite ───────────────────────────────────────────────────

    /** @return a rewritten member, or the SAME instance when it must not change (no-op / skip). */
    private static Member rewriteRowAction(Member m, String rowDto) {
        if (m.name() == null || m.type() == null) return m;

        // Gate 1 — name: deny-list first, then EXACT row-action verb (or the one intentional prefix).
        String name = m.name().toLowerCase();
        if (DENY.contains(name)) return m;
        if (!(ALLOW.contains(name) || name.startsWith("ontoggle"))) return m;

        // Must be a single-parameter arrow type; multi-param callbacks are out of scope.
        String[] arrow = parseArrow(m.type());
        if (arrow == null) return m;
        String paramsStr = arrow[0].trim();
        String ret = arrow[1].trim().isEmpty() ? "void" : arrow[1].trim();
        if (paramsStr.isEmpty() || splitTopLevel(paramsStr).size() != 1) return m;

        // Gate 2 — parameter evidence: already the row DTO → no-op; id-like primitive → rewrite; else skip.
        String param = paramsStr.trim();
        int colon = param.indexOf(':');
        String pName = colon >= 0 ? param.substring(0, colon).trim() : null;
        String pType = colon >= 0 ? param.substring(colon + 1).trim() : param.trim();
        if (pType.equals(rowDto)) return m;                                 // already normalized
        if (!(isScalarId(pType) && isIdLike(pName))) return m;              // uncertain → leave it

        return new Member(m.name(), "(item: " + rowDto + ") => " + ret);
    }

    /**
     * Scalar id types a row-key can take. {@code number}/{@code string} cover Integer/Long/String ids;
     * {@code UUID} is the frontend id alias emitted by UUID-identity backends (e.g. the restaurant
     * runs — {@code onDelete: (itemId: UUID)}). This must track the id types the reconciler produces;
     * anything not here (a DTO, an enum, a union) is left alone so a non-id callback is never corrupted.
     */
    private static boolean isScalarId(String type) {
        return "number".equals(type) || "string".equals(type) || "UUID".equals(type);
    }

    /** {@code id}, {@code ID}, or camelCase {@code *Id} (classId, memberId) — not {@code valid}/{@code uuid}. */
    private static boolean isIdLike(String name) {
        if (name == null) return false;
        return name.equals("id") || name.equals("ID") || name.endsWith("Id");
    }

    // ── tiny TS type parsing (depth-aware, no regex on nesting) ─────────────────

    /** Splits {@code "(params) => ret"} into {@code [params, ret]}; null if not an arrow type. */
    private static String[] parseArrow(String type) {
        String t = type.trim();
        if (!t.startsWith("(")) return null;
        int depth = 0, close = -1;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')') { depth--; if (depth == 0) { close = i; break; } }
        }
        if (close < 0) return null;
        String rest = t.substring(close + 1).trim();
        if (!rest.startsWith("=>")) return null;
        return new String[]{ t.substring(1, close), rest.substring(2).trim() };
    }

    /** Splits on top-level commas, respecting {@code () [] <> {}} nesting. */
    private static List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(' || ch == '[' || ch == '<' || ch == '{') depth++;
            else if (ch == ')' || ch == ']' || ch == '>' || ch == '}') depth--;
            else if (ch == ',' && depth == 0) { parts.add(s.substring(start, i)); start = i + 1; }
        }
        parts.add(s.substring(start));
        return parts;
    }
}
