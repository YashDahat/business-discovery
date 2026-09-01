# Frontend Issue → Solution Plan (abs-fitness brief `9312afa6`, attempt 3)

**Date:** 2026-08-30
**Source run:** `feature/gym-full_platform-9312afa6` (attempt 3, 2026-08-23)
**Evidence:** `docs/llm/interactions.jsonl` in the generated repo (23 frontend ErrorFixAgent rounds).

## Summary

Attempt 3's frontend build opened with **~61 TypeScript errors across ~19 root-cause clusters** and took the ErrorFixAgent **23 rounds** to clear (it *did* pass — the run later died at the backend smoke-boot S3 boolean gate, tracked separately in the typed-env fix).

Every error is a **cross-file contract mismatch** or a **hallucinated library surface** — not a local typo. Independently-generated units (pages / hooks / config / foundation) disagree on a shared contract, and the model invents API surface it can't verify. This document lists each issue and the solution **already available in the codebase**, plus the coverage gap that let it through. Scope: issue + existing solution only — no new implementation here.

---

## 1. Custom-hook `mutate` signature (TS2554 "Expected 1 arguments, but got 2") — 13 occ / 7 files — ✅ FIXED 2026-08-30

**Issue.** Pages call the idiomatic TanStack form `mutate(vars, { onSuccess, onError })`, but the generated hooks type `mutate` as a **single-argument** function, so the callback options arg is rejected.

**Root cause (verified).** `FrontendHookGenerator.emitMutation()` hand-writes a narrowed return-type annotation:
`util/FrontendHookGenerator.java:169` →
```
{ mutate: (vars) => void; mutateAsync: (vars) => Promise<T>; isPending; isError; error }
```
`(vars) => void` drops TanStack's real `mutate(vars, options?)` overload. Confirmed in the generated `frontend/src/hooks/bookingHooks.ts`.

**Already-available solution.** The mechanical hook layer itself (`FrontendHookGenerator`, called from `ApiArtifactGeneratorNode`) is the single owner of this signature — the fix has one deterministic home, no LLM. It already emits `mutateAsync` alongside `mutate`.

