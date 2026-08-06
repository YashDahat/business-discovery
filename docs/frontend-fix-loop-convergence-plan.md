# Frontend Fix-Loop Convergence Plan

**Status:** Change 1 (clustering) ✅, Change 3 (deterministic import pre-pass) ✅, and Change 2 (missing-producer) ✅ — all implemented + unit-tested. Change 2 converged with the completeness plan's **Point B** (`ImportClosureChecker` detection + `MissingModuleSynthesizer` synthesis); backend DTO synthesis is the one open item.
**Date:** 2026-08-04 (updated 2026-08-06)
**Goal:** Reduce ErrorFixAgent rounds on the frontend. Today it exhausts all 30 rounds and resolves nothing (`620bb653` attempts 1 & 3). Target: deterministic pre-pass clears the mechanical classes, the LLM sees the **full** error set clustered by root cause, and converges in ≤5 rounds.

---

## Already built — do NOT rebuild

Verified in source; the plan builds on these, it does not duplicate them:

- **Deterministic pre-pass exists** in both validators (run before the LLM loop):
  - `FrontendValidationNode` (`@Order(13)`): `RouteManifestReconciler`, `ApiContractChecker`, `TsxExportGuard`, `NpmPackageFixer`, `JsxTypeImportFixer`, `UiImportRewriter`, `ServiceImportRewriter`.
  - `BackendValidationNode` (`@Order(8)`): ~10 runtime patchers + `MavenDependencyInjector`, `RepositoryMethodInjector`.
- **Class-wide edits:** `ErrorFixAgent` already exposes a `bulk_str_replace` tool with MANDATORY "if the same string changes in 2+ files, use bulk" guidance.
- **Registry + contract card:** `TypeScriptExportRegistry` (symbol→path, `toImportCatalog()`) and `FrontendContractCard` (hook return types, type/interface field shapes, props, and a `⚠ type-not-value` warning) are populated per file in `FrontendGeneratorNode`, layer-ordered, seeded-from-disk on resume, and injected into both the generation prompt and the ErrorFixAgent system prompt (immutable = ground truth, "harness REFUSES edits").
- **Free recompile:** `autoVerify` post-round hook compiles after every mutating round.
- **Error routing primitive:** `CompileErrorClassifier` classifies output into `MISSING_IMPORT / WRONG_IMPORT_PATH / MISSING_DEPENDENCY / TS_MODULE_NOT_FOUND / TS_TYPE_ERROR / LOGIC_ERROR` and exposes `needsDeterministicFix` / `needsLlmFix`.
- **Import resolver:** `TypeScriptImportFixer` (registry-driven specifier resolution) exists.

## Root causes of non-convergence (confirmed in code)

**GAP 1 — the seed is truncated, not clustered.** `ErrorFixAgent.prioritizeCompilerOutput(output, SEEDED_ERRORS_MAX_CHARS=6000)` reorders `src/types/` first, then **truncates on a block boundary** and reports `showing N of M`. On the 57-error run it showed **8 of 57**. The agent literally cannot see 49 errors → cannot cluster them → cannot `bulk_str_replace` them → recompile still fails → repeat until round 30. *If it can't see the error, no number of rounds finds it.* This is the primary "nothing resolves" cause.

**GAP 2 — no missing-producer pre-pass.** A referenced-but-never-emitted module (`AdminLayout` frontend, `OrderItemResponse` backend) has no deterministic handler. The loop *can* `write_file`, but authoring a whole file inside the repair loop is exactly what it fails at (all 6 `AdminLayout` errors survived 3 attempts). The registry already knows the gap: `resolveSpecifier(symbol)` is empty AND `knows(symbol)` is false.

**GAP 3 — the deterministic import pre-pass is incomplete for the frontend.** `TypeScriptImportFixer` runs during *generation* but is **not** in `FrontendValidationNode`'s post-build pre-pass, so wrong-path errors on the built tree fall through to the LLM. And `TypeScriptExportRegistry` records only symbol→path — **no binding (default vs named)** — so the default↔named class (`siteConfig`, `OrderDetailView`, `ReservationDetailModal`) cannot be mechanically fixed.

---

## The plan — three changes, ordered by leverage

### Change 1 — Cluster the FULL error set by root cause (highest leverage)

**Status (2026-08-05): ✅ Implemented + unit-tested (5/5).** `util/CompileErrorClusterer.java` groups the full tsc output by `(code + subject symbol)`, biggest-cluster-first, each with its complete affected-file list; under a tight budget clusters collapse to one-line summaries so none are dropped. Wired into `ErrorFixAgent` via a `renderErrors()` helper at all three agent-facing sites (seed, `autoVerify`, `run_compiler`); backend/non-tsc output falls back to `prioritizeCompilerOutput`. Two companion changes shipped with it: (a) the frontend seed now runs `npm run typecheck` (complete, non-incremental) with a defensive fallback to `npm run build` when the script is absent; (b) the foundation (`webapp-foundation`) gained `"typecheck": "tsc --noEmit -p tsconfig.app.json"` (pushed to `main`). Differs from the sketch below: groups by `code + subject` directly rather than reusing `CompileErrorClassifier` categories.

