# Reliable Generation — Tasks Classified by "Accurate Context" Failure Mode

**Date:** 2026-09-05
**Source:** the 14 tasks + provenance map in `docs/frontend-error-analysis-prakash-stores-31c78b9a.md`
(Appendix C — *Frontend generation: full context provenance map*, and Appendix D — *Implementation task list*).

## Why this reframing

The prakash-stores analysis proved one thing: **the frontend fails when the context handed to the LLM
is inaccurate, not when the LLM is incapable.** 48 of 54 errors were context-attributable. So the tasks
are not really a random backlog — each one removes a *specific way context can be wrong*.

This doc re-divides the tasks by **which failure mode of context accuracy each one closes**. That makes
the plan legible ("what class of wrongness am I removing?"), and it exposes the sequencing: you cannot
usefully add *completeness* while a *contradiction* still lets a competing source win.

### The provenance reading rule (the axis everything hangs on)

> A **Card** carries **SHAPE** (fields / signatures) into the **SYSTEM** prompt.
> A **registry** carries **EXISTENCE** (name / path / kind) into the **USER** `depFiles`.
> A context category is **fully accurate only when its shape *and* its existence are both present,
> from exactly one authoritative source, current, and scoped to the file's author-role.**

Break any of those four qualifiers and you get one of five failure modes.

---

## The five classes of context inaccuracy

| Class | Failure mode | The LLM sees… | Symptom |
|---|---|---|---|
| **C1 — Single Source** | **Contradiction / redundancy** — 2+ sources describe one thing and disagree | two shapes for one DTO; picks the richer wrong one | invented fields/types (Themes A–D) |
| **C2 — Completeness** | **Absence** — a needed shape/existence fact reaches the prompt from *nothing* | a symbol it must use, with no shape → it invents one | `AuthUser` fields, nested types, export kind (F, E, [4c]) |
| **C3 — Fidelity / Freshness** | **Degradation** — one source, but stale, lossy, or degraded, passed as truth | a `void` return / a regex-mangled hook sig as if correct | `getAllProducts: void` cascade (D); hook-param drift |
| **C4 — Role Scoping** | **Dilution / misdirection** — correct facts, wrong audience, or LLM asked to author beyond its role | an SDK it never calls; freedom to declare contracts | contract drift, `import_from` races, non-shadcn UI |
| **C5 — Derived Channel (OCP)** | **Delivery drift** — the channel carrying foundation facts is hardcoded and rots | foundation truth that silently went stale | a new foundation feature not recognized |

---

## Classification of every task

Status/error-counts carried over from `frontend-error-analysis-prakash-stores-31c78b9a.md`.

| Task | Title (abbrev) | Class | Removes this inaccuracy | Errors | Status |
|---|---|---|---|---|---|
| **1** | Strip data-model shapes from plan/enrichment → WIRE TYPES sole DTO source | **C1** | the 2nd DTO source (plan/enrichment) that contradicts the derived truth | ~41 (A/B/C) | TODO — biggest lever |
| **2** | Delete `FrontendPlannedContractCard` (actual-over-planned) | **C1** | the planned-vs-actual fork in the prompt | (fork) | TODO |
| **10** | Contract-as-artifact — emit props interface once, both sides `import` | **C1** | two *re-authored* copies of a shared contract → compiler binds instead | prevents cascade | TODO (needs 8) |
| **3** | Foundation model shapes field-for-field via `FoundationSymbolRegistry` | **C2** | absence of `AuthUser`/shell model fields (shown-as-prose, ignored) | 4 (F) | TODO — rides C5/task 9 |
| **4** | `ApiContractCard.readDerived` recursive (`Files.walk` over `types/**`) | **C2** | nested / non-top-level DTO types missed by the flat scan | 0 latent | TODO — cheap |
| **5** | Record export kind (default/named) in `TypeScriptExportRegistry` | **C2** | absence of export *kind* → `import {X}` vs `import X` guessed | 3 (E) | TODO |
| **6** | Re-derive FE types after backend validation (Solution A leg 2) | **C3** | degraded backend artifact (`void`) shipped to FE as ground truth | 4 (D) | TODO |
| **11** | Hook contracts from `FrontendHookGenerator`, not regex re-scan | **C3** | lossy regex recovery of a signature that was known exactly at emission | (fidelity) | TODO (after 2) |
| **7** | Components = pure consumers; retire dead patchers | **C4** | LLM authoring contracts/services it shouldn't; sibling races | prevents recurrence | TODO (needs 1 & 3) |
| **14** | shadcn-only + lucide UI rule; stop offering `@radix-ui/*` | **C4** | an over-broad UI import vocabulary → inconsistent primitives | UI consistency | TODO — standing rule |
| **9** | OCP: auto-onboard foundation features (derive seams from manifest) | **C5** | hardcoded foundation seams that drift from the real foundation | OCP | TODO — see feature-manifest plan |
| **8** | ✅ `NotFoundPage` precheck fixed at foundation | *(not context)* | build-precheck bug (masked the whole FE) | enables ~6 | ✅ DONE |
| **12** | ✅ `emitAppRoutes` guard imports fixed | *(not context)* | deterministic codegen bug | 2 (E) | ✅ DONE |
| **13** | ✅ `FoundationRefReconciler` cart fence narrowed | *(not context)* | deterministic codegen bug | 2 (G) | ✅ DONE |