**Gap.** The emitted type is stricter than both TanStack reality and how pages call it. The generator controls the exact string, so it is fully fixable at source (widen `mutate` to accept the options param, or stop hand-annotating and let TanStack's inferred types flow). Highest-leverage, lowest-risk of the eight.

**Fix applied (2026-08-30, `FrontendHookGenerator.emitMutation`).** Both `mutate` and `mutateAsync` now declare the optional second arg `options?: MutateOptions<TData, Error, TVariables>` using TanStack's own exported type (imported as a type-only import, safe under `verbatimModuleSyntax`). The runtime body is unchanged — it still returns the real `useMutation` `mutate`/`mutateAsync`, which are exactly assignable to the widened signature. `2-arg mutate(vars, { onSuccess, onError })` calls now compile. Unit-verified (`FrontendHookGeneratorTest`, 12/12).

---

## 2. `AdminLayout` children (TS2559 `'{ children: Element; }'`) — 16 occ / 6 files — ✅ IMPLEMENTED 2026-08-31

> **Status.** Legs 1–5 implemented + unit-verified; only the optional `?redirect=` login enhancement remains.
> - **Legs 1–4 (mechanical core):** `RouteManifest` (`RouteGate` enum + `gate`-based derivation + nav flips), `RouteManifestGenerator` (`routes.ts` `gate` field/type + gate-bucketed `AppRoutes.tsx`), new `AdminLayoutWrapperPatcher` wired at `FrontendValidationNode` step 5f. Tests: `RouteManifestGeneratorTest` (16), `AdminLayoutWrapperPatcherTest` (4), `RouteManifestReconcilerTest` (9) all green.
> - **Leg 5 (prompt):** the real culprit turned out to be an explicit *instruction to wrap* — `file_generate_frontend.txt` / `file_generate.txt` / `feature_enrichment.txt` / `user/arch_outline.txt` all told the model "Admin*Page.tsx wraps in `<AdminLayout>`". Rewrote all four to "admin pages return content DIRECTLY; AdminLayout is applied by the admin layout-route; never import/render `<AdminLayout>` in a page (Outlet layout-route, no `children` → TS2559)", and fixed the stale `routeTable.filter(r => !r.admin)` nav hints to `r.gate !== 'admin'`. (This beats adding to a contract card — it removes the contradiction at source, the same prompt-contradiction class that sank attempt 2.)
> - **Confirmed:** the foundation ships `AdminLayout` as an `<Outlet/>` layout-route with no `children` prop (`webapp-foundation/frontend/src/components/AdminLayout.tsx`), so leg 1's nesting is correct against the real base.


**Issue.** Foundation `AdminLayout` renders via the router `<Outlet>` pattern and has **no `children` prop**; generated admin pages wrap content `<AdminLayout>…</AdminLayout>`.

**Already-available solution.** `FoundationContractCard` injects `frontend/FOUNDATION_CONTRACT.md` into the (cached) system prompt as immutable ground truth, and `FrontendContractCard` scans `shell/` (AdminLayout, SiteHeader, SiteLayout, SiteFooter) as immutable — `util/FrontendContractCard.java:173`. The contract *documents* the Outlet shape.

**Gap.** Contract is **prompt-only** and was ignored. No mechanical patcher rewrites `<AdminLayout>children</AdminLayout>` usage. (Note: the agent "fixed" it by adding `children` to the foundation `AdminLayout` — mutating a fenced file, the opposite of intent.)

**Real root cause (verified).** This is not a stray LLM guess — the generator *designs it in*. `RouteManifestGenerator` emits admin routes **flat** and comments the assumption explicitly (`util/RouteManifestGenerator.java:247-283`):

> "Admin routes stand alone (their page components carry AdminLayout); public routes are wrapped in the foundation SiteLayout." → `<Route path="/admin/bookings" element={<ProtectedRoute><AdminBookingsPage /></ProtectedRoute>} />`

So the route table gives admin pages **no layout-route**, which forces each page to self-wrap `<AdminLayout>{content}</AdminLayout>`. But the foundation authored `AdminLayout` as an **Outlet layout-route** component (renders `<Outlet/>`, no `children`) — exactly like `SiteLayout`. The generator assumes a children-wrapper; the foundation ships an Outlet layout. That contradiction *is* the bug. (Confirming it: the agent's attempt-3 "fix" added `children?: ReactNode` to the signature but left `<Outlet/>` in the body — it silenced TS2559 while guaranteeing the wrapped content would never render. A runtime bug hidden behind a green compile.)

### Why the wrapper belongs in the route table

Not primarily OCP (though OCP applies loosely — adding a ninth admin page should mean **a row in the manifest, not an edit to layout code**). The two sharper reasons:

- **`AdminLayout` is a fenced foundation file.** The page-side wrapper was structurally pushing us toward mutating it (which is exactly the half-fix the agent shipped in attempt 3). Moving the wrapper to the route table takes that pressure off the fenced file entirely.
- **The route table is already the single place that knows a page's gate.** `RouteManifest` is *the* source of truth for path + guard. Putting the layout/guard wrapper anywhere else (page body, AdminLayout children) **duplicates knowledge the manifest already holds** — the same drift that produced "1 admin route of 7" before the manifest existed.

### Target output (`AppRoutes.tsx`)

Three gate groups. Admin gets guard + admin chrome once at the group level; public gets `SiteLayout`; login-required-but-not-admin pages sit in a nested guard **inside** `SiteLayout` so a guest still sees site chrome while being redirected.

```tsx
<Routes>
  {/* admin: guard + admin chrome */}
  <Route element={<ProtectedRoute allowedRoles={['ADMIN']}><AdminLayout /></ProtectedRoute>}>
    <Route path="/admin" element={<AdminDashboardPage />} />
    <Route path="/admin/bookings" element={<AdminBookingsPage />} />
    <Route path="/admin/classes" element={<AdminClassesPage />} />
    <Route path="/admin/inquiries" element={<AdminInquiriesPage />} />
    <Route path="/admin/membership-plans" element={<AdminMembershipPlansPage />} />
    <Route path="/admin/trainers" element={<AdminTrainersPage />} />
    <Route path="/admin/media" element={<AdminMediaPage />} />
  </Route>

  {/* site chrome for everything public-facing */}
  <Route element={<SiteLayout config={siteConfig}><Outlet /></SiteLayout>}>
    <Route path="/" element={<HomePage />} />
    <Route path="/about" element={<AboutPage />} />
    <Route path="/classes" element={<ClassesPage />} />
    <Route path="/contact" element={<ContactPage />} />
    <Route path="/gallery" element={<GalleryPage />} />
    <Route path="/membership" element={<MembershipPage />} />
    <Route path="/trainers" element={<TrainersPage />} />
    <Route path="/trainer/:id" element={<TrainerDetailPage />} />
    <Route path="/cart" element={<CartPage />} />
    <Route path="/login" element={<LoginPage />} />
    <Route path="/signup" element={<SignupPage />} />

    {/* login required, still inside site chrome */}
    <Route element={<ProtectedRoute><Outlet /></ProtectedRoute>}>
      <Route path="/checkout" element={<CheckoutPage />} />
      <Route path="/account" element={<AccountPage />} />
    </Route>

    <Route path="*" element={<NotFoundPage />} />
  </Route>
</Routes>
```

**Nesting order is load-bearing on the inner group: `SiteLayout` outside, guard inside.** Reversed, the guard renders the `/login` redirect with no header/footer — jarring, and it loses the cart-preserved round trip the contract describes.

### The route config is mechanical (why legs 1–3 are reliable)

The route table is **worker-generated, not LLM-authored, and the LLM cannot edit it.** `RouteManifestGenerator` emits `routes.ts`, `AppRoutes.tsx`, the `App.tsx` shell and `AppProviders.tsx` via deterministic Java string builders (`emitRoutesTs` / `emitAppShell` / `emitAppProviders` + the AppRoutes derivation) from the plan-derived `RouteManifest`. The class states it outright — *"App.tsx is templated, not LLM-generated"* (`:21`), *"App.tsx is worker-derived and the LLM cannot edit it"* (`:70`) — and both `routes.ts` and `AppRoutes.tsx` carry `RouteManifest.PLAN_MARKER`, which the ErrorFixAgent refuses to touch (`RouteManifest.java:32`).

So legs 1–3 (the `RouteGate` model, the gate-bucketed emission, the `allowedRoles={['ADMIN']}` on the admin group) are **deterministic Java edits** — the grouping comes out byte-identical every run, with zero dependence on the LLM getting layout nesting right. This is why the admin-layout mismatch is a *pipeline* defect, not a prompt problem.

The one LLM-authored half is the **page bodies** (`pages/Admin*Page.tsx`) — which is exactly why they self-wrap in `<AdminLayout>`. Leg 4 (`AdminLayoutWrapperPatcher`) exists solely to reconcile that LLM output with the mechanical route table.

| Artifact | Generated by | Fix leg |
|---|---|---|
| `routes.ts`, `AppRoutes.tsx`, `App.tsx`, `AppProviders.tsx` | **Mechanical** (`RouteManifestGenerator`) | Legs 1–3 (deterministic) |
| `pages/Admin*Page.tsx` bodies | **LLM** | Leg 4 (unwrap patcher) |

### Solution — Option A: make admin a layout-route, mirror the public pattern

Keep the foundation `AdminLayout` **untouched** (it already renders `<Outlet/>` correctly) and fix what the generator controls. Legs are mechanical-first.

**1. `RouteEntry` gains a third gate state (primary).** `admin: boolean` cannot express "login required, not admin" — which is exactly why `/checkout` and `/account` came out **unguarded** (the emitter had nothing to read). Replace the boolean with an enum, in both the Java model (`RouteManifest.Entry`) and the emitted `routes.ts` `RouteMeta`:
   ```ts
   export type RouteGate = 'public' | 'auth' | 'admin';
   export interface RouteEntry {
     key: keyof typeof ROUTES;
     path: string; page: string; importPath: string;
     label: string; gate: RouteGate; nav: boolean;
   }
   ```
   Derivation in `RouteManifest.derive()`: admin-token pages → `'admin'`; keys `CHECKOUT` and `ACCOUNT` → `'auth'`; everything else → `'public'`. Also flip `nav` to **false** for `CHECKOUT` (checkout in the primary nav is wrong) and `CART` (the header already renders a cart button) — today `derive()` gives both `nav: true`.

**2. `RouteManifestGenerator` buckets by `gate` (primary).** Replace the per-page iteration (`util/RouteManifestGenerator.java:271-292`) with three emitted groups exactly as in Target output above; emit each block's `path="*"` last. Add `import AdminLayout from '@/components/AdminLayout'` (default import — see Confirmed below) to `AppRoutes.tsx`. This deletes the "pages carry AdminLayout" assumption at `:247` and `:278`.

**3. `ProtectedRoute` — reuse, don't rewrite.** It **already** accepts `allowedRoles?: string[]` and already reads the single-string `user.role` (`allowedRoles.includes(user.role)`). The gap was purely that the generator emitted `<ProtectedRoute>` with **no props** on admin routes, so any logged-in `USER` reached `/admin` (and signup mints `USER`s freely). Leg 2 passing `allowedRoles={['ADMIN']}` closes it with **no foundation change**. *(Optional, separate:* it currently redirects unauthenticated users to a bare `ROUTES.LOGIN`; adding `?redirect=<path>` would preserve the checkout/account round trip — a nice-to-have foundation edit, not required for the fix.)*

**4. Unwrap patcher (primary) — new `AdminLayoutWrapperPatcher`** in `FrontendValidationNode`'s deterministic pass (same family as `SiteConfigAccessPatcher` / `FrameworkNavigationPatcher`). For every admin page, rewrite the paired wrapper `<AdminLayout> … </AdminLayout>` → `<> … </>` (a fragment is a valid single route element, safe whether the wrapper held one child or several) and drop the now-unused **default** `import AdminLayout from '@/components/AdminLayout'`. Pages wrap in up to 3 return branches (loading / error / main), so it must unwrap **every** occurrence. Zero LLM; idempotent; provably correct once leg 2 provides the chrome via the layout route.

**5. Contract reinforcement (secondary) — `FrontendContractCard` / `FOUNDATION_CONTRACT.md`.** State the rule with its replacement (per the "every prohibition names the canonical path" guardrail): *"`AdminLayout` is a layout-route component that renders `<Outlet/>`. Admin pages render ONLY their own content and are mounted as child routes of the admin layout route — never wrap page content in `<AdminLayout>`."* The weak leg — it reduces how often legs 2/4 fire, not something to rely on.

**Why Option A, not "give `AdminLayout` a `children` prop" (Option B).** Option B mutates a fenced foundation file, diverges from the working `SiteLayout` pattern, and is the half-fix the agent already got wrong. Legs 2 and 4 are two halves of one change and are always applied together (unwrapping without the layout route would strip the admin chrome).

### Confirmed (the two open questions)

- **Import path & export style.** Verified against the generated repo: `AdminLayout` lives at **`@/components/AdminLayout` as a `default` export** — it is *not* in the `@/shell` barrel (which exports `SiteHeader`/`SiteFooter`/`SiteLayout` as **named**, plus the `SiteConfig` types). So legs 2 and 4 both use `import AdminLayout from '@/components/AdminLayout'` (default), which deliberately differs from `import { SiteLayout } from '@/shell'`. Note it sits in `components/`, outside the `shell/` set `FrontendContractCard` fences — still foundation scaffold, still don't mutate it.
- **Unmatched `/admin/*` URLs — decision: fall through to the public `*` route** (public 404 chrome). The admin group is a pathless guard+chrome for the seven real admin pages; a bogus admin URL is not a privileged surface, and one catch-all is simpler. React Router already does this: a pathless layout route only matches when one of its children matches, so `/admin/nonsense` falls to the public `<Route path="*">`. *(Alternative if admin-styled 404s are later wanted: add a `<Route path="*" element={<NotFoundPage/>}>` as the last child inside the admin group. Deliberately not doing that now.)*

---

## 3. `siteConfig` shape mismatch (TS2339 on `openingHours` / `mapCoordinates`; missing `header`) — ✅ IMPLEMENTED 2026-08-31 (v1, design tokens omitted)

> **Status.** Legs 1–4 implemented + unit-verified; design tokens omitted for v1 (foundation defaults).
> - **Legs 1–2 (mechanical):** new `SiteConfigGenerator.emit(manifest, brief, hasAuth)` — emits `frontend/src/config/siteConfig.ts` as the contract-shaped `{ header, footer }`, nav derived from the public `RouteManifest` entries (**Contact pinned last, Home first** — convention), brand/address/phone/openingHours from `BriefContext` (`openingHours` a string by construction), optional aesthetic fields omitted. `showAuth` ← auth flag; **`showCart` ← a `/cart` route is planned AND the business sells goods** (category/features `COMMERCE` signal, word-bounded to dodge workshop/marketing/delivery) — so a gym/salon shows no cart even with a scaffolded cart route. Wired in `FrontendGeneratorNode.synthesizeRouteRegistry` beside `emitRoutesTs`, PLAN_MARKER-fenced + added to `isFenced` + `markDerived`. Test: `SiteConfigGeneratorTest` (5) green; verified against the real abs-fitness brief (correct Pune address/phone, `showCart:false`, Contact last).
> - **Leg 3 (prompt/plan):** flipped all four prompts (`file_generate*.txt`, `arch_outline.txt` ×2, `feature_enrichment.txt`) from "you must generate siteConfig.ts" → "it is DERIVED — do not plan/generate; import & read it".
> - **Leg 4 (surface B, prompt):** stated `openingHours` is a string (never `.map()`), and map coordinates are page-local `const`s read from BUSINESS CONTEXT lat/lng, not siteConfig fields. `SiteConfigAccessPatcher` retained as belt-and-suspenders.
> - **Deferred:** design-token mapping (`colorScheme` → Tailwind classes) — v2 if the default theme reads generic.


**Issue.** `config/siteConfig.ts` is generated separately from the `SiteConfig` / `SiteFooterProps` contract in `shell/types.ts`; pages destructure footer fields that don't exist and the config omits `header`.

**Already-available solution.** `SiteConfigAccessPatcher` (`FrontendValidationNode.java:100`) — deterministic, zero-LLM backstop that rewrites flat `siteConfig.<field>` → nested `siteConfig.footer.<field>` for fields living in exactly one section (`util/SiteConfigAccessPatcher.java`). Documented shape lives in `arch_outline.txt` and the foundation contract.

**Gap.** The patcher fixes **flat-vs-nested access** of *existing* fields. It does **not** reconcile a `siteConfig.ts` whose *shape* diverges from the contract (missing `header`, `openingHours` typed as array vs string, non-existent `mapCoordinates`). Ambiguous/absent fields are deliberately left to the agent.

**Real root cause (verified).** `siteConfig.ts` is **LLM-authored** — the foundation contract literally says *"You must generate `src/config/siteConfig.ts`"* (`webapp-foundation/frontend/FOUNDATION_CONTRACT.md:140`). The `SiteConfig` shape is fixed and foundation-fenced (`shell/types.ts`) and documented in **four** places (that contract, `FrontendContractCard`, `SiteConfigAccessPatcher`'s docstring, the prompts) — and the model still got it wrong. Same "contract ignored" pattern as #2. Two distinct surfaces:
- **A — `siteConfig.ts` shape divergence.** The model authored a flat / header-less object and typed `openingHours` as an array; the `: SiteConfig` annotation then surfaced the mismatch (missing `header`, `openingHours` string-vs-array).
- **B — pages reading fields that aren't on the contract.** `ContactPage` destructured `siteConfig.footer.mapCoordinates` (doesn't exist) and `.map()`-ed `openingHours` (a string) — it assumed `siteConfig` carries page-specific data. The agent's fix moved those to page-local constants (`GYM_HOURS`, `MAP_LAT`, `MAP_LNG`).

**The data is derivable — so siteConfig should be mechanical, not LLM.** `SiteConfig` is config with a fixed contract, and almost every value is already in hand:
- `header.brandName` / `footer.brandName` ← `BriefContext.businessName`
- `header.navLinks` ← the **`RouteManifest`** public-nav entries (`gate !== 'admin' && nav`) — the *same single-source-of-truth* move as #2, which also kills nav-vs-config drift
- `footer.address` ← `BriefContext.address`; `footer.phone` ← `BriefContext.phone`
- `footer.openingHours` ← `BriefContext.openHours` — **already a formatted string**, so the array-type error (surface A) cannot recur by construction
- `header.showAuth` / `showCart` ← the existing `Flags` (auth context / cart present)
- everything else (`email`, `socialLinks`, `tagline`, design tokens `bgClass`/…) is **optional** in the contract → omit and the shell's built-in defaults apply
- `latitude` / `longitude` are in `BriefContext` too — confirming the map data belongs to the **page**, not siteConfig (exactly the agent's manual conclusion)

### Solution (planned) — Option A: scaffold `siteConfig.ts` mechanically, mirror the routes.ts move

Take `siteConfig.ts` off the LLM's plate the same way `routes.ts` / `App.tsx` / `AppRoutes.tsx` already were. Legs, mechanical-first:

**1. New `SiteConfigGenerator` (primary, mechanical).** Emit `frontend/src/config/siteConfig.ts` as `export const siteConfig: SiteConfig = { header, footer }` (named export + `import type { SiteConfig } from '@/shell'`, matching the foundation import style) from `BriefContext` + `RouteManifest`, wired into `FrontendGeneratorNode` right beside the existing `emitRoutesTs` call (`nodes/FrontendGeneratorNode.java:508-534` — both the manifest and `ctx.getBriefCtx()` are already in scope there). Carries a PLAN_MARKER so the ErrorFixAgent won't hand-edit it. Fields as mapped above; omit optional aesthetic fields in v1 (foundation defaults cover them). This makes the shape and `openingHours` type correct **by construction** — surface A disappears.

**2. navLinks single-source-of-truth (folded into leg 1).** `header.navLinks` is derived from the `RouteManifest` (`label` ← `Entry.label`, `href` ← `Entry.path`), so the header nav, the config, and the route table can never drift — the same principle as #2, and consistent with the nav-filter hint the prompts already document.

**3. Remove it from the LLM plate (prompt/plan).** Strip `config/siteConfig.ts` from the file manifest (`ProjectPlanningNode` "do not plan" list, alongside `App.tsx`/`routes.ts`) and flip the guidance in the four prompts + the foundation note from *"You must generate siteConfig.ts"* → *"siteConfig.ts is DERIVED from the plan + brand/contact data — do NOT generate it."* Exactly the move already made for `routes.ts` in `arch_outline.txt`.

**4. Surface B (secondary, prompt + context).** State that page-specific data is **not** on `siteConfig`: map coordinates come from the brief's `latitude`/`longitude` injected into the page as local constants, and `openingHours` is a **string** (never `.map()` it). Optionally inject `lat`/`lng` into the `ContactPage` generation context so it uses real values instead of inventing `siteConfig.footer.mapCoordinates`. `SiteConfigAccessPatcher` stays as the belt-and-suspenders for any residual flat access.

**Why mechanical, not "just fix the prompt/contract" (the tempting Option B).** The contract was already spelled out in four places and still ignored — the same failure mode as #2's admin wrapper. `siteConfig` is derivable config, not creative content; deriving it guarantees the shape and types are right every run, removes an error cluster from the LLM entirely, and makes the header nav provably consistent with the route table. The optional aesthetic bits that *are* creative (colors, tagline, social) are exactly the ones the contract marks optional, so omitting them costs nothing.

### Confirmed

- **`SiteConfig` is foundation-fenced and mostly-optional** (`shell/types.ts`: only `brandName` + `navLinks` required; `openingHours?: string | null`). A mechanical scaffold that fills the required + brief-derived fields and omits aesthetic optionals is fully valid and type-checks.
- **All derivation inputs exist at the wiring point.** `BriefContext` (`service/llm/BriefContext.java`) provides `businessName`, `address`, `phone`, `openHours` (string), `latitude`, `longitude`; the `RouteManifest` provides nav. `FrontendGeneratorNode:508-534` already emits `routes.ts` there with both in scope — so `siteConfig.ts` slots in with no new plumbing.
- **Open question (design choice):** design tokens (`bgClass`/`textClass`/`accentClass`…) — omit in v1 (foundation dark-theme defaults) or add a small `colorScheme` → Tailwind-class mapping from `BriefContext.colorScheme`/`designDirection`. Recommend omit for v1; add the mapping as a follow-up if the default theme reads as generic.

---

## 4. `InquiryType` type-as-value (enum used as runtime value)

**Issue.** Backend enum surfaces on the frontend as a TypeScript **string-union type alias** (`type InquiryType = 'A' | 'B'`), then pages use it as a **value** — `z.nativeEnum(InquiryType)`, `Object.values(InquiryType)` — which has no runtime object. Recurs (same class as MenuCategory-as-enum in prior runs).

**Already-available solution.** **None specific.** No enum/type-as-value patcher exists (grep for `nativeEnum` / `z.enum` finds only `RouteManifestGenerator`, unrelated). The TS type generator emits the union as a type only.

**Gap.** Full gap. Options space (for later): emit backend enums as a runtime `as const` object + derived type so both value and type positions resolve; or a prompt rule pairing `z.enum([...])` with a plain const array.

**Real root cause (verified).** `TsTypeGenerator.java:134-138` emits every backend enum as a **type-only union** (`export type InquiryType = 'FREE_TRIAL' | 'TOUR_BOOKING' | 'GENERAL_INQUIRY'`). A type has no runtime existence, so the moment a consumer needs the *values* — dropdown options, a zod schema, `Object.values` — there is nothing to read. In 9312afa6 the agent worked around it by **hand-duplicating the literals three times** in `LeadCaptureForm.tsx`: `const INQUIRY_TYPES: InquiryType[] = ['FREE_TRIAL','TOUR_BOOKING','GENERAL_INQUIRY']`, `z.enum(['FREE_TRIAL','TOUR_BOOKING','GENERAL_INQUIRY'])`, and `'GENERAL_INQUIRY' as InquiryType` — three copies, each free to drift from the backend enum.

### Target format (requested)

Emit each backend enum as a runtime **const object + a same-named derived type**:
```ts
export const InquiryType = {
  GENERAL: 'GENERAL',
  MEMBERSHIP: 'MEMBERSHIP',
  TRAINING: 'TRAINING',
  COMPLAINT: 'COMPLAINT',
} as const;

export type InquiryType = typeof InquiryType[keyof typeof InquiryType];
```
`InquiryType` is now BOTH a value and a type under one name (legal TS — the value and type namespaces merge). Values are the enum **constant names**, matching the union the generator already emitted and Jackson's default name serialization — so the wire contract is unchanged. Every position now resolves: `InquiryType.GENERAL` (value), `Object.values(InquiryType)` (values), `z.nativeEnum(InquiryType)` (zod), `x: InquiryType` (type).

### Solution (planned) — mechanical emission + value-import correctness

**1. `TsTypeGenerator` enum emission (primary, mechanical).** Replace the union at `:134-138` with the const-object + derived-type block above. Single source of truth — the literal triplication disappears.

**2. Import correctness (the one real gotcha).** A const object is a VALUE, so a consumer that uses it as a value must **value-import** it (`import { InquiryType }`), not `import type` — under `isolatedModules`/`verbatimModuleSyntax` a type-only import used as a value is a hard error. Two parts:
- The generated **type files** (cross-imports at `:128`) and the **SDK** (`TsSdkGenerator`'s `import type` at ~`:89`) reference enums only in TYPE positions → `import type` stays correct there; no change needed.
- **LLM consumers are the risk** (the real form did `import type { InquiryType }` and then read its values). Handle both ways:
  - **Prompt** (`file_generate_frontend.txt`, the ZOD/types rules ~`:91`): enums from `@/types` are const objects — import them as VALUES (`import { X }`), consume via `z.nativeEnum(X)` / `Object.values(X)` / `X.MEMBER`; NEVER re-list the literal strings or `import type` an enum you read values from.
  - **Mechanical net (belt-and-suspenders): new `EnumValueImportPatcher`** in `FrontendValidationNode`'s deterministic pass. Given the set of const-object enum names (from the generated type files / `TypeScriptExportRegistry`), for any frontend file that uses an enum in a value position (`X.`, `Object.values(X)`, `z.nativeEnum(X)`, `X[`) but imported it via `import type { X }`, upgrade to `import { X }`. Same family as `TanStackImportFixer` / `SiteConfigAccessPatcher`; makes it correct even when the prompt is ignored (the recurring lesson from #2/#3).

**3. Update the (currently-red) enum test.** `TsGeneratorsTest.emitsInterfacesAtPlannedPathsWithNullabilityAndEnums` (`:100`) asserts the old `export type BookingStatus = 'CONFIRMED' | 'CANCELLED_BY_USER'` — rewrite it to assert the const-object + derived type. (It is on the pre-existing-failure list; this aligns it to the new emission.)

**Why mechanical, not prompt-only.** The value/type duality is a TypeScript representation problem with exactly one correct encoding — deriving it in the generator gives every consumer one runtime source of truth and ends the hand-maintained literal arrays. A prompt asking the model to "keep a const array in sync with the type" just re-introduces the drift it's meant to remove.

### Confirmed

- **Wire contract unchanged:** const values are the enum constant NAMES — identical to the union already emitted and to Jackson's default serialization; backend and frontend stay in agreement.
- **Legal TS:** `export const InquiryType` + `export type InquiryType` coexist (value + type namespaces); safe under `isolatedModules`/`verbatimModuleSyntax` since the const is a real runtime export.
- **zod (source-verified against the installed `node_modules/zod@3.25.76`):** the default `import { z } from 'zod'` resolves to **v3 classic** (`src/index.ts` → `./v3/external.js`). There, `z.enum` (`createZodEnum`) accepts **only a string tuple `[U, ...U[]]`** — a const object does NOT typecheck; `z.nativeEnum` (`EnumLike`) is the form that accepts the const object. In the bundled v4, `z.nativeEnum` still exists (`v4/classic/schemas.ts:1510`, **deprecated, not removed**) and `z.enum` gains a const-object overload. So:
  - **Use `z.nativeEnum(InquiryType)`** — valid on the current v3 pin, and *deprecated-but-functional* if the pin ever moves to `^4` (a lint/JSDoc warning, build still passes → "merely dated", never broken).
  - Do **NOT** steer to `z.enum(InquiryType)` now — that const-object form is **v4-only** and would fail to compile on the installed v3.
  - *Fully version-proof option (no deprecation, both majors):* also emit a companion `export const InquiryTypeValues = ['GENERAL', ...] as const;` and use `z.enum(InquiryTypeValues)` (valid via v3's `Readonly<[U,...U[]]>` overload and v4's `readonly string[]` overload); the tuple doubles as a dropdown array. Cost: one extra generated export per enum.
- **SDK unaffected:** `TsSdkGenerator` uses enums only in signatures, so its `import type` remains valid.

---

## 5. Handler signature drift — object vs id (4 admin pages)

**Issue.** Page `handleDelete*` handlers take the full object; the child table/form components' contracts expect `(id: number)`.

**Already-available solution.** `PlannedComponentPropsCard` + `FrontendPlannedContractCard` establish a single up-front prop contract from the enriched spec's `public_functions` so a parent and child bind to the SAME definition instead of each inventing one during the parallel COMPONENT layer (`util/PlannedComponentPropsCard.java` — "sibling-prop drift, Cluster 2").

**Gap.** The planned card pins **prop shapes**, but callback **parameter types** (id vs object) still drift between the page and the child it renders. Coverage is partial.

---

## 6. lucide-react hallucinated icon (`SwimmingPool` → `Waves`)

**Issue.** The generator imported a lucide export that doesn't exist.

**Already-available solution.** `NodeModuleExportRegistry` (scans real `node_modules` exports scoped to `package.json`) + `UiImportRewriter` (`FrontendValidationNode.java:84`) — the generic fix for *missing* imports: it adds an import for a symbol used but never imported (e.g. a lucide icon dropped into JSX).

**Gap.** The registry/rewriter only **adds a missing import** or moves radix→shadcn. It does **not validate that an already-imported named symbol actually exists** in the package, nor replace a non-existent one — so a hallucinated `SwimmingPool` import falls through to tsc + the agent.

---

## 7. react-day-picker v10 `initialFocus` removed (2 occ, `BookingFilter`)

**Issue.** `initialFocus` was dropped from the Calendar/day-picker API in v10; page code still passes it.

**Already-available solution.** Fixed at the **foundation Calendar component** level (webapp-foundation — `initialFocus`→`autoFocus`), noted at `FrontendValidationNode.java:94`.

**Gap.** The foundation fix covers the foundation `Calendar` wrapper. Page code that uses the day-picker/Calendar **directly with `initialFocus`** (as `BookingFilter` did) is outside that fix's reach — no page-level patcher for the removed prop.

---

## 8. Implicit `any` on catch / callback params

**Issue.** Under strict TS, `err`/`error`/`booking` callback params were implicitly `any`.

**Already-available solution.** Largely a **cascade of #1** — the offending params lived in the `mutate(vars, { onSuccess(err){…} })` callbacks. Removing those callbacks (the #1 fix) eliminates most of these.

**Gap.** No dedicated pass; secondary. Fixing #1 dissolves most instances; residual strict-mode `any` is left to the agent.

---

## Cross-cutting observation

Four of these (#2, #3, #4, #7) are **known classes with prior fixes** (FoundationContractCard, SiteConfigAccessPatcher, MenuCategory-as-enum note, day-picker foundation fix) that **recurred** here — the pattern is: a *prompt/contract* solution gets ignored, and the *mechanical* backstop covers only a narrow slice of the shape. The durable direction (for a follow-up plan) is to convert prompt-only contracts into mechanical patchers, and to widen the existing patchers (#3 shape reconcile, #6 invalid-export replace, #5 callback-param pinning). #1 is the cleanest single win: it is generator-owned and fixable at source.

## Reference docs

- `docs/frontend-error-patterns-abs-fitness.md` (Theme C/D leaks, F4)
- `docs/frontend-hook-generation-and-prompt-segregation.md` (sibling-prop drift, hook layer)
