# Frontend Export Registry + Component Contract Card

**Status:** Design / build spec (not yet implemented)
**Date:** 2026-08-03
**Owner area:** `discovery-worker` — frontend generation & validation
**Motivating run:** `worker-620bb653` (Farmaaish Restaurant, brief `9bdf03a2`), attempts 1–4 — frontend build failed every attempt (30-round ErrorFixAgent exhaustion, `type=CODE`).

---

## 1. Problem — one pattern behind every error

Across the motivating run (backend seed, 57-error frontend seed, and the 28 residual errors on the branch), **not one error is "logic wrong inside a file."** Every single one is a **cross-file contract mismatch**: File A references something in File B (a symbol, path, field, method, prop, export style, or value-vs-type) and gets the interface wrong. The two files were produced by **separate LLM calls that never saw each other's actual output**, so they agree on *intent* and disagree on *interface*.

Every error sorts into one of four contract types (mutually exhaustive):

| Contract | The question generation got wrong | Example errors (this run) |
|---|---|---|
| **Existence** | Does the referenced thing exist / is it imported? | `AdminLayout` never generated (×6), `Badge` not imported (×4), backend `LocalTime` not imported (×5) |
| **Shape** | Does it have the field/method/prop I'm using? | `CheckoutController` methods (×4), `useEvents.createEvent/updateEvent` (×2), `AuthUser.id` (×2), `Reservation.specialRequests` (×1), `InquiryDto` (×1), `Calendar` props (×1), `mutateAsync` (×1) |
| **Nullability** | Does optional / `null` / `undefined` line up? | `string \| undefined` vs `string \| null` (×3); `EventForm` zod-optional `active` vs required resolver cascade |
| **Binding** | default vs named export? type vs value? | `siteConfig` default-vs-named (×1), `CheckoutStep` type-used-as-enum (×5), attempt-1 Layout/OrderDetailView/ReservationDetailModal default↔named swaps |

## 2. Root cause — the frontend has no symbol table

The **backend almost never fails this way, and the one time it did tells you why.** Backend generation reconciles every reference against a live symbol table — `JavaClassRegistry` (observed: `Merged 30 filesystem classes … total=77`), `ImportResolver`, the templater, `ApiInventory`, `EnvVarScanner`. Result: the backend seed had **exactly one error — `LocalTime`** — a `java.time` JDK type, i.e. *the one kind of symbol the registry doesn't track*. Where the symbol table has coverage → consistent. Where it doesn't → the same failure the frontend has everywhere.

**The frontend has no equivalent.** No registry of "what components/hooks/types exist, what they export, their props/return shapes" is fed into each consumer's generation. Every page→layout, page→hook, component→ui, consumer→spine boundary is generated **blind**. The existing mitigations (`ApiContractCard`, `TsTypeGenerator` dedupe) patch exactly **one** boundary — the derived **API types** — and do nothing for the *intra-frontend* boundaries, which is where all 28 residual errors live.

**The fix is not better prompts or more fix-loop rounds. It is giving the frontend the same contract-resolution layer the backend already has.**

### Design principle — replicate the two human faculties
A human writing `OrderPage` doesn't out-think the model; they have two things the generator lacks:
1. **Memory** of what they authored (they *know* `AdminLayout` doesn't exist yet, and that it's a default export).
2. **"See the contract"** — the LSP red-underlines `goToNextStep` instantly and autocompletes `next/back/goTo`.

The registry-card reconstructs faculty #2 at generation time. The ErrorFixAgent-with-typechecker is faculty #2 during repair. We need both.

---

## 3. The artifact — Component Contract Card (rendered into the prompt)

