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

## 4. `InquiryType` type-as-value (enum used as runtime value) — ✅ IMPLEMENTED 2026-09-01 (version-proof `…Values` tuple)

> **Status.** Legs 1–3 implemented + unit-verified.
> - **Leg 1 (mechanical):** `TsTypeGenerator.emitEnum` now emits each backend enum as `export const X = {…} as const` + `export type X = typeof X[keyof typeof X]` + `export const XValues = [...] as const` (the version-proof zod tuple). Test: `TsGeneratorsTest.emitsEnumAsConstObjectDerivedTypeAndValuesTuple` green.
> - **Leg 2 (import correctness):** prompt rule `3c` in `file_generate_frontend.txt` (enums are const objects — value-import them; use `z.enum(XValues)` / `XValues.map` / `X.MEMBER`; never re-list literals) + new **`EnumValueImportPatcher`** wired at `FrontendValidationNode` step 5g — upgrades `import type { X }` → `import { X }` when X (or `XValues`) is used as a value, splitting mixed imports and leaving type-only usages/generated files alone. Test: `EnumValueImportPatcherTest` (5) green.
> - **Leg 3:** updated `TsGeneratorsTest`'s enum assertion to the new format (added a direct `emitEnum` test since the enclosing test has a pre-existing, unrelated nullability failure at `:107`).
> - **zod form:** `z.enum(XValues)` — verified valid + non-deprecated in the installed zod v3.25 AND v4 (see Confirmed).


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

**Real root cause (verified).** The contract is fully specified — and it is the *contract itself* that trips the model. `ContractReconciler` (a Pro call) mixed conventions **within one component**; `docs/ARCHITECTURE.json` for `ClassTable` reconciles to:
```
onEdit:   (fitnessClass: FitnessClassDto) => void   // the full OBJECT
onDelete: (classId: number) => void                // the ID
```
The child `ClassTable` implements this exactly. The parent page is *shown* the same contract (via `PlannedComponentPropsCard`), yet writes both handlers **symmetrically** — `handleEditClass(item)` and `handleDeleteClass(item)` — because edit/delete "should" look alike. Passing `handleDeleteClass` to `onDelete: (id: number)` is TS2322, across all 4 admin pages. The agent's fix flipped the page to `handleDeleteClass(classId: number)` + an internal `classes.find(c => c.id === classId)`. So: the contract is correct and visible, but its **intra-component object-vs-id asymmetry** fights the model's symmetry instinct — a "shown-but-ignored" failure the planned card can't prevent because it faithfully relays the asymmetry.

### Solution (planned) — normalize row-action callbacks to ONE shape (mechanical + prompt)

Kill the asymmetry rather than hope the model honors it: make every row-action callback on a list/table component take the **full row object** — the shape the model naturally writes and the table trivially has in hand.

**1. New `RowActionContractNormalizer` (primary, mechanical).** A deterministic pass after `ContractReconciler`, over each component's reconciled `public_functions` + `file_role` RECONCILED-CONTRACT string. For a list/table component (has an `items: XDto[]` prop), rewrite the single parameter of each **row-action** callback to the row DTO — `on<Verb>: (item: XDto) => void` — so `onDelete: (classId: number)` → `onDelete: (fitnessClass: FitnessClassDto)`. Written back into the spec so BOTH the child and the parent page bind to the symmetric contract. Same family as the other reconciliation passes; zero LLM; idempotent (already-`(item: XDto)` is a byte-identical no-op — double-run test).

**Which callbacks it may touch — narrow, two gates (skip when unsure).** The `items: XDto[]` prop is the anchor, but "any single-param `on<Verb>`" is too broad: tables also carry `onSort: (column: string)`, `onPageChange: (page: number)`, `onSearch: (query: string)`, `onSelectAll: (checked: boolean)`, and rewriting those to the DTO is nonsense — worse than a no-op, it corrupts a working contract into a type error that points at the *component*, not the normalizer. So a callback is rewritten only when BOTH gates pass:
- **Gate 1 — name.** A **deny-list is checked first** (`onSort`, `onPageChange`, `onPage`, `onSearch`, `onFilter`, `onColumnSort`, `onSelectAll`, …); then the verb must **exactly** match a row-action allowlist (`onEdit`, `onDelete`, `onView`, `onSelect`, `onRowClick`, `onArchive`, `onApprove`, `onReject`, plus the one intentional prefix `onToggle*`). Exact match matters because `onSelectAll` starts with the allowlisted `onSelect` — a prefix match would re-admit the nastiest case; the deny-list + exact-match closes it.
- **Gate 2 — parameter evidence.** Inspect the single param: already the row DTO → **no-op**; an id-like primitive (name `id` or `*Id`, type `number`/`string`) → **rewrite** to `(item: XDto)`; anything else → **skip**. This is the second line of defense — `onSelectAll: (checked: boolean)` fails here too (boolean, name not id-like).