**Coverage:** C1 (task 1) owns ~41 errors, C2 (3+4+5) owns ~7, C3 (6) owns 4 — together the 48
context-attributable errors. C4 and C5 are *preventive/structural* (stop recurrence, stop channel rot).
The three DONE tasks are non-context codegen/precheck fixes, listed so nothing is silently out of scope.

---

## Flow diagram — where each class acts on the context pipeline

Context flows from four **origins** → through **mechanisms** (cards = shape, registries = existence) →
into the two **prompt slots** (SYSTEM / USER) → to the **LLM author**. Each class is a *gate* on that
flow that guarantees one qualifier of accuracy.

```
 ORIGINS                     MECHANISM                         PROMPT SLOT         AUTHOR
 ───────                     ─────────                         ───────────         ──────

 ARCHITECTURE.json ─┐
 (intent)           │
 ENRICHMENT.json ───┤        cards → SHAPE  ─────────────────► SYSTEM prefix ─┐
 (requirement)      │        registries → EXISTENCE ────────► USER depFiles ─┤
 DERIVED (backend)  │                                                        ├──► LLM authors
 types/hooks (truth)│                                                        │    components/pages
 FOUNDATION + disk ─┘                                                        │
 (ground truth)                                                             ─┘

        │            │                        │                     │              │
        ▼            ▼                        ▼                     ▼              ▼
   ┌─────────┐  ┌──────────┐          ┌──────────────┐     ┌──────────────┐  ┌───────────┐
   │   C5    │  │   C1     │          │     C2       │     │     C3       │  │    C4     │
   │ DERIVED │  │ SINGLE   │          │ COMPLETENESS │     │ FIDELITY /   │  │ ROLE      │
   │ CHANNEL │  │ SOURCE   │          │              │     │ FRESHNESS    │  │ SCOPING   │
   ├─────────┤  ├──────────┤          ├──────────────┤     ├──────────────┤  ├───────────┤
   │ task 9  │  │ 1  2  10 │          │  3   4   5   │     │   6    11    │  │  7   14   │
   │ derive  │  │ collapse │          │  fill every  │     │ re-derive    │  │ author-   │
   │ foundat-│  │ to ONE   │          │  absent      │     │ after truth  │  │ role +    │
   │ ion     │  │ authorit-│          │  shape/exist │     │ changes;     │  │ UI vocab  │
   │ seams   │  │ ative    │          │  fact        │     │ no lossy     │  │ scoped    │
   │ from    │  │ source   │          │              │     │ re-scan      │  │           │
   │ manifest│  │          │          │              │     │              │  │           │
   └────┬────┘  └────┬─────┘          └──────┬───────┘     └──────┬───────┘  └─────┬─────┘
        │            │                       │                    │                │
        ▼            ▼                       ▼                    ▼                ▼
   "the channel  "exactly ONE          "shape AND existence   "the one source   "each fact goes
    stays true    truth per fact —       are BOTH present      is CURRENT and    to the author
    to the        no competing          for every symbol       LOSSLESS, not     that needs it;
    foundation"   copy to pick wrong"    the file consumes"     stale/degraded"   nothing authors
                                                                                  beyond its role"
```

### Ordering falls out of the diagram (each class depends on the one to its left)

```
 C5 derive channel ──► C1 single source ──► C2 completeness ──► C3 fidelity ──► C4 scoping
 (task 9)              (1, 2, 10)           (3, 4, 5)           (6, 11)         (7, 14)

 WHY THIS ORDER:
  • C1 before C2 — adding a missing shape is pointless while a contradicting copy can still win.
  • C2 before C3 — a fact must EXIST before "is it current?" is even a question.
  • C3 before C4 — scope the author to consume a truth only once that truth is complete & current.
  • C5 underpins C2/task 3 — foundation shapes must arrive via the derived (manifest) channel, not a
    hardcoded read, or C2's fix re-introduces the exact drift C5 removes.
```

### Recommended executable sequence (composes with the doc's own readiness note)

1. **Already done:** tasks 8, 12, 13 → a run now *reaches* `ErrorFixAgent` and yields real post-fix data.
2. **C5 first (task 9)** — establish the derived foundation channel (see
   `docs/foundation-feature-manifest-plan.md`); it unblocks C2/task 3.
3. **C1 (tasks 1, 2)** — collapse to one wire truth; this is the ~41-error lever. Add **10** once the
   build reliably runs (compiler is its enforcer).
4. **C2 (tasks 3, 4, 5)** — fill the absence gaps: foundation fields (via C5), recursive scan, export kind.
5. **C3 (tasks 6, 11)** — re-derive after backend validation; deliver hook contracts losslessly.
6. **C4 (tasks 7, 14)** — lock components to pure consumers and the UI vocabulary to shadcn-only.

---

## The invariant this buys

When all five classes hold, the reading rule is satisfied end-to-end:

> Every symbol a component/page consumes arrives with its **shape** *and* its **existence**, from
> **one** authoritative source, that is **current**, delivered through a **derived** (non-rotting)
> channel, and **scoped** to what that file's LLM is allowed to author.

At that point context inaccuracy — the proven root of 48/54 errors — cannot form. The residual is only
genuine codegen/pipeline bugs (the C-less rows), which have their own fixes and are explicitly *out* of
the context-accuracy scope.

---

## Cross-references

- Task provenance + per-error mapping: `docs/frontend-error-analysis-prakash-stores-31c78b9a.md`
  (Appendix C).
- C5 detailed design (manifest + pruning): `docs/foundation-feature-manifest-plan.md`.
- C1 plan-time cousin (cross-layer wire reconciliation, "Solution A"): same source doc, *Solution A* §.
