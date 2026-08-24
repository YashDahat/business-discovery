# Pipeline Changes Report — Planning, Validation & ErrorFixAgent

**Status:** Implemented + unit-tested (32 tests across 8 classes, all green); **uncommitted (staged)** and **e2e-unverified**.
**Date:** 2026-08-06
**Scope:** `discovery-worker` — `ProjectPlanningNode`, `BackendValidationNode`, `FrontendValidationNode`, `ErrorFixAgent`, and the supporting utilities/prompts they call. One foundation-repo change (`webapp-foundation`, pushed).
**Design rationale (deeper):** `docs/frontend-fix-loop-convergence-plan.md`, `docs/architecture-json-completeness-plan.md`, `docs/frontend-export-registry.md`.

---

## 1. Executive summary

Two recurring pipeline failures were addressed:

1. **Missing producers.** A module referenced by generated code but never generated — `AdminLayout` (a layout named only in feature prose, never listed in `ARCHITECTURE.json`) and `OrderItemResponse` (a DTO invented in generated code, never in the manifest). These became `TS2307` / "cannot find symbol" cascades the `ErrorFixAgent` could not author its way out of (`AdminLayout` survived 3 attempts / ~90 fix rounds).
2. **Fix-loop non-convergence.** The frontend fix loop exhausted all 30 rounds resolving nothing, because the agent was seeded a **truncated** error view (`8 of 57`) it could neither cluster nor bulk-fix.

The work spans three threads, mapped onto the components you asked about:

| Thread | Component(s) touched |
|---|---|
| **Completeness invariant** (missing producers, plan-time + gen-time) | `ProjectPlanningNode`, `FrontendValidationNode`, `BackendValidationNode` |
| **Deterministic import pre-pass** (wrong paths, default↔named) | `FrontendValidationNode` |
| **Fix-loop convergence** (complete, clustered errors) | `ErrorFixAgent` |

**Result:** a two-point enforcement of the invariant *"every referenced module exists,"* on **both** stacks — planned/prose misses get a proper spec at plan time; gen-time inventions get detected and repaired at build time — plus a fix loop that now sees the complete error set grouped by root cause.

---

## 2. `ProjectPlanningNode` — plan-time completeness (Point A)

**Problem:** the outline stage owns the `files[]` list but referenced `AdminLayout` only in feature prose; enrichment left `imports_from` ~27% empty; the existing `validateCrossReferences` read only `imports_from`, so the missing producer was never added → never generated.

**Changed:** `validateCrossReferences(spec)` → **`validateCrossReferences(spec, workspace, briefCtx)`**, now:
1. **Detects** referenced-but-unplanned modules on both sides (on-disk aware, so a foundation file like `SiteLayout` is never stubbed):
   - `ManifestCompletenessChecker.findMissingFrontend` — provided = `files[]` ∪ on-disk `frontend/src`; referenced = `imports_from` **plus `@/…` refs mined from prose** (`FeatureSpec.featureInstruction` + `FileSpec.description`, filtered to component/hook segments). *This is what catches `AdminLayout`.*
   - `ManifestCompletenessChecker.findMissingBackend` — provided = backend `files[]` ∪ on-disk `backend/src/main/java`; referenced = `backend/` `imports_from` paths (path-based, no prose).