Multi-parameter callbacks (`onQuantityChange: (id: number, qty: number)`) are out of scope by the "single parameter" condition and are **left untouched** — not first-param-rewritten. (A consistent ideal would be `(item: XDto, qty: number)`, but that's deferred to v2.) A new row-action verb is handled by adding it to the allowlist, not by loosening the predicate.

**2. Prompt rule (`feature_enrichment.txt` + `file_generate_frontend.txt`).** "Row-action callbacks on a table/list (`onEdit`/`onDelete`/`onView`/…) ALL take the full row object — `(item: XDto) => void`, never a bare id. The list already has the object; the handler reads `item.id` as needed. Keep every row-action callback's signature identical." **This leg targets the *source*, not a downstream consumer.** The asymmetry originates in `ContractReconciler` (a Pro call); steering it there is the one case among the five issues where the prompt fixes the actual origin — so if it lands, the normalizer rarely fires and the whole class mostly disappears at the reconciliation step. It carries more weight here than the prompt legs in #2/#3, which only steer a consumer of an already-correct contract.

**3. Result.** Child: `onClick={() => onDelete(fitnessClass)}` (trivial, it has the object). Page: symmetric `handleEdit(item)` / `handleDelete(item) => deleteClass(item.id)` — no `.find()` lookup, no asymmetry to drift from.

**Both legs stay** even though leg 2 hits the source: `ContractReconciler` is a Pro call and will occasionally emit a mixed convention regardless of the prompt, so the mechanical normalizer is the guarantee, not the optimization.

**Why mechanical, not prompt-only.** The contract is LLM-reconciled and inconsistently conventioned; a deterministic normalizer guarantees symmetric row-action signatures so parent and child cannot disagree — the same single-source-of-truth principle as #2–#4. A prompt alone leaves the asymmetry in the reconciled contract for the model to re-misread.

### Confirmed

- **The object form is a superset, so "recommended" is not a coin-flip.** A handler given the object can read `item.id`; a handler given the id must *search* for the object — the agent's manual fix (`handleDeleteClass(classId)` + `classes.find(c => c.id === classId)`) is the id form paying its cost in the caller. Choosing object eliminates the lookup rather than relocating it.
- **The page always has the row objects** (it renders the table from `classes`/`trainers`/… arrays), so passing the object costs nothing.
- **The DTO is derivable** from the component's `items: XDto[]` prop, so the normalizer never has to guess the target type.
- **Both parent and child already read the reconciled spec**, so normalizing it once fixes both sides — no separate page-side and component-side edits.
- **Bias = skip, not rewrite (inverted from #4).** In #4's import patcher, over-firing cost a redundant import; here, over-rewriting silently turns a correct contract into a nonsensical one whose type error blames the component. So both gates must pass, and anything uncertain is left to the prompt + ErrorFixAgent.

### Open choice

Convention direction: **full row object for every row-action callback** (recommended — matches the model's instinct and needs no lookup) vs. **bare id for every callback** (more RESTful, but forces an object lookup for edit-style actions that need to populate a form). Recommend the object form for v1.

---

## 6. lucide-react hallucinated icon (`SwimmingPool` → `Waves`) — ✅ IMPLEMENTED 2026-09-01

> **Status.** Legs 1–3 implemented + unit-verified (`LucideIconValidatorTest`, 8/8).
> - **Registry accessor:** new `NodeModuleExportRegistry.exportsOfPackage(frontendDir, "lucide-react")` returns the RAW per-package export set (does NOT collapse ambiguous symbols, so `Route` — exported by both lucide-react and react-router-dom — is retained), reusing the existing private `exportsOf`.
> - **Leg 2 (mechanical) — new `LucideIconValidator`** wired at `FrontendValidationNode` step 5h (after `EnumValueImportPatcher`, before `TypeScriptImportFixer`). Validates every `import { … } from 'lucide-react'` against the real export set. **Tier A** (certain typo — case-insensitive / `Icon` add-strip / singular-plural normalizing to exactly ONE real export) rewrites the import spec AND every un-aliased JSX/value usage; `fix()` returns true only for Tier A (a build-fixing change → triggers the rebuild). **Tier B** (no certain normalization, e.g. `SwimmingPool`) inserts a `// FIXME[invalid-icon]` comment above the import naming the bad symbol + registry-verified candidates (lexical edit-distance ≤3, a tiny concept→lucide hint table tokenizing the invented name, generic fallback — all validated against the real set so a suggestion is never itself fake), leaving the import untouched so the build stays red and the agent runs. Never guesses a semantic replacement itself; idempotent (won't duplicate an existing FIXME); skips `// GENERATED` and `components/ui/` files.
> - **Leg 1 (prompt):** `file_generate_frontend.txt` rule 11 — lucide icons must be real, no `SwimmingPool`/`Gym`/`Treadmill`/`Yoga` etc., with an always-valid fallback set and "never guess a PascalCase name from the business domain."
> - **Leg 3 (agent prompt):** `fix_file.txt` rule 8 — honor a `// FIXME[invalid-icon]` comment by replacing the named symbol (import + all usages) with ONE of the listed real icons, never inventing another, and removing the comment when fixed.


**Issue.** The generator imported a lucide export that doesn't exist.

**Already-available solution.** `NodeModuleExportRegistry` (scans real `node_modules` exports scoped to `package.json`) + `UiImportRewriter` (`FrontendValidationNode.java:84`) — the generic fix for *missing* imports: it adds an import for a symbol used but never imported (e.g. a lucide icon dropped into JSX).

**Gap.** The registry/rewriter only **adds a missing import** or moves radix→shadcn. It does **not validate that an already-imported named symbol actually exists** in the package, nor replace a non-existent one — so a hallucinated `SwimmingPool` import falls through to tsc + the agent.

**Real root cause (verified).** The model invented `SwimmingPool`; lucide-react has no such export (it ships `Waves` for water). This is a **semantic** hallucination, not a typo — there is no lexical path from `SwimmingPool` to `Waves`, so only a model (the ErrorFixAgent, which fixed it) can bridge it. That distinction is the whole difficulty of #6 and separates it from the other four: the mechanical layer can *detect* the bad name but cannot *derive* the right one.

**Two constraints found while scoping:**
- **A mechanical "correct" replacement is not generally possible.** A generic fallback keeps the build green but a `Circle` on the "Swimming Pool" facility card is a client-visible regression the agent would have fixed better. Near-match (typo/casing/`Icon`-suffix) is a *different* class and rarely fires here anyway — lucide v1.28 already exports the `*Icon` aliases (`PencilIcon`, `CalendarIcon`, `Trash2Icon` are all valid in the repo). So the mechanical lever is genuinely weaker here than in #1–#5.
- **The existing registry can't be used as-is to validate.** `NodeModuleExportRegistry.packageFor()` **drops ambiguous symbols** (exported by ≥2 scoped packages). `Route` is exported by BOTH `lucide-react` and `react-router-dom` — and the repo really does `import { Route } from 'lucide-react'` — so a validator built on that map would falsely flag a *valid* import. The validator needs a **raw per-package export set** (a new accessor over the existing `exportsOf(...)`), not the ambiguity-collapsed map.

### Solution (planned) — prevention-first, mechanical net that only acts when it can be right

**1. Prevention (prompt — the primary lever here).** In `file_generate_frontend.txt`: use ONLY real lucide-react icons; there is no `SwimmingPool`/`Gym`/`Treadmill`/`Yoga` icon — NEVER invent a domain icon. Give a small curated always-valid set for common concepts (`Dumbbell`, `Waves`, `Users`, `Star`, `Calendar`, `Clock`, `MapPin`, `Phone`, `Mail`, `Award`, `Heart`, …) and "if unsure, pick a generic one from this list." This attacks the source; unlike #2/#3 the prompt is the main fix because the residue can't be mechanically corrected well.

**2. Mechanical net — new `LucideIconValidator`** in `FrontendValidationNode`'s deterministic pass, over the **raw** lucide-react export set (new `NodeModuleExportRegistry.exportsOfPackage("lucide-react")`). For each `import { … } from 'lucide-react'`, find names not in the real set and split the work along the seam between *deterministic* and *semantic* — **fix what it can be certain of, annotate the rest for the agent**:
- **Tier A — high-confidence normalization (auto-fix).** A name that resolves to exactly ONE real export under case-insensitive / `Icon` add-strip / singular-plural normalization → rewrite the import AND all JSX/value usages. Deterministic, always correct; saves the round entirely for trivial typos.
- **Tier B — invalid, no certain normalization → ANNOTATE + DEFER (does not touch the build).** The layer cannot know that `SwimmingPool` should be `Waves` — that is a semantic judgment. So instead of guessing (and risking a wrong-but-green `Circle`), it inserts a structured comment directly above the offending import naming the invalid symbol and **registry-verified** candidate replacements, then leaves the pick to the ErrorFixAgent. The build stays red on the invalid import, so the agent runs anyway — but now it is handed the diagnosis and real options rather than having to discover the bad name from tsc and re-enumerate lucide's exports itself (which it can't reliably do → it could pick another fake). Example:
  ```tsx
  // FIXME[invalid-icon]: 'SwimmingPool' is not exported by lucide-react. Replace it (import + all
  //   usages) with one of these real icons: Waves, Droplets, Droplet, Activity.
  import { SwimmingPool } from 'lucide-react';
  ```
  **Candidate generation (all values validated against the real export set, so a suggestion is never itself fake):** (i) lexical near-matches by edit distance; (ii) a small **concept→lucide** hint table that tokenizes the invented name and maps common domain concepts to real icons (`swim`/`pool`/`water` → `Waves`, `Droplets`; `gym`/`fitness`/`workout` → `Dumbbell`, `Activity`; `yoga` → `PersonStanding`; `treadmill`/`cardio` → `Activity`, `Footprints`; …); (iii) a generic real fallback set (`Star`, `Sparkles`, `Circle`) so the comment always offers *something* valid. The table is curated but tiny and auditable, and — crucially — it only *suggests*; the agent makes the call with usage context, so an imperfect suggestion is corrected rather than shipped.

This resolves the reliability-vs-cosmetics trade the rest of the plan avoided: **no wrong icon is ever auto-shipped** (Tier B never rewrites), yet the agent's fix is grounded, cheap, and correct-on-first-try because the deterministic registry work is done for it.

**Small ErrorFixAgent prompt note.** Add one line to the agent's guidance: "if a `// FIXME[invalid-icon]` comment is present, replace the named symbol (import + all usages) with one of the listed real icons — do not invent a new one." Ensures the pre-computed candidates are honored (the agent already did `SwimmingPool→Waves` unaided, so this only makes it faster and safer).

---

## 7. react-day-picker v10 `initialFocus` removed (2 occ, `BookingFilter`) — ✅ IMPLEMENTED 2026-09-01

> **Status.** Both legs implemented + unit-verified (`DayPickerPropPatcherTest`, 7/7); rewrite proven with `tsc`.
> - **Verified against the real repo + react-day-picker@10.0.1:** the project pins `^10.0.1`; `initialFocus` is gone from the v10 `.d.ts`; `autoFocus?: boolean` is its documented replacement. Reproduced `<Calendar initialFocus/>` → **TS2322** "Property 'initialFocus' does not exist … DayPickerProps"; rewrite to `<Calendar autoFocus/>` → `tsc` clean. (The wrapper types props as `React.ComponentProps<typeof DayPicker>`, so page-level `initialFocus` is rejected even though the foundation wrapper itself is fine.)
> - **Leg 1 (mechanical) — new `DayPickerPropPatcher`** wired at `FrontendValidationNode` step 5i. Rewrites the `initialFocus` prop → `autoFocus` **only inside a `<Calendar …>` / `<DayPicker …>` opening tag** (negative lookahead excludes `<CalendarIcon>`/`<CalendarDays>`; a same-named variable elsewhere is left alone), with a brace/quote-aware tag-end scan so a `>` inside `onSelect={(d) => …}` isn't mistaken for the tag close. Handles bare `initialFocus` and `initialFocus={true}`; idempotent; skips `// GENERATED` and `components/ui/` files. Preserves the focus-on-open UX the agent otherwise dropped by deleting the prop.
> - **Leg 2 (prompt)** — `file_generate_frontend.txt` rule 13: `initialFocus` was removed in react-day-picker v10 (`<Calendar initialFocus/>` is TS2322); use `autoFocus`; never pass `initialFocus` to `<Calendar>`/`<DayPicker>`.

**Issue.** `initialFocus` was dropped from the Calendar/day-picker API in v10; page code still passes it.

**Already-available solution.** Fixed at the **foundation Calendar component** level (webapp-foundation — `initialFocus`→`autoFocus`), noted at `FrontendValidationNode.java:94`.

**Gap.** The foundation fix covers the foundation `Calendar` wrapper. Page code that uses the day-picker/Calendar **directly with `initialFocus`** (as `BookingFilter` did) is outside that fix's reach — no page-level patcher for the removed prop.

---

## 8. Implicit `any` on catch / callback params — ✅ CLOSED 2026-09-01 (dissolved by #1 + prompt leg)

> **Status.** Verified against the real `9312afa6` run + repo; no mechanical pass warranted.
> - **The dominant cluster is dissolved by the committed #1 fix.** The run log shows **zero TS7006** — the implicit-any params lived inside `mutate(vars, { onSuccess, onError })`, and the **TS2554** "expected 1 arg, got 2" (13 occ / 7 files) rejected the whole call first, masking them. With #1's `MutateOptions<TData, Error, TVars>` widening now in `FrontendHookGenerator`, those callbacks are contextually typed — no implicit any and no callback deletion needed.
> - **Residual (final repo): exactly 2 explicit `: any`**, both at genuinely-untyped boundaries — `catch (error: any)` (`SignupForm.tsx:54`) and a Razorpay `handler: (response: any)` (`CheckoutForm.tsx:58`). `tsconfig.app.json` has `strict:true` (noImplicitAny on) but `noUnusedParameters:false`.
> - **No patcher** — a catch var is already `unknown` under strict (forcing a narrowing rewrite risks changing behavior of code reading `.message`), and an external SDK callback can't be typed mechanically without the lib's types.
> - **Prompt leg added** (`file_generate_frontend.txt` rule 12): never `catch (e: any)` → `catch (error)` + `error instanceof Error ? error.message : '…'`; an untyped third-party SDK callback param → a small local `interface` when you read known fields (else `: unknown` + narrow), never implicit/`: any`; contextually-typed params (mutate `onError`/`onSuccess`, `.map`, JSX handlers) get no annotation.
> - **Verified with `tsc --noEmit` under the repo's real `strict:true` config** (2026-09-01): baseline clean; rule 12's `catch` fix compiles; bare `: unknown` + property access on the Razorpay `response` FAILS `TS18046` ×3 (so "narrow before access" is load-bearing); a local `RazorpayResponse` interface compiles — hence rule 12 prefers a local interface for field-reading SDK callbacks.

**Issue.** Under strict TS, `err`/`error`/`booking` callback params were implicitly `any`.

**Already-available solution.** Largely a **cascade of #1** — the offending params lived in the `mutate(vars, { onSuccess(err){…} })` callbacks. Removing those callbacks (the #1 fix) eliminates most of these.

**Gap.** No dedicated pass; secondary. Fixing #1 dissolves most instances; residual strict-mode `any` is left to the agent.

---

## Cross-cutting observation

Four of these (#2, #3, #4, #7) are **known classes with prior fixes** (FoundationContractCard, SiteConfigAccessPatcher, MenuCategory-as-enum note, day-picker foundation fix) that **recurred** here — the pattern is: a *prompt/contract* solution gets ignored, and the *mechanical* backstop covers only a narrow slice of the shape. The durable direction (for a follow-up plan) is to convert prompt-only contracts into mechanical patchers, and to widen the existing patchers (#3 shape reconcile, #6 invalid-export replace, #5 callback-param pinning). #1 is the cleanest single win: it is generator-owned and fixable at source.

## Reference docs

- `docs/frontend-error-patterns-abs-fitness.md` (Theme C/D leaks, F4)
- `docs/frontend-hook-generation-and-prompt-segregation.md` (sibling-prop drift, hook layer)
