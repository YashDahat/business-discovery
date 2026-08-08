# ARCHITECTURE.json Completeness Plan

**Status:** In progress (2026-08-06) — Point A ✅ and Point B (detect + repair, **frontend + backend**) ✅ implemented + unit-tested (21/21). Point B **is** Change 2 in `frontend-fix-loop-convergence-plan.md`.
- **Point A** (plan-time): `ManifestCompletenessChecker.findMissingFrontend`/`findMissingBackend` (both on-disk aware) → `LlmGeneratorService.specifyMissingFiles` (stack-agnostic) wired into `ProjectPlanningNode.validateCrossReferences`, upgrading each miss to a proper spec with a stub fallback; persisted via the node's final `ArchitectureJsonUtil.write`.
- **Point B** (gen-time), both sides now covered:
  - *Frontend* — `ImportClosureChecker` finds unresolved local imports over real code; `MissingModuleSynthesizer` **repairs** with permissive placeholders (default + named exports, children passed through). Wired into `FrontendValidationNode` after the Change-3 path fixers; default repairs, strict (`worker.completeness.import-closure-strict`) fails loud.
  - *Backend* — `javac` detects "cannot find symbol: class X"; `BackendClassSynthesizer` **repairs** by writing a minimal placeholder class at the right package (import-aware, else the referencer's). Wired into `BackendValidationNode` after the mechanical injectors, before the ErrorFixAgent. Resolves the missing-TYPE error; residual member errors are a tractable in-file task for the agent.
- **Pending:** the "targeted LLM gen" route for a non-layout / non-trivial miss (Point A specs the planned/prose cases; the deterministic stubs cover gen-time inventions).
**Date:** 2026-08-05
**Owner area:** `discovery-worker` — `ProjectPlanningNode` (planning stages)
**Motivating defect:** `AdminLayout` referenced by 6 admin pages but never generated (farmaaish-restaurant, brief `9bdf03a2`) → 6× `TS2307` that survived 3 fix-loop attempts. Backend twin: `OrderItemResponse` referenced by `OrderResponse`/`OrderService`, never generated.
**Relationship:** fixes the *root* of GAP 2 (missing producer) at plan time. Complements — does not replace — Change 2 (`MissingModuleGenerator`, post-generation backstop) in `frontend-fix-loop-convergence-plan.md`.

---

## 1. Root cause (evidence-backed from the branch's `ARCHITECTURE.json`)

The plan is **not self-consistent**: it designs consumers that reference a producer it never schedules, and the field a completeness check would rely on is mostly empty.

Concretely, for `AdminLayout`:
- **Not in `files[]`.** The manifest lists 110 files — all 6 admin pages, all admin controllers — but **no `AdminLayout.tsx` entry** (nor `SiteLayout`). The generator only produces `files[]` entries, so it was never asked to make it. Not a skip; an absence from the work list.
- **`SiteLayout` exists only via the scaffold.** `webapp-foundation` ships `SiteLayout`/`SiteHeader`/`SiteFooter`. It does **not** ship an admin layout. The outline treated `AdminLayout` as if it were a scaffold-provided primitive like `SiteLayout` — an incorrect assumption about what the foundation provides.
- **The dependency lives only in prose.** `AdminLayout` appears 4× in `features[]` prose ("All pages are wrapped in the `<AdminLayout>` component from `@/components/AdminLayout`"), but the admin pages' structured `imports_from` list their *child components* (`MenuTable`, `ReservationsTable`, …) and **omit the layout wrapper**. `AdminDashboardPage.tsx` has `imports_from: []` entirely.
- **`imports_from` is broadly unreliable: 30/110 files (27%) empty/absent** (16/63 frontend). Matches the recorded backend pattern (23/60 backend files empty).

## 2. Which stage is weak (precise)

| Stage (code) | Role re: the missing file | Verdict |
|---|---|---|
| **Outline** — `generateArchitectureSpec` (Pro) | owns `files[]`; never listed `AdminLayout` (assumed scaffold-provided) | **root cause** |
| **Enrichment** — `enrichFeatures` (Flash) | pulls `filesByFeature.getOrDefault(...)` and mutates existing `FileSpec`s in place; **never appends to `spec.files`** | not the origin — "takes the list as-is" |
| **Safety net** — `validateCrossReferences` | backfills a `buildStubEntry` for any `imports_from` path with no file entry | correct design, but **blinded**: `if (file.getImportsFrom() == null) continue;` and it only reads `imports_from`, which never contained `AdminLayout` |

So: the outline dropped the *file*; enrichment could not add it (it only enriches what it's given); and the existing closure check couldn't backfill it because the dependency was never recorded in the field it reads.

## 3. The fix — a plan-time completeness pass

**Where:** end of `ProjectPlanningNode`, after `enrichFeatures`, as a **strengthened replacement for `validateCrossReferences`**. Runs before any generation node, so the missing file is added to `files[]` and generated as a first-class file.

**Inputs (both already available at this point in the node):**
- **Scaffold inventory** — the worker already knows it via `scaffoldModules` (used by `stripScaffoldOwnedFiles`) and the foundation cloned into the workspace *before* planning (`cloneFoundation`). This is what distinguishes "referenced but scaffold-provided" (`SiteLayout` → fine) from "referenced but missing" (`AdminLayout` → add).
- **ARCHITECTURE.json** — `files[]` (paths + `imports_from`) and the prose (`features[].description`, `files[].description`).

### Steps — detection is MECHANICAL, speccing is the LLM 2nd pass

**1. (Mechanical) Provided set** = scaffold files ∪ `files[]` paths.

**2. (Mechanical) Referenced set** — from THREE sources, not just `imports_from`:
- `files[].imports_from` (structured, but 27% empty — necessary, not sufficient)
- **the prose** — regex `@/[\w/-]+` import paths and `<PascalCase>` JSX component references in `description`/`features` text. *This is where `AdminLayout` actually lives.*

**3. (Mechanical) Diff** → `missing = referenced − provided`. `AdminLayout`: referenced in prose, not scaffold-provided, not in `files[]` → **flagged**. This is the mechanical detection — it works because it reads prose + diffs the scaffold, which the `imports_from`-only check does not.

**4. (LLM 2nd pass — targeted, grounded)** Hand the model *the mechanical missing list* + the referencing files' descriptions + the scaffold inventory. For each item it decides: real file to add / scaffold-provided (drop) / false positive (type alias, re-export). For real ones it emits a **proper `FileSpec`** (`file_path`, `file_type`, `layer`, `feature_name`, `description` modeled on the feature prose + `SiteLayout`, `public_functions`/exports) appended to `files[]` — so the generator produces a *real* `AdminLayout`, not the bare `buildStubEntry` placeholder.

**5. (Mechanical) Re-diff** to confirm the additions introduced no new dangling references (one bounded loop).

## 4. The mechanical / LLM boundary

- **Detection = mechanical** (steps 1–3). "Missing files can be found mechanically in `ARCHITECTURE.json`" is true — *from the prose + scaffold diff*, NOT from `imports_from` alone (that path already exists as `validateCrossReferences` and already failed).
- **Speccing = LLM** (step 4), scoped narrowly to turning detected-missing symbols into proper `FileSpec`s — not a free "re-review your whole generation."

## 5. Caveats

- **Self-critique blind spot.** The model that made the omission may rationalize it. Mitigation: don't free-audit — feed it the concrete mechanical diff to adjudicate specific items; run on Flash (cheap) or a different model; mechanically validate its output (every added `file_path` well-formed, non-duplicate, resolves under a known layer).
- **Prose is fuzzy.** Regex over natural language misses some references. So this fixes *most* at the cheapest point, not all.
- **Therefore keep Change 2 as the backstop.** The plan-time pass fixes the root cheaply and produces proper specs; **Change 2 (post-generation, over real `import` statements)** catches whatever the fuzzy prose scan misses, against ground-truth code, stage-agnostically.

## 6. Why plan-time first (ordering vs Change 2)

Fixing `ARCHITECTURE.json` first means the missing file gets a **proper spec and is generated as a first-class file** (real admin navigation, auth wiring, `<Outlet/>`), not a minimal stub synthesized late. Change 2 then becomes the safety net for the residue rather than the primary mechanism. Sequence: **this plan → then Change 2 backstops what it misses.**

## 7. Upstream option (larger, optional)

The cheapest long-term cure is to stop the outline from assuming scaffold provision: feed `generateArchitectureSpec` the **foundation's real file inventory** so it knows `AdminLayout` is not provided and must be listed. And require enrichment to promote prose-named wrappers into `imports_from` so the *structured* graph is trustworthy. These reduce how much the completeness pass has to catch, but they are still planner-authored and best-effort — the hard guarantee is §8.

---

## 8. Enforcement & invariant

You **cannot** guarantee completeness from `ARCHITECTURE.json` alone: `imports_from` is the unreliable field, so a manifest that is empty everywhere would trivially "pass" any self-check (no dangling refs because no refs). *Empty ≠ correct.* The guarantee must come from checking the manifest against an **independent source of truth**.

### The invariant (two clauses)

> For every file **F** and every local import **I** in F:
> **(a)** `resolve(I) ∈ files[] ∪ scaffold ∪ node_modules` — *no unplanned imports*, **and**
> **(b)** `imports_from(F)` reflects F's real local imports — *no wrongly-empty imports*.

**Reframe — "no empty imports" is the wrong rule.** A pure type file or leaf util *correctly* has empty `imports_from`. The defect is a file that really imports things but lists nothing (the admin pages import children + `AdminLayout`, list only children). Clause (b) is *consistency with reality*, not non-emptiness.

### Enforcement Point A — plan time (best-effort hardening, cheap)

Checked against the richer-but-still-planner-authored source: **the prose.**
1. **Backfill** `imports_from(F)` by mechanically extracting `@/…` paths and `<PascalCase>` refs from F's `description`/feature prose and unioning them in — repairs the 27%-empty case (a page whose prose says "wrapped in `<AdminLayout>`" gains that edge).
2. **Closure-diff** the backfilled `imports_from` against `files[] ∪ scaffold` → unresolved = missing producer → LLM-spec + add (§3), or drop if scaffold-provided.
3. **Hard gate:** after backfill + closure, assert **zero** unresolved `imports_from` entries remain; else `throw WorkerException` — never enter generation with a knowingly-incomplete manifest.

This is *hardening*, not a guarantee — prose regex is fuzzy, so it reduces the problem, it does not close it.

### Enforcement Point B — generation time (the actual guarantee, ground truth)

**Status (2026-08-06): ✅ detection + repair implemented + unit-tested (6/6). This IS Change 2** in `frontend-fix-loop-convergence-plan.md` — its detection and its synthesis.
- **Detection** — `util/ImportClosureChecker.java` scans every generated `frontend/src/*.ts(x)` file's real `import`/`export … from` statements and finds each local (`@/`,`./`,`../`) specifier that resolves to no file on disk (extension + `index` + literal-asset resolution; node-module and side-effect imports ignored). Catches modules invented only at generation, which the plan-time checks cannot see.
- **Repair** — `util/MissingModuleSynthesizer.java` writes a permissive placeholder for each miss: a default export AND every named symbol the consumers ask for (as value *and* type), with the component form passing `children` through so a synthesized layout never silently blanks the page. The import now resolves; residual type mismatches (props, etc.) are a bounded problem left to the ErrorFixAgent.

Wired into `FrontendValidationNode` **after** the Change-3 path fixers (so a remaining miss is genuinely absent, not mis-pathed) and **before** the ErrorFixAgent. **Default repairs** (synthesize placeholders, rebuild, proceed; writes `docs/IMPORT_CLOSURE_REPORT.md`); `worker.completeness.import-closure-strict=true` instead **fails loud** without stubbing (`WorkerException(CODE)`).

**Backend (2026-08-06): ✅ implemented + unit-tested (5/5).** `util/BackendClassSynthesizer.java` parses `mvn compile` output for `cannot find symbol: class X` and writes a minimal placeholder class for each X that was never generated — package resolved from the referencer's `import` of X, else the referencer's own package (nested/builder locations resolve to the top-level package). `*Exception` names extend `RuntimeException`; others are an empty class. Wired into `BackendValidationNode` after the mechanical injectors, before the ErrorFixAgent (recompile-and-return if it closes). Unlike the frontend TS `any` stub it only resolves the missing *type* (not every getter/ctor) — but that turns "author a whole file" (which the agent fails at) into "add members to an existing class" (which it handles). **Open:** the "targeted LLM gen" route to produce a *real* DTO instead of an empty placeholder.

The only true source of "what F imports" is F's real `import` statements.
4. After each file/layer, parse the **actual** imports; for every local (`@/`/relative) import, assert it resolves to a real on-disk file (generated or scaffold) — exactly what `TypeScriptExportRegistry` + the filesystem already do (Change 3's machinery).
5. **Unresolved import = the ground-truth signal of an unplanned/missing file** → trigger missing-producer synthesis (Change 2), re-assert, and hard-fail only if repair cannot close it.

Deterministic and *complete* because it reads code, not claims — it catches `AdminLayout` whatever `imports_from` said.

### Where the guarantee lives

| Stage | Source of truth | Strength | Role |
|---|---|---|---|
| Plan-time (A) | prose + scaffold | best-effort (fuzzy) | fixes most *before* generation → proper specs, not late stubs |
| Gen-time (B) | **real code** | **hard guarantee** | the invariant that actually holds; catches what A's prose scan missed |

You *make sure* with **B**; **A** is the cheap first line. Neither can be replaced by "validate the manifest against itself" — that is the mistake `validateCrossReferences` embodies today (it trusts `imports_from`, the unreliable input).

### Failure mode (must be decided)

Both gates must be able to **fail loud** (`WorkerException`) to be gates, not advisories. Recommended: **auto-repair then re-assert** (backfill / synthesize the missing producer, recompute, verify closed), hard-failing only when repair cannot close the invariant — consistent with the pipeline's fix-then-verify philosophy.

---

## Implementation sketch

- ✅ **New:** `util/ManifestCompletenessChecker.java` — `findMissingFrontend` (provided = `files[]` ∪ on-disk foundation files; referenced = `imports_from` + `@/…` prose refs filtered to component/hook segments; diff) **and** `findMissingBackend` (provided = backend `files[]` ∪ on-disk `backend/src/main/java/*.java`; referenced = `backend/` `imports_from` paths; diff — path-based, no prose). Both on-disk aware. Step 5 (re-diff) deferred. Pure/unit-tested.
- ✅ **Extend:** `LlmGeneratorService.specifyMissingFiles(missingPaths, referencingContext, exemplar, brief)` (Flash) → proper `FileSpec`s. Stack-agnostic (backend Java + frontend TS; `file_type` read/inferred from path); robust manual parse (skips the `public_functions` List<PublicFunction> shape trap); empty on failure so the caller stubs. Prompts: `system|user/spec_missing_files.txt` (full-stack). Wired via `ProjectPlanningNode.specifyMissingFrontend` (assembles feature-prose context + `SiteLayout` exemplar). Backend detection to route `OrderItemResponse`-class misses here is still pending.
- ✅ **Wire:** `ProjectPlanningNode.validateCrossReferences(spec, workspace)` — runs the checker (frontend, on-disk aware) then the existing `imports_from` stubbing (backend only; frontend skipped there). Additive, not a replace; `buildStubEntry` is the current add-action.
- ✅ **Tests:** `ManifestCompletenessCheckerTest` (4/4) — `AdminLayout` (prose-only) flagged, `SiteLayout` (on disk) not; dangling `imports_from` caught; planned files + bare-dir prose refs ignored; `referencedBy` preserved.
- **Enforcement Point B (gen-time closure assertion)** is *not* in this doc's scope — it lives with Changes 2 & 3 (`TypeScriptExportRegistry` resolution + `MissingModuleGenerator`) in `frontend-fix-loop-convergence-plan.md`. This doc implements **Point A** (plan-time hardening); §8 is the shared invariant both points enforce. **Point B is where the `OrderItemResponse`-class defect is caught** — a class invented in generated code but never in the manifest, so `findMissingBackend` (plan-time) structurally cannot see it.

## Validation

Re-plan farmaaish-restaurant on a fresh branch → assert `AdminLayout.tsx` now appears in `files[]` with a real spec, generates, and the 6× `TS2307` never occur. Backend twin: `OrderItemResponse` appears in `files[]`.