2. **Specs** each miss into a proper `FileSpec` via `LlmGeneratorService.specifyMissingFiles` (Flash, stack-agnostic), seeded with the referencing context (who referenced it + the referencing files' descriptions + the feature prose) and an on-disk exemplar (`SiteLayout` for the frontend). Falls back to the existing `buildStubEntry` if the LLM declines/errors — so a miss **always** resolves.
3. New helpers: `pending`, `applyMissing`, `specifyMissing`, `readExemplar`.

The additions land in the in-memory `spec` and are persisted by the node's existing final `ArchitectureJsonUtil.write` → the generator produces the file.

**New/used elsewhere:** `util/ManifestCompletenessChecker.java`, `LlmGeneratorService.specifyMissingFiles(...)`, prompts `system|user/spec_missing_files.txt` (full-stack — `file_type` inferred from path).

---

## 3. `BackendValidationNode` — backend gen-time repair (Point B, backend)

**Problem:** a class invented in generated code but never planned (`OrderItemResponse`, referenced by `OrderResponse`/`OrderService`) is invisible to plan-time checks — it appears nowhere in the manifest. `javac` detects it as "cannot find symbol: class X," but the `ErrorFixAgent` reliably fails to author the whole missing file.

**Changed:** after the mechanical injectors (`MavenDependencyInjector`, `RepositoryMethodInjector`) and before the `ErrorFixAgent`, added a **backend synthesis** step:
- Track the latest compile output (`BuildResult latest`).
- `BackendClassSynthesizer.synthesize(backendSrcJava, latest.output())` — parses `symbol: class X` / `location: class <FQN>`, resolves the target **package** (the referencer's `import` of `X`, else the referencer's own package; nested/builder locations resolve to the top-level package), and writes a minimal placeholder (`*Exception` → `extends RuntimeException`; else empty class). Skips existing files.
- Recompile; if green, `rescanValueBindings` + `markFilesValidated` + return; else residual errors go to the `ErrorFixAgent`.

**Honest limitation:** Java has no `any`, so a placeholder resolves the missing **type** but not every getter/constructor — it converts *"author a whole file"* into *"add members to an existing class,"* which the agent handles well.

**New:** `util/BackendClassSynthesizer.java`.

---

## 4. `FrontendValidationNode` — deterministic pre-pass (Change 3) + gen-time closure (Point B, frontend)

Two additions, both **after** the existing mechanical fixers and **before** the `ErrorFixAgent`.

**4a. Change 3 — registry-driven import correction (pre-pass step 6).**
`TypeScriptImportFixer.fixAll(frontendSrc, workspace, TypeScriptExportRegistry.buildFromDisk(...))` runs on the complete file set: fixes wrong `@/`/relative **paths** and **default↔named** mismatches (`TS2613`/`TS2614`) — provably-correct rewrites only; ambiguous cases deferred to the agent.
- `TypeScriptExportRegistry` gained **binding** capture (`enum Binding{DEFAULT,NAMED}` + `resolveBinding`) and a `buildFromDisk` factory.
- `TypeScriptImportFixer` gained the reverse **default→named** rewrite, a boolean `fix()` return, and a `fixAll()` batch.

**4b. Point B — gen-time closure assertion + repair.**
`ImportClosureChecker.check(frontendSrc)` scans the **real** `import`/`export … from` statements and finds every local (`@/`,`./`,`../`) specifier that resolves to no file on disk — catching modules invented only at generation, which plan-time cannot see. Then:
- **Default = repair:** `MissingModuleSynthesizer.synthesize(...)` writes a permissive placeholder per miss (default + every named symbol as value *and* type, with the component form **passing `children` through** so a synthesized layout never blanks the page), rebuild → if green, skip the agent; else residual type errors go to the agent. Writes `docs/IMPORT_CLOSURE_REPORT.md`.
- **Strict** (`worker.completeness.import-closure-strict=true`): fail loud (`WorkerException(CODE)`) without stubbing.

**New/extended:** `util/ImportClosureChecker.java`, `util/MissingModuleSynthesizer.java`; extended `util/TypeScriptExportRegistry.java`, `util/TypeScriptImportFixer.java`. Added a `@Value` strictness field.

---

## 5. `ErrorFixAgent` — complete, clustered errors (Change 1)

**Problem:** the seed was `prioritizeCompilerOutput(output, 6000)` — truncated to `8 of 57`. The agent cannot cluster or `bulk_str_replace` errors it never sees, so it burned all 30 rounds.

**Changed:**
- **Complete, non-incremental seed:** the frontend fix loop now seeds from `buildTool.runTscCheck` → `npm run typecheck` (`tsc --noEmit -p tsconfig.app.json`), which emits the full type-error set every run (unlike incremental `tsc -b`). A **defensive fallback** to `npm run build` fires if the `typecheck` script is absent (older scaffolds) so the seed is never an npm error.
- **Root-cause clustering:** new `renderErrors(fileType, output, maxChars)` helper routes **frontend** output through `CompileErrorClusterer` (groups by `code + subject symbol`, biggest-first, each with its complete affected-file list — the input `bulk_str_replace` needs; one-line summaries under budget so no cluster is dropped) at all three agent-facing sites — the **seed**, the `autoVerify` post-round hook, and the `run_compiler` tool. **Backend / non-tsc** output falls back to the existing `prioritizeCompilerOutput`.

**New:** `util/CompileErrorClusterer.java`. **Foundation dependency:** the `typecheck` script (see §7).

---

## 6. New & modified files

**New utilities (`discovery-worker/.../worker/util/`):**

| Class | Role | Used by |
|---|---|---|
| `CompileErrorClusterer` | cluster full tsc output by root cause | `ErrorFixAgent` |
| `ManifestCompletenessChecker` | plan-time detect missing modules (FE prose+on-disk / BE imports_from+on-disk) | `ProjectPlanningNode` |
| `ImportClosureChecker` | gen-time detect unresolved FE imports over real code | `FrontendValidationNode` |
| `MissingModuleSynthesizer` | gen-time repair — permissive TS placeholders | `FrontendValidationNode` |
| `BackendClassSynthesizer` | gen-time repair — minimal Java placeholder classes | `BackendValidationNode` |

**Modified:**
- `nodes/ProjectPlanningNode.java` — completeness pass + LLM speccing + helpers
- `nodes/FrontendValidationNode.java` — `TypeScriptImportFixer.fixAll` + closure check/repair + `@Value` flag
- `nodes/BackendValidationNode.java` — `BackendClassSynthesizer` step + `latest` output tracking
- `nodes/ErrorFixAgent.java` — `renderErrors`/clustering + `runTscCheck` seed + fallback
- `service/llm/generator/LlmGeneratorService.java` — `specifyMissingFiles` (stack-agnostic, robust parse)
- `util/TypeScriptExportRegistry.java` — binding + `buildFromDisk`
- `util/TypeScriptImportFixer.java` — default→named + `fixAll`

**Prompts (new):** `resources/prompts/system/spec_missing_files.txt`, `resources/prompts/user/spec_missing_files.txt`.

---

## 7. Foundation change (`webapp-foundation`, pushed to `main`)

`frontend/package.json` gained `"typecheck": "tsc --noEmit -p tsconfig.app.json"`. Required because `ErrorFixAgent` seeds from `npm run typecheck`; the correct form is `-p tsconfig.app.json` (the bare `tsc --noEmit` reads the root `tsconfig.json` with `files: []` and checks nothing). Verified against a real project: emits the complete error set (28/28) non-incrementally.

---

## 8. The enforcement model (both stacks)

| | Frontend | Backend |
|---|---|---|
| **Point A** — plan-time detect + spec (`ProjectPlanningNode`) | `findMissingFrontend` → `specifyMissingFiles` | `findMissingBackend` → `specifyMissingFiles` |
| **Point B** — gen-time detect (validation nodes) | `ImportClosureChecker` | `javac` ("cannot find symbol") |
| **Point B** — gen-time repair (validation nodes) | `MissingModuleSynthesizer` | `BackendClassSynthesizer` |

*Plan-time* produces proper specs for planned/prose misses; *gen-time* catches and repairs inventions the plan can't see. Point A + Point B together = the invariant *"every referenced module exists."*

---

## 9. Configuration

| Key | Default | Effect |
|---|---|---|
| `worker.completeness.import-closure-strict` | `false` | `false` = frontend closure **repairs** (synthesize + rebuild); `true` = **fail loud** without stubbing |

(The foundation `typecheck` npm script is required by the `ErrorFixAgent` seed.)

---

## 10. Tests

All green; run via `./mvnw -f discovery-worker/pom.xml test -Dtest=<Class>`.

| Test class | Tests | Covers |
|---|---|---|
| `CompileErrorClustererTest` | 5 | clustering, ordering, tsc-detection, budget one-liners |
| `TypeScriptExportRegistryTest` | 6 (+2) | binding capture, `resolveBinding`, catalog |
| `TypeScriptImportFixerTest` | 4 | default↔named both directions, no-op, safety guard |
| `ManifestCompletenessCheckerTest` | 5 | FE prose-only vs on-disk scaffold; BE dangling vs planned/on-disk |
| `LlmGeneratorServiceSpecMissingFilesTest` | 5 | FE/BE parse, `file_type` inference, junk → empty |
| `ImportClosureCheckerTest` | 3 | unresolved-only, empty when closed, report |
| `MissingModuleSynthesizerTest` | 3 | default+named placeholders, children pass-through, skip-existing |
| `BackendClassSynthesizerTest` | 5 | same/imported package, `*Exception`, skip-existing, `packageOf` |

---

## 11. Status, limitations & verification

**Done:** everything above compiles and is unit-tested.

**Deliberately deferred (one item, both stacks):** the **"targeted LLM gen"** route that produces *real* content for a gen-time miss instead of a deterministic placeholder. Point A already specs planned/prose cases properly; the placeholders cover the rarer gen-time inventions and are meant to be replaced by the ErrorFixAgent or a client change.

**Known limitations:**
- Backend placeholder resolves the missing *type*, not every member (see §3).
- `ImportClosureChecker`/prose detection are regex-based (documented blind spots for multi-line/dynamic edge cases).
- Frontend synthesis handles `@/` and relative specifiers; namespace/`import type` default edges are best-effort.

**Verification state — important:**
- **Unit-tested + compiles:** yes (32 tests, clean `test-compile`).
- **Committed:** **no** — all changes are **staged only**, accumulated across many increments. Review and commit as one or a few logical commits.
- **End-to-end:** **unverified.** No piece has run in a real pipeline; the earlier `worker-620bb653` / `worker-8a53ac13` runs were terminated before completion. A clean-branch run to a finished PR is what would actually validate the chain.