**Problem it fixes:** GAP 1 (truncation blindness) — the direct "reduce rounds / converge" lever.

**New:** `util/CompileErrorClusterer.java`. Input = the complete compiler output (untruncated). Output = a compact list of **root-cause clusters**, each: `{category, code, symbol/module, message exemplar, affectedFiles[]}`. Grouping key = `(error code + missing symbol/module)` so all 6 `AdminLayout` TS2307 collapse to one cluster and all 5 `CheckoutStep` TS2693 to another. Reuse `CompileErrorClassifier` categories for the `category` field and the deterministic-vs-LLM split.

**Render** (replaces the truncated seed the agent receives):
```
FRONTEND BUILD — 57 errors in 9 root-cause clusters:
[1] TS2307 missing module '@/components/AdminLayout' (6 files) — DETERMINISTIC
    AdminDashboardPage, AdminEventsPage, AdminOrdersPage, AdminMenuPage, ...
[2] TS2693 'CheckoutStep' type-used-as-value (5 sites: OrderPage×4, PaymentStep)
[3] TS2322 undefined-vs-null on optional fields (3 files) — ...
...
```
The key inversion: the agent now plans against **9 causes**, not a moving 8-of-57 window. Round count tracks *causes* (small), and every multi-file cluster maps straight onto one `bulk_str_replace`.