Represent contracts as **synthetic module declarations** (a hand-rolled `.d.ts` of the project's own modules). The model natively reads `declare module`, it's maximally compact, and it pins the three things that break: **path, binding, signature (with nullability).**

```
── FRONTEND MODULE CONTRACT ──────────────────────────────────────────
Authoritative. Import ONLY from these paths, ONLY these names, with the
binding shown. Match every prop/field/return EXACTLY, including `| null`
vs optional `?`. If you need something not listed here, it does not
exist — do not invent an import.

@/shell/AdminLayout                                          [component]
  export default function AdminLayout(props: { children: React.ReactNode }): JSX.Element

@/cart/types                                                 [types]
  export interface CheckoutStep      { id: string; label: string; validate?: () => boolean | string }
  export interface CheckoutController {
    steps: CheckoutStep[]; current?: CheckoutStep; index: number;
    isFirst: boolean; isLast: boolean; error: string | null; progress: number;
    next(): boolean; back(): void; goTo(id: string): void }
  ⚠ CheckoutStep/CheckoutController are TYPES, not values — no `CheckoutStep.PAYMENT`.

@/cart/useCheckout                                           [hook]
  export function useCheckout(steps: CheckoutStep[]): CheckoutController

@/components/ui/badge                                        [component]
  export const Badge: (props: { variant?: 'default'|'secondary'|'destructive'|'outline';
    className?: string; children?: React.ReactNode }) => JSX.Element

@/features/events/useEvents                                  [hook]
  export function useEvents(): { events: EventDto[]; isLoading: boolean;
    create: UseMutationResult<EventDto,Error,EventCreate>;
    update: UseMutationResult<EventDto,Error,EventDto> }
  ⚠ no `createEvent`/`updateEvent` — use `create.mutateAsync(...)`.

@/types/auth                                     [type · GENERATED api contract]
  export interface AuthUser { userId: string; email: string; roles: string[] }
  ⚠ no `id` field — the identifier is `userId`.
──────────────────────────────────────────────────────────────────────
```

Each `⚠` is auto-generated from a detected footgun (type-used-as-value candidate, near-miss field name, api-derived type). Those three annotations alone target the `CheckoutStep` (×5), `useEvents` (×2), and `AuthUser.id` (×2) errors.

## 4. Extraction schema (data model behind the render)

```ts
ModuleContract {
  importPath: string       // "@/shell/AdminLayout" — resolved via tsconfig `paths`
  filePath:   string
  provenance: "generated" | "api-derived" | "scaffold" | "planned"   // authority marker
  exports:    ExportSymbol[]
}
ExportSymbol {
  name:    string
  binding: "default" | "named"
  kind:    "component" | "hook" | "type" | "interface" | "enum" | "const" | "function"
  props?:   Field[]        // components
  params?:  Field[]        // hooks/functions
  returns?: string         // hooks/functions (flat render)
  fields?:  Field[]        // interfaces/types
  members?: string[]       // enums
}
Field { name: string; type: string; optional: boolean; nullable: boolean }  // `?` vs `| null` tracked separately
```

`optional` vs `nullable` are separate flags on purpose — that distinction *is* the `undefined` vs `null` error class.

---

## 5. Lifecycle — register per batch, topological order

Generation runs in **logically separate batches** (dependency layers), mirroring the backend's `ENUM→ENTITY→DTO→…→CONTROLLER`. **Register the batch's exports into the registry after each batch completes.**

**Batch order (topological):**
```
types/models → api/sdk → hooks → context → ui (scaffold, pre-registered)
→ components → shell/layouts → pages → App/router
```

Two conditions make batching correct:

1. **Order must be a true topological sort.** The `AdminLayout` bug is a batch-order failure — there was no "register shell/layouts **before** pages," so pages referenced a layout no batch had produced. Fixing the order makes that class structurally impossible; the pre-validation existence check (§8) catches any residue.
2. **Same-batch forward refs need a two-pass seed.** Within `components`, A may use B (same batch, B not yet registered). Handle with:
   - **Pass 1:** seed every batch member's contract from its **planned** signature in `ARCHITECTURE.json` (`provenance: "planned"`).
   - **Pass 2:** overwrite with the **real extracted** signature once the file exists.
   Cross-batch boundaries (the killers: page→hook, page→layout) are already covered by ordering; the two-pass only closes same-batch.

**Resume (attempts 3/4):** `GitWorkspaceNode` re-clones the branch; **rehydrate the registry by scanning the cloned workspace** — exactly as `JavaClassRegistry` does (`Merged 30 filesystem classes from /workspace/backend/src/main/java`). No separate persistence needed.

---

## 6. Field reduction — eliminate, don't thin

Reduce card size by **removing fields a convention makes unnecessary**, never by thinning the signature (thinning re-opens the exact bugs).

**Safe reductions:**
- **Drop `binding` entirely** by *enforcing* an export convention: **components default-export; hooks/types/consts/utils named-export.** Binding becomes derivable from `kind` and is never stated — and this *removes the default↔named error class at the source.* Biggest single reduction.
- **`provenance` → sparse marker.** Only tag authoritative modules (`api-derived`, `scaffold`); `generated` is the unmarked default.
- **Don't restate API-derived types** — they already live in `ApiContractCard`. The registry references them; it doesn't duplicate.

**Do NOT thin:** `fields[].nullable/.optional`, hook `returns`, component `props` names *are* the error classes (`AuthUser.id`, `useEvents.createEvent`, `undefined vs null`). Cutting them re-creates the bugs.

**Get token budget from scoping, not thinning:** full detail only for the file's **declared manifest imports**; a one-line index (`path — names [kind]`) for everything else (so the model can still *discover* a path for something it needs but wasn't handed — the "I need an admin layout" → finds `@/shell/AdminLayout` case).

