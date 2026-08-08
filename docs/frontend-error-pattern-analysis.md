# Frontend Generation Error Patterns — farmaaish as-generated

**Date:** 2026-08-08
**Source:** `farmaaish-restaurant` branch commit `31b8b37` (frontend right after `FrontendGeneratorNode`,
**before** the ErrorFixAgent touched anything). Reconstructed via `git worktree` + `tsc --noEmit -p tsconfig.app.json`.
**Purpose:** enumerate every error the generator produced and cluster it into patterns, so we fix the
**generator** (prompts / deterministic patchers) instead of paying the ErrorFixAgent to clean up each run.

## Headline numbers

- **65 raw type errors** across **41 files** as generated.
- The fix loop drove this to **7** over 30 rounds, then exhausted. (The "57" seen mid-run was the
  agent's seed *after* the deterministic pre-pass had already removed a few.)
- **~72% of the 65 are deterministically fixable without the LLM** — see "Leverage" below. The single
  biggest pattern (JSX namespace, 48%) is a one-line mechanical fix.

| # | Pattern | Count | % | Mechanically fixable? |
|---|---|--:|--:|---|
| A | `JSX.Element` used with no `JSX` namespace in scope | **31** | 48% | ✅ deterministic patcher |
| B | Default ↔ named import mismatch | 8 | 12% | ✅ (TypeScriptImportFixer) |
| C | Symbol/module used but not imported (or doesn't exist) | 8 | 12% | ◑ mostly |
| D | Type assignability — nullability & RHF/zod resolver | 12 | 18% | ✗ needs type reasoning |
| E | Function arity / signature | 1 | 2% | ✗ needs contract |
| F | Shell/`siteConfig` prop-shape mismatch | 2 | 3% | ◑ contract-driven |
| G | `process.env` in browser code | 2 | 3% | ✅ deterministic patcher |
| H | Implicit `any` parameter | 1 | 2% | ◑ |

---

## Implementation status (2026-08-08)

Deterministic coverage now runs in the `FrontendValidationNode` pre-pass, **before** the ErrorFixAgent:

| Pattern | Fix | Status |
|---|---|---|
| A · JSX namespace (31) | `JsxTypeImportFixer` (pre-existing) + prompt rule (`file_generate_frontend.txt` rule 0) + insertion hardening | ✅ done |
| B · default↔named (8) | `TypeScriptImportFixer` (pre-existing); `AuthContextType` via `FOUNDATION_CONTRACT` | ✅ covered |
| C · `useQuery` missing (4 of 8) | **`TanStackImportFixer` (new this session)** | ✅ done |
| C · shadcn `progress` module-not-found (`TS2307`) | ✅ **foundation pre-installed 43 shadcn components** (was 25; committed `webapp-foundation` `6387476`) | ✅ done |
| C · `Card` / lucide-icon used-but-unimported (`TS2304`) | ✅ **`UiImportRewriter` rule 3 + `NodeModuleExportRegistry` (this session)** — adds the import for any capitalized JSX tag resolvable in local shadcn OR a scoped node_modules package | ✅ done |
| C · SDK-member gap (1, e.g. `deleteGalleryImage`) | ApiInventory / SDK-derivation completeness | ⬜ remaining |
| D · nullability & RHF/zod resolver (12) | ✅ **prompt rule 3b (this session)**: ban `.default()` in form schemas + name/coerce DTO fields | ✅ done (prompt-only) |
| E · `useCheckout(steps)` (1) | `FOUNDATION_CONTRACT_FRONTEND` | ✅ covered |
| F · siteConfig / NavLink shape (2) | `FOUNDATION_CONTRACT_FRONTEND` | ✅ covered |
| G · `process.env` (2) | **`ProcessEnvPatcher` (new this session)** | ✅ done |
| H · implicit `any` (1) | prompt nudge | ⬜ remaining |

**New this session:** `ProcessEnvPatcher` + `TanStackImportFixer` (patchers), the shadcn pre-install
(25→43 components), the JSX prompt rule + insertion hardening, **rule 3b for zod forms (Pattern D)**, and
**`NodeModuleExportRegistry` + `UiImportRewriter` rule 3** (add a missing import for any capitalized JSX tag
resolvable in local shadcn or a scoped node_modules package — Card, lucide icons, …). Combined with the
pre-existing fixers and the `FOUNDATION_CONTRACT`, **~63/65 (97%)** of the as-generated errors are now
addressed — deterministically, by contract, or by prompt rule. Residue: one SDK-member gap, one implicit-`any`.

**Guarantee vs prevention:** the patchers + shadcn pre-install + `FOUNDATION_CONTRACT` are hard guarantees;
the JSX and forms (rule 3b) *prompt rules* are prevention — they rely on the model following them, with the
JSX patcher as a deterministic backstop and (for D) no backstop yet.

---

## Pattern A — `Cannot find namespace 'JSX'` (TS2503) · 31 errors · **48%**

Every generated component/page annotates its return as `(): JSX.Element` but never brings `JSX` into
scope. Under the modern JSX transform (`"jsx": "react-jsx"`) with React 19 types, `JSX` is **not a
global namespace** — it lives under `React.JSX`. So a bare `JSX.Element` fails in all 31 files.

Affected (all 31): every `pages/*Page.tsx` (Home, Login, Profile, Contact, Catering, Gallery, NotFound,
Admin{Gallery,Inquiries,Menu,Orders,Reservations}) and most `components/**` (admin tables/dialogs/forms,
home/*, menu/*, checkout/PaymentComponent, contact/ContactDetails, inquiry/InquiryForm, profile/OrderHistoryTable).

> **Correction (2026-08-08):** a deterministic patcher for this **already exists** — `util/JsxTypeImportFixer`,
> wired into `FrontendValidationNode`; it fixed all 31 in the farmaaish run (the tip has none left). So this
> pattern is *not* what the fix loop burned rounds on. This session completed the two missing halves: the
> prevention **prompt rule** (`file_generate_frontend.txt` rule 0) and **hardening** the insertion so it
> never displaces a `'use client'` directive or a foundation fence marker off line 1. See
> `docs/generator-hardening-plan.md` W4.

**Root cause:** prompt/scaffold — the model's boilerplate return-type habit collides with the tsconfig.
**Generator fix (highest leverage in the whole pipeline):** one of —
- deterministic patcher: when a `.tsx` uses `JSX.Element`/`JSX.*` without it in scope, insert
  `import type { JSX } from 'react';` (or rewrite to `React.JSX.Element`); **or**
- prompt rule in `file_generate_frontend.txt`: "annotate components as `React.JSX.Element` or omit the
  return type; never bare `JSX.Element`."
Either removes **31/65 errors before the LLM runs**, which alone likely lets the loop converge.

## Pattern B — Default ↔ named import mismatch · 8 errors

The import form doesn't match the module's export style:
- **TS2613 (4)** — `import siteConfig from '@/config/siteConfig'` but it's a **named** export
  (`export const siteConfig`): `App.tsx`, `contact/InteractiveMap.tsx`, `AboutPage.tsx`, `ContactPage.tsx`.
- **TS1192 (2)** — default-importing a module with no default export: `CateringPage` ← `inquiry/CateringInfo`,
  `HomePage` ← `home/HeroSection`.
- **TS2614 (1)** — `import { MenuItemCard }` but it's a **default** export (`MenuItemsGrid.tsx`).
- **TS2724 (1)** — `import { AuthContextType }` from `@/context/AuthContext` — a **non-exported** interface
  (`useAuth.ts`). *This is a FENCED-contract miss → now covered by FOUNDATION_CONTRACT_FRONTEND.md.*

**Root cause:** the generator guesses export style. **Fix:** already the job of `TypeScriptImportFixer`
(default↔named) — extend to cover all four shapes; the `AuthContextType` case is fixed by the new
foundation contract.

## Pattern C — Used-but-not-imported / nonexistent symbol · 8 errors

- **TS2304 (6)** — `useQuery` used without import (`useInquiries.ts` ×2, `useReservations.ts` ×2);
  `Card` used without import (`MenuPage.tsx` ×2).
- **TS2307 (1)** — `import '@/components/ui/progress'` — the shadcn **progress** component was never
  installed/generated (`CheckoutPage.tsx`).
- **TS2305 (1)** — `import { deleteGalleryImage } from '@/services/galleryService'` — member not exported
  by the derived SDK (`AdminGalleryPage.tsx`).

**Fix:** import-resolver / prompt for the missing-import cases (useQuery, Card); the **shadcn pre-installer**
must include `progress` (and the NpmPackageFixer catch it); the SDK-member gap is an ApiInventory/derivation
completeness issue.

## Pattern D — Type assignability: nullability & RHF/zod resolver · 12 errors

- **TS2322 (7)** in `MenuItemForm.tsx` — a *cascade* from one root: the zod schema marks `description?`/
  `imageUrl?` **optional**, so the `Resolver<>`/`Control<>` type diverges from the form value type and errors
  on every `<FormField control={...}>` (lines 52, 84, 98, 116, 137, 151, 171). One root fix clears all seven.
- **TS2322 (3)** — date/Calendar typing: `InquiryForm` (`eventDate: string` vs `Date`; `<Calendar>` prop
  object incl. `initialFocus`), `ReservationForm` (`onSelect` Dispatch type).
- **TS2345 (2)** — `MenuItemForm`: `onSave({...})` optional `description`/`imageUrl` vs **required** DTO;
  and `handleSubmit(onSubmit)` `SubmitHandler` mismatch.

**Root cause (corrected 2026-08-08 from the fix-loop diff):** the 7-error `MenuItemForm` cascade was NOT
`.optional()` — it was **`z.boolean().default(false)`**. `.default()` makes a field OPTIONAL on the schema
INPUT type but REQUIRED on its OUTPUT type (`z.infer`), so `zodResolver(schema)` stops matching
`useForm<z.infer<typeof schema>>` and every `<FormField>` errors (removing `.default(false)` fixed all 7 in
one edit). The `TS2345`/submit errors are the separate impedance: the ApiInventory nullability fix made DTO
fields non-null, but generated forms spread `...values` (optional) into the required DTO.

**Fix — ✅ DONE (prompt rule, this session):** `file_generate_frontend.txt` **rule 3b** — (a) ban `.default()`
in form schemas (put defaults in `useForm({ defaultValues })`); (b) build the DTO by naming each field (no
`...values`) and coerce optional→required (`?? ''` / `?? null`). Plus a `.default()` line in the SELF-CHECK.
Prevention-only (no deterministic patcher — safely stripping `.default()` means relocating the default, too
risky to automate); the rule is validated by the fix that actually worked in-run.

## Pattern E — Function arity · 1 error

- **TS2554** — `CheckoutPage.tsx:17` `useCheckout()` expected 1 arg. *FENCED-contract miss → now covered by
  FOUNDATION_CONTRACT_FRONTEND.md (`useCheckout(steps)`).*

## Pattern F — Shell / `siteConfig` prop-shape mismatch · 2 errors

- **TS2741** — `GalleryPage.tsx:19` a nav link `{ label }` missing required `href`.
- **TS2353** — `siteConfig.ts:46` declares `mapCoordinates`, not part of the fenced `SiteConfig` type.

**Root cause:** LLM invents fields on / omits required fields of the fenced shell types. **Fix:** the
`SiteConfig`/`NavLink` shapes are now in FOUNDATION_CONTRACT_FRONTEND.md; consider a `siteConfig` validator.

## Pattern G — `process.env` in browser code (TS2591) · 2 errors

- `contact/InteractiveMap.tsx:8` and `ContactPage.tsx:25` use `process.env.*`. Vite exposes env via
  **`import.meta.env`**, and `process` isn't defined in the browser.

**Fix:** deterministic patcher `process.env.X` → `import.meta.env.VITE_X` + a prompt rule. Fully mechanical.

## Pattern H — Implicit `any` (TS7006) · 1 error

- `AdminReservationsPage.tsx:18` — a `.map((r) => …)` callback param without a type. **Fix:** prompt nudge
  or let inference improve once the surrounding SDK types resolve.

---

## Leverage — what a deterministic pre-pass would remove before the LLM

| Bucket | Errors | Fix |
|---|--:|---|
| JSX namespace (A) | 31 | patcher: add `import type { JSX }` / use `React.JSX.Element` |
| Default↔named (B) | 8 | extend `TypeScriptImportFixer` (+ contract for `AuthContextType`) |
| Missing `useQuery`/`Card` (part of C) | 6 | import resolver / prompt |
| `process.env` (G) | 2 | patcher → `import.meta.env` |
| **Deterministically removable** | **~47 (72%)** | **before any LLM round** |

That leaves ~15 genuine type/contract issues for the ErrorFixAgent (nullability/resolver, date typing,
shadcn `progress` install, SDK member, arity/config shape) — and of those, the **7 that actually survived**
this run were all **fenced-contract drift** (auth `isLoading`/`user.role`/`login(u,p)`/no `email`,
`useCheckout(steps)`, react-day-picker `initialFocus`, form↔DTO nullability), i.e. exactly what the new
`FOUNDATION_CONTRACT` cards + the form-nullability rule target.

## Recommendations (priority order)

1. ✅ **JSX patcher/prompt (Pattern A)** — DONE (patcher `JsxTypeImportFixer` pre-existed; this session added
   the prompt rule + hardened the insertion so it never displaces a directive/fence marker).
2. ✅ **`process.env` → `import.meta.env` patcher (G)** — DONE (`ProcessEnvPatcher`, wired + tested).
3. ✅ **Missing-import resolution for well-known symbols (C)** — DONE. TanStack hooks via `TanStackImportFixer`;
   JSX-tag components/icons via **`UiImportRewriter` rule 3** backed by the new **`NodeModuleExportRegistry`**
   (scans the project's declared node_modules dependency scope, symbol→package, unambiguous-only). Resolves
   both local shadcn (`<Card>` → `@/components/ui/card`) and library icons (`<ShoppingCart/>` → `lucide-react`).
   Default↔named (B) already covered by `TypeScriptImportFixer`.
4. ✅ **shadcn pre-installer completeness** — DONE: the foundation now ships **43** shadcn components
   (was 25; added `progress` + 17 more: alert, aspect-ratio, scroll-area, slider, pagination, breadcrumb,
   collapsible, hover-card, toggle(-group), command, drawer, carousel, navigation-menu, menubar,
   context-menu, input-otp), committed to `webapp-foundation` (`6387476`). Kills `TS2307` module-not-found.
5. ✅ **Form↔DTO nullability rule (D)** — DONE (prompt rule 3b): ban `.default()` in form schemas (the real
   7-error cascade cause), name-and-coerce DTO fields. Prevention-only — no patcher (relocating a stripped
   `.default()` is too risky to automate).
6. ✅ **Foundation contract (shipped)** covers the auth/checkout/siteConfig fenced-drift tail (part of B, E, F).

Net: A, B (existing fixers) + G, C-TanStack, C-JSX/registry (new patchers) + shadcn pre-install +
FOUNDATION_CONTRACT (E, F, part of B) + **D (rule 3b)** ≈ **63/65 (97%)** addressed before/without the fix
loop. Residue ≈ 2: one SDK-member gap (ApiInventory) and one implicit-`any`. This turns a 30-round exhaustion
into a near-empty fix loop — modulo the prevention caveat above (the JSX/forms *prompt rules* rely on the
model; the patchers, registry, pre-install, and contract are hard guarantees).