**Wire:** in `ErrorFixAgent`, replace the seed construction at line ~298 (`prioritizeCompilerOutput(preCheck.output(), 6000)`) with the clustered render. Keep `prioritizeCompilerOutput` for the per-round `autoVerify` delta, but have it prefer full clustered counts over raw truncation. Preserve the derived-`src/types/` escalation note (it's still correct).

**Tests:** `CompileErrorClustererTest` on the real 57-error and 28-error dumps from `farmaaish-restaurant` (captured this session) → assert cluster count and per-cluster file lists.

**Effort:** M (new util ~150 LOC + one wire point + tests).

### Change 2 — Missing-producer pre-pass

**Status (2026-08-06): ✅ implemented — converged with the completeness plan's Point B.** Change 2 = *detect a missing producer over real code, then synthesize it* — which is exactly **Point B** in `architecture-json-completeness-plan.md`:
- **Detection** → `util/ImportClosureChecker.java` — scans the real generated imports, resolves each local specifier against the filesystem; catches gen-time inventions, not just manifest-declared refs.
- **Synthesis** → `util/MissingModuleSynthesizer.java` — writes a permissive placeholder (default + every named symbol as value *and* type, children passed through so a synthesized layout never blanks the page) so the import resolves; residual type errors → ErrorFixAgent.

Both run in `FrontendValidationNode` after the Change-3 path fixers, before the ErrorFixAgent. **Default repairs**; strict (`worker.completeness.import-closure-strict`) fails loud without stubbing. This supersedes the `MissingModuleGenerator` sketch below (kept for rationale). Differences from the sketch: a maximal-compat permissive stub (not a `SiteLayout`-template clone); it does **not** repoint importers (the placeholder is created at the path consumers already import); the "targeted LLM gen for non-layouts" route is deferred — Point A already produces proper specs for planned/prose misses, so Point B's residual (gen-time inventions) gets the deterministic stub. **Open:** backend `OrderItemResponse` synthesis (`javac` detects it; the DTO synthesis is the remaining item).

**Problem it fixes:** GAP 2 (`AdminLayout` / `OrderItemResponse` never generated).

**Original sketch (superseded by Point B above):** `util/MissingModuleGenerator.java`, run in `FrontendValidationNode` pre-pass (before the LLM loop) and mirrored for backend DTOs in `BackendValidationNode`. Algorithm:
1. Scan imports across `frontend/src`; for each specifier, resolve against `TypeScriptExportRegistry` + the filesystem.
2. If a specifier resolves to **no file** AND its symbol is unknown to the registry → it's a missing producer.
3. Resolve by kind:
   - **Layout/shell** (`*Layout`) → deterministic stub from the `SiteLayout` template (default export, `{children}` + `<Outlet/>`), placed at the registry-idiomatic path (`@/shell/`), then repoint importers.
   - **Everything else** → **one** targeted LLM generation call for that single file, seeded with the contract card — not the 30-round loop.
4. Register the new module so downstream pre-pass steps see it.

**Backend analog:** detect `cannot find symbol: class X` where `X` is referenced but no `X.java` exists (e.g. `OrderItemResponse`) → one targeted DTO generation. This promotes the **manifest-completeness gate** to a shared backend+frontend concern (see `frontend-export-registry.md` §8/§11).

**Tests:** `MissingModuleGeneratorTest` — fixture with `AdminLayout` referenced but absent → asserts stub created at `@/shell/AdminLayout` + 6 importers repointed; backend fixture with missing `OrderItemResponse`.

**Effort:** M–L (frontend stub path is deterministic; the targeted-gen path reuses existing generator plumbing).

### Change 3 — Complete the deterministic import pre-pass

**Status (2026-08-05): ✅ Implemented + unit-tested (registry 6/6, fixer 4/4).** All three sub-parts done. Note: `TypeScriptImportFixer` already handled the named→default direction (TS2614) via a file-read. Shipped: (1) `TypeScriptExportRegistry` now captures binding (`enum Binding {DEFAULT, NAMED}` + `resolveBinding`) and adds a `buildFromDisk(frontendSrc, workspace)` factory; (2) `TypeScriptImportFixer` gained the reverse **default→named** rewrite (TS2613) — driven by the registry binding plus a file guard that never rewrites a *valid* default import — a boolean `fix()` return, and a `fixAll()` batch; (3) `FrontendValidationNode` runs `fixAll` (registry rebuilt from disk) as pre-pass step 6, included in the post-fix rebuild gate. **100% mechanical, zero LLM** — provable rewrites only; ambiguous cases (multi-symbol/mixed imports, target with both default+named) are deliberately deferred to the ErrorFixAgent. Not done: surfacing binding in `toImportCatalog()` for the *generation* prompt (separate follow-up — would reduce the class at the source too).

**Problem it fixes:** GAP 3 (wrong-path + default↔named fall through to the LLM).

1. **Wire `TypeScriptImportFixer` into `FrontendValidationNode`'s pre-pass** (add to the block at lines 66–71, after `UiImportRewriter`), so registry-driven path resolution runs on the *built* tree, not only at generation.
2. **Enrich `TypeScriptExportRegistry`** to capture **binding** (`default` vs `named`) per symbol — the `NAMED_EXPORT` regex already sees the `default` keyword; store it. Add `resolveBinding(symbol)`.
3. Extend `TypeScriptImportFixer` to rewrite import **style** to match the registry's binding (`import X` ↔ `import { X }`) — the provably-correct default↔named fix, no LLM.

**Tests:** extend `TypeScriptExportRegistryTest` (binding capture) and `TypeScriptImportFixerTest` (default↔named rewrite against a registry fixture).

**Effort:** S (mostly wiring + a regex-group capture).

---

## Sequencing

1. **Change 1 first** — ✅ done. Biggest converge-rate win, unblocks the agent's existing `bulk_str_replace`, no dependency on the others.
2. **Change 3 second** — ✅ done. Removes the import/path/binding classes from the loop entirely.
3. **Change 2 last** — ✅ done, as **Point B** (`ImportClosureChecker` detection + `MissingModuleSynthesizer` synthesis; see `architecture-json-completeness-plan.md` §8). Backend DTO synthesis still open.

After all three, the expected loop shape: **pre-pass clears imports/paths/bindings/missing-files (0 LLM rounds) → agent receives the full residual clustered by root cause → ≤5 `bulk_str_replace` rounds on genuine semantic divergence (nullability, prop reshaping, hook-API).**

## Risks / watch-items

- **Clustering key precision:** over-coarse grouping merges distinct fixes into one cluster the agent then can't bulk-fix; over-fine defeats the purpose. Key on `(code, symbol/module)` and validate against the captured real dumps.
- **`bulk_str_replace` false positives:** a class-wide rewrite of a common string can hit unintended sites. Constrain by the cluster's `affectedFiles[]` (which the clusterer now provides), not a blind directory sweep.
- **Missing-producer stub quality:** a deterministic layout stub must satisfy consumers' usage (children/Outlet) or it just moves the error. Model the stub on the verified `SiteLayout` shape.
- **Registry binding accuracy:** regex-lite export parsing has known blind spots (re-exports, multi-declaration). Only rewrite binding when the registry is confident; leave ambiguous cases to the LLM.
- **Do not touch derived files:** the `src/types/*` derived-file guard and its escalation note must survive the seed-render change (Change 1).

## Validation

Re-run on a **fresh branch** (no inherited fixes) to completion (not stopped mid-loop), same brief (`farmaaish-restaurant`). Success = frontend reaches SmokeTest with **0 LLM rounds** on the import/path/binding/missing classes and **≤5 rounds** total. Compare against this session's baseline (57-error seed, 30-round exhaustion).