Net core card: ~4 fields (`path`, `kind`, `exportName`, flat `signature`) + sparse `⚠`/provenance markers.

---

## 7. Storage — three layers; nothing new persisted as truth

The registry is **derived data.** Persisting derived data as truth re-creates the divergence disease. Truth stays in the code.

| Layer | Lifetime | Where | Contents |
|---|---|---|---|
| **Persistence** | survives container death | **repo (already exists)** | Real contracts = the `.tsx/.ts` export statements; Planned contracts = `ARCHITECTURE.json` |
| **Runtime** | per container | **in-memory `FrontendExportRegistry`** | rebuilt from workspace (fresh: incrementally per batch; resume: scan cloned branch) |
| **Prompt cache** | per LLM call (~5-min TTL) | **Anthropic prompt cache** | the rendered Contract Card in the system-prompt prefix |

**Rejected alternatives:**
- **DB table (`module_contract`):** creates a second source of truth that diverges from the branch after a partial push or a fix-agent edit. `generated_file` stays the *progress ledger* ("Layer [DTO] — all 7 already done, skipping"), not a contract store.
- **Committed `contracts.json` as truth:** stale the instant the fix agent edits a file → generator trusts stale ground truth = the bug, re-created.

**Only new file written to the repo — a write-only debug artifact:** `docs/FRONTEND_CONTRACTS.md`, regenerated every run, human-readable, same family as `SMOKE_REPORT.md` / `API_CONTRACT_REPORT.md`. For eyeballing *"what did the registry see when it generated `OrderPage`?"* — **never read back into generation.**

---

## 8. ErrorFixAgent integration

Inject the Contract Card into the fix agent's **cached** system prompt (this is the Anthropic prompt cache, run-constant → ~free across all 30 rounds — the mechanism behind the millions of `cache_read` tokens observed).

**Why it matters:** the fix agent currently whack-a-moles *because it also lacks the contracts* — observed burning rounds on `read_file(useAuth.ts)`, `read_file(AuthContext.tsx)` to **manually reconstruct** a contract the registry already has. The card removes that rediscovery cost (rounds *and* context window).

**⚠ Staleness split — the one caveat.** The fix loop *mutates files*, so contracts change mid-loop; a frozen card can go stale (agent adds `createEvent`, card still says it's absent → agent fights its own ground truth). Therefore:
- **Cache only the immutable/authoritative contracts** — api-derived types, the `cart` spine, ui primitives, anything marked do-not-edit.
- **Keep actively-edited consumer files OUT of the cached card** — the agent sees those through its own edits / `read_file`.

**Bonus — generalize `derivedFileGuard`.** Once the card is authoritative, give the agent the rule: *"these contracts are ground truth — fix the consumer to match, never edit the contract file."* This extends the existing `derivedFileGuard` (which already forbids editing derived types and redirects to fixing importers) from derived-types to the whole spine, killing the agent's most expensive failure mode (rewriting the do-not-edit spine to satisfy a consumer).

**Existence check for free.** The registry *is* the pre-validation existence gate: any manifest import whose resolved path isn't registered = missing producer → generate it (e.g. `AdminLayout`) **before** handing to validation. This is where the enforced export convention (§6) is also asserted.

---

## 9. Error-class → card-field traceability

The test that the artifact is designed against the *actual* failure surface — every error from the run maps to a specific field.

| Card field | Error class killed | This run |
|---|---|---|
| `importPath` (resolved) + existence check | wrong path / missing file (TS2307) | `AdminLayout` ×6 |
| one-line index → discoverable imports | component exists but not imported (TS2304) | `Badge` ×4 |
| enforced export convention (replaces `binding`) | default↔named (TS2613/2614) | `siteConfig`, attempt-1 swaps |
| `kind` + `⚠ TYPE not value` | type-used-as-value (TS2693) | `CheckoutStep` ×5 |
| `returns` / `params` on hooks | method-not-found / wrong arg count (TS2339/2554) | `CheckoutController` ×4, `useEvents` ×2 |
| `fields` + `⚠ near-miss` | field-not-on-type (TS2339/2322) | `AuthUser.id`, `Reservation.specialRequests`, `InquiryDto` |
| `Field.optional` vs `Field.nullable` | `undefined` vs `null` (TS2322) | UserProfileForm/ReservationForm ×3 |
| `props` on components | prop mismatch | `Calendar`, `DeleteTestimonialDialog.onConfirm` |

---

## 10. Where it plugs into the worker

- **New:** `util/FrontendExportRegistry.java` — extract (parse top-level exports, same AST-lite approach as `ApiInventory`), register (per batch), render (Contract Card), resolve import paths through `tsconfig.app.json` `paths`.
- **`FrontendGeneratorNode`** — drive the topological batch order; populate the registry after each batch; render + inject the card via the existing `generateFileContent(..., sharedContext)` system-prompt overload (built for `ApiContractCard`).
- **`BackendValidationNode` / a new frontend pre-validation step** — run the existence gate (missing-producer → generate) and convention assertion before `npm run build`.
- **ErrorFixAgent** — inject the immutable-contracts card into its cached system prompt; add the "contracts are ground truth, fix the consumer" rule (generalized `derivedFileGuard`).
- **`ARCHITECTURE.json`** — source of the planned-signature seed (Pass 1); no schema change.
- **Debug artifact** — write `docs/FRONTEND_CONTRACTS.md` at end of frontend generation.

## 11. Open questions / risks

- **AST-lite vs real parser:** `ApiInventory`'s regex approach has known blind spots (class-boundary tracking, multi-var declarations). A React/TS extractor hits the same risk for prop/return inference. Decide: regex-lite (fast, some misses) vs a real TS parser pass (accurate, heavier). Prop-type inference for complex components is the hardest case.
- **Export-convention migration:** enforcing "components default-export" means the scaffold + prompts + any existing generated files must agree. Needs a one-time normalizer (extend `UiImportRewriter`) so mixed-style existing code converges.
- **Token budget at scale:** a large project's full index could still be big. Validate the scoping (detail-for-imports + one-line-index) stays within budget on a 140-file frontend.
- **Same-batch two-pass cost:** Pass-1 planned seeds depend on `ARCHITECTURE.json` signature quality; the planner's known "empty `imports_from`" blindness may weaken the seed. Measure whether planned seeds are accurate enough or whether same-batch ordering (sub-topo within batch) is needed instead.

---

## TL;DR

Every frontend build error is a cross-file contract mismatch; the backend avoids them because it generates against a live symbol table (`JavaClassRegistry`) and the frontend doesn't. Build the frontend's equivalent — a `FrontendExportRegistry` populated per batch in topological order, rendered as a compact `.d.ts`-style **Contract Card** injected into the (cached) generation and fix-agent system prompts. Truth stays in the code (source files + `ARCHITECTURE.json`); the registry is an in-memory rebuild; nothing new is persisted as state except a write-only `docs/` debug snapshot. Reduce the card by enforcing an export convention (kills the binding class outright) and by scoping (detail for imports, index for the rest) — never by thinning the signature.
