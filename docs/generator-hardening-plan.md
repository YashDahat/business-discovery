# Generator Hardening Plan — User Identity, SEO Landing, Endpoint Security

**Status:** Design only — **do not implement yet.** Gated on confirming that generation itself is correct first.
**Date:** 2026-08-08
**Origin:** First full end-to-end run of a generated app (`farmaaish-restaurant`), driven live + via Playwright. Three defect classes surfaced. This doc plans the fixes at the **generator/pipeline** level (not in any generated repo).
**Related:** [[session_aug_2026_major_changes]] (FOUNDATION_CONTRACT cards), `docs/pipeline-changes-report.md`, `docs/world_class_gaps` (SEO/JSON-LD gap), prior runtime-wiring defect scans.

---

## Evidence (from the farmaaish e2e scan)

All write flows persisted correctly (reservation/inquiry/order/menu CRUD → 201), but three defects:

1. **Orders attributed to a hardcoded fake customer.** `OrderController` hardcodes
   `customerId = UUID.fromString("a0eebc99-…")` in both `createOrder` and `getMyOrders`.
   Every order → same fake customer; "my orders" returns everyone's; no ownership checks.
   *Root cause:* generated domain keys everything by **UUID**, foundation `User.id` is **Integer** →
   the LLM couldn't bridge the two and stubbed a placeholder (its own comment names the correct
   `userRepository.findByEmail(...).getId()` it declined to write). There is also no signup flow —
   only a single seeded admin exists, so "customers" were never real users.

2. **Two admin endpoints fully public** (confirmed unauthenticated, live): `POST /api/admin/gallery`
   created a row; `GET /api/admin/inquiries` returned customer PII. `GET /api/v1/admin/orders`
   (kept the `v1` prefix) correctly returned **403**.

3. **API path-prefix drift** — controllers mix `/api/v1/...`, `/api/...`, `/api/public/...`.
   This drift is *what* pushed controllers outside the security matcher (cause of #2).

**Pivotal facts for the fixes:**
- `ARCHITECTURE.json` **already carries a per-endpoint access tier** on every endpoint
  (`"access": public|authenticated|admin` — 7/3/18 in this run), and the **planned** paths were all
  correctly `/api/v1/...`. So the plan was right; **generation drifted**, and the LLM-freehand
  `SecurityConfig` hardcoded matchers that tracked the *plan*, not the *actual* controllers.
- Foundation auth spine (fenced): `User{Integer id, firstName, lastName, email, password, Role}`,
  `Role{ADMIN,USER}`, `UserRepository.findByEmail`, `UserService.loadUserByUsername`, `JwtUtil`,
  `SecurityConfig`, `AuthController POST /api/v1/auth/login`, `AdminInitializer`. **No phone field,
  no signup endpoint** today.

---

## Workstream 1 — Real user identity + self-signup (fixes defect #1)

**Goal:** every customer is a real user, signed up with **mobile number + email**, and every
owned record (orders, and any future "my reservations") is attributed to the authenticated user —
never a placeholder.

### Generator fixes
1. **Extend the fenced foundation auth spine** (deterministic scaffold, not LLM):
   - Add `phone` to `User` (or a `Customer`/profile — see open decision D1).
   - Add **registration**: `POST /api/v1/auth/register { firstName, lastName?, email, phone, password }`
     → creates a `USER`-role account, returns a JWT (same shape as login).
   - Support login by **email or phone** (single identifier field; `AuthController` resolves either).
   - Provide a canonical **current-user resolver** the generated code MUST call instead of
     hardcoding: e.g. `CurrentUserService.currentUser()` / `currentUserId()` (reads
     `SecurityContext` → `UserRepository.findByEmail(principal)`), returning the real `User.id`.
2. **Surface all of the above in `FOUNDATION_CONTRACT_BACKEND.md`** (and add a hard prompt rule):
   > Never hardcode a user/customer id. Resolve the caller via the foundation current-user API and
   > use `User.id`'s type for any owner/customer foreign key.
3. **Owner-FK type alignment.** Generated owner/customer columns must use `User.id`'s type. Either
   (a) keep foundation `User.id` = Integer and forbid UUID owner refs, or (b) migrate the auth spine
   to UUID ids so the whole system is UUID-consistent (see D2). Add a validation-node check that flags
   any `customerId`/`ownerId`/`userId` field whose type ≠ `User.id` type.
4. **Frontend:** foundation `AuthContext` gains `register(...)`; generator builds a **Signup page**
   and wires it into routes + header ("Login / Sign up"). Add to `FOUNDATION_CONTRACT_FRONTEND.md`.

### Components to touch
`webapp-foundation` auth spine (User, AuthController, UserRepository, new `CurrentUserService`,
AuthContext, useAuth) · `FOUNDATION_CONTRACT_{BACKEND,FRONTEND}.md` · `file_generate_backend.txt`
(no-hardcoded-id rule) · a backend validation check for owner-FK type · `RouteManifestGenerator`
(signup route).

### Open decisions
- **D1:** phone on `User` directly, or a separate `Customer` profile entity linked to `User`?
- **D2:** id strategy — align generated domain to Integer `User.id`, or move the auth spine to UUID
  so everything is UUID? (UUID-everywhere is cleaner long-term; Integer is less churn.)
- **D3:** login identifier — email only, phone only, or either? OTP later?

---

## Workstream 2 — Generic SEO landing page

**Goal:** every generated site ships a search-optimized landing page so the business can rank,
driven by the `ArchitectBrief` / `siteConfig` business data.

### Generator fixes
1. **SEO primitives in the foundation (fenced):**
   - A `<SeoHead>` component (react-helmet-async or head injection) reading an SEO block on
     `siteConfig` (title, description, canonical, OG/Twitter, keywords).
   - **JSON-LD structured data** for the vertical — `LocalBusiness`/`Restaurant` schema
     (name, address, geo, `openingHours`, `telephone`, `priceRange`, `servesCuisine`, `menu` URL,
     `aggregateRating`). Emitted from `siteConfig`.
   - Semantic landing markup: single `<h1>`, sectioned content, descriptive `alt` text.
2. **Infra:** `InfraGeneratorNode` emits `sitemap.xml` + `robots.txt` (sitemap from the route
   manifest's public routes).
3. **Content:** seed real business copy (hero, USP, hours, location) from the brief — no lorem/empty.
   Fix the broken-image issue by sourcing real/placeholder-but-valid image URLs.
4. **SPA SEO caveat (must decide):** a Vite SPA serves an empty shell to crawlers. Options:
   (a) **prerender/SSG** the landing (+ key public pages) via a vite prerender plugin — best for SEO;
   (b) static meta in `index.html` only — weak; (c) move to SSR. Recommend (a) for the landing page.
5. **Contract/prompt:** add an "SEO landing" rule to `arch_outline.txt` + `file_generate_frontend.txt`
   requiring the home/landing page to include `<SeoHead>` + JSON-LD from `siteConfig`.

### Components to touch
`webapp-foundation` (`SeoHead`, `siteConfig` SEO block, JSON-LD helper) · `InfraGeneratorNode`
(sitemap/robots, prerender wiring) · `arch_outline.txt` + `file_generate_frontend.txt` ·
`FOUNDATION_CONTRACT_FRONTEND.md` (siteConfig SEO shape) · Dockerfile/build (prerender step if D4=a).

### Open decisions
- **D4:** prerender/SSG vs meta-only vs SSR for crawler-facing SEO.
- **D5:** which schema.org type per business category (Restaurant, Store, LocalBusiness, …) — derive
  from the brief category.

---

## Workstream 3 — Generalized, deny-by-default endpoint security (fixes defects #2 & #3)

**Goal:** access control is a **property of each endpoint, generated deterministically from the plan**,
so no path drift or LLM freehand can leave data exposed. Default is **deny**.

### Key lever
The manifest **already declares the correct access tier per endpoint** (`public|authenticated|admin`)
and the correct `/api/v1` paths. Stop letting the LLM author `SecurityConfig`; **derive protection**.

### Generator fixes
1. **Deterministic security generation (new `SecurityPolicyGenerator`/patcher):** after backend
   generation, read each endpoint's `access` tier from the manifest and emit authorization that is
   **path-drift-proof** — prefer **method-level** `@PreAuthorize` on each controller method
   (`permitAll` / `isAuthenticated()` / `hasAuthority('ADMIN')`) keyed to the tier, since annotations
   travel with the method regardless of the mapped path. Keep `SecurityConfig` minimal:
   `@EnableMethodSecurity`, JWT filter, and **`anyRequest().authenticated()` (deny-by-default)** —
   never `anyRequest().permitAll()`.
2. **Reconcile matchers to real paths (if any path matchers remain):** derive `permitAll` matchers
   from the **actual** generated controller `@RequestMapping` values (scanned off disk), not the plan,
   so a drifted path can never silently fall through.
3. **Kill path drift at the source:** enforce a single API prefix. Add a backend post-pass that
   normalizes every controller to the planned `/api/v1/...` (or asserts it), so SDK, security, and
   plan all agree. (Complements #1 — belt and suspenders.)
4. **Ownership enforcement:** for `authenticated` resource endpoints (`my-orders`, `GET /orders/{id}`),
   generate an ownership check (`resource.customerId == currentUserId()`), reusing Workstream 1's
   current-user resolver. Add a validation-node lint that flags owned endpoints missing the check.
5. **Verification gate:** a validation-node step that, from the manifest, asserts every
   `admin`/`authenticated` endpoint is actually protected (e.g., static check that the controller
   method carries the right `@PreAuthorize`), failing the build if an admin endpoint is reachable
   unauthenticated.

### Components to touch
New `SecurityPolicyGenerator` (backend validation phase) · `webapp-foundation/SecurityConfig`
(deny-by-default + `@EnableMethodSecurity`) · enhance existing `SecurityConfigPatcher` /
`RolePrefixPatcher` · API-prefix normalizer · `FOUNDATION_CONTRACT_BACKEND.md` (state the security
model) · `file_generate_backend.txt` (rule: declare access via `@PreAuthorize`, never author
SecurityConfig).

### Open decisions
- **D6:** method-level `@PreAuthorize` (drift-proof, preferred) vs regenerated path matchers vs both.
- **D7:** enforce the `/api/v1` prefix by rewriting drifted controllers, or only assert + fail?

---

## Workstream 4 — Deterministic frontend error pre-pass (JSX patcher + siblings)

**Goal:** eliminate the mechanical, high-volume frontend type errors **before** the ErrorFixAgent runs,
so the fix loop converges instead of exhausting. Grounded in `docs/frontend-error-pattern-analysis.md`
(farmaaish: **65 as-generated errors; ~72% mechanically fixable; one pattern = 48%**).

### Primary: JSX namespace patcher (removes ~48% of frontend errors) — ✅ DONE (2026-08-08)

**Status:** implemented. The patcher already existed as `util/JsxTypeImportFixer` (wired in
`FrontendValidationNode`, unit-tested) — it fixed all 31 JSX errors in the farmaaish run (the tip has
zero left). The two genuinely-missing halves were completed this session: (1) the **prevention prompt
rule** added to `file_generate_frontend.txt` (rule 0, React 19 constraints); (2) the patcher's insertion
**hardened** to place the import *after* any leading `'use client'` directive or fence/banner comment,
so it can never displace a foundation fence marker off line 1 (2 new tests; 5/5 green). Uncommitted on
`feature/e2e_testing_pipeline`.

- **Defect:** 31/65 errors were `TS2503 Cannot find namespace 'JSX'` — every component annotates
  `(): JSX.Element` but `JSX` is not in scope. Under the modern `react-jsx` transform + React 19 types,
  `JSX` is no longer a global namespace; it lives under `React.JSX`.
- **Fix (deterministic patcher in the `FrontendValidationNode` pre-pass, run BEFORE the ErrorFixAgent,
  alongside `TypeScriptImportFixer`):** for any `.ts`/`.tsx` that references `JSX.Element`/`JSX.*` with
  `JSX` not in scope, either
  (a) insert `import type { JSX } from 'react';` (minimal — keeps the annotation), or
  (b) rewrite `JSX.Element` → `React.JSX.Element` when `React` is already imported (no new import).
  Idempotent; skip files that already import `JSX` or use `React.JSX`.
- **Prevention (belt + suspenders):** rule in `file_generate_frontend.txt` — "type component returns as
  `React.JSX.Element` or omit the annotation; never bare `JSX.Element`."
- **Impact:** this single patcher removes ~48% of the frontend error volume; on farmaaish it alone would
  very likely have let the 30-round loop converge.

### Siblings (same mechanical pre-pass, from the analysis)
- ✅ **`process.env.X` → `import.meta.env.VITE_X` patcher** (Pattern G, `TS2591`) — DONE: `ProcessEnvPatcher`
  (wired into `FrontendValidationNode`, unit-tested).
- ✅ **Missing react-query hook import** (`useQuery` used but not imported → `TS2304`) — DONE:
  `TanStackImportFixer`. Default↔named shapes (`TS2613`/`TS1192`/`TS2614`) were already covered by
  `TypeScriptImportFixer`; `AuthContextType` (`TS2724`) by the foundation contract.
- ✅ **used-but-unimported JSX tag** (`<Card>`/`<ShoppingCart/>`, Pattern C) — DONE: new
  **`NodeModuleExportRegistry`** (scans the declared node_modules dependency scope → unambiguous
  symbol→package map) + **`UiImportRewriter` rule 3** (adds the import for any capitalized JSX tag resolvable
  in local shadcn or a scoped package). Wired + unit-tested.
  **Backend extension (next):** mirror the registry as a `MavenDependencyRegistry` — scan the resolved
  dependency jars (scoped to the pom) for simple-name → FQN — and feed `JavaImportResolver` so backend code
  gets the same "used-but-unimported library symbol" resolution. The `NodeModuleExportRegistry` Javadoc calls
  out this parallel by design.
- ✅ **shadcn pre-installer completeness** — DONE: foundation now ships 43 shadcn components (was 25;
  +`progress` and 17 more), committed to `webapp-foundation` (`6387476`). `TS2307` module-not-found is gone.

### Components to touch
New `JsxNamespacePatcher` + `ProcessEnvPatcher` (run in `FrontendValidationNode` **before** `ErrorFixAgent`)
· extend `util/TypeScriptImportFixer` · shadcn pre-installer component set · `file_generate_frontend.txt`
(JSX return-type rule). Each patcher ships with unit tests (usage-without-import → adds import;
already-imported / `React.JSX` present → no-op).

### Open decision
- **D8:** JSX fix style — inject `import type { JSX }` (keep annotations) vs rewrite to `React.JSX.Element`
  (no import churn) vs a prompt-only nudge to drop the annotation. *Recommend the patcher for determinism;
  the import-injection variant is the least invasive.*

---

## Sequencing & gating

Per direction: **land nothing here until we've confirmed generation is correct** (a clean run to a
finished PR that builds, boots, and passes the e2e smoke we ran manually). **Exception: W4** is the
natural first step — it's cheap, purely deterministic, and directly *serves* that gate by making the
frontend fix loop converge (removing ~72% of errors, ~48% from the JSX patcher alone), which is what a
"clean run to a finished PR" depends on. Suggested order: **W4 (mechanical pre-pass) → W3 (security —
highest risk, mostly deterministic) → W1 (identity/signup) → W2 (SEO).** Each ships with a validation-node
check so the defect it fixes becomes a build-failing invariant, not a thing we re-discover by hand.

## Per-workstream verification
- **W1:** two distinct signed-up users → each `my-orders` returns only their own; no placeholder id
  in any owner column; owner-FK type == `User.id` type.
- **W2:** landing page returns `<title>`/meta/JSON-LD in the crawler-visible HTML; `sitemap.xml` +
  `robots.txt` served; JSON-LD validates against schema.org.
- **W3:** every `admin`/`authenticated` endpoint returns 401/403 unauthenticated (script the whole
  manifest); no endpoint falls through to `permitAll`; owned endpoints enforce ownership.
- **W4:** after the pre-pass, `tsc --noEmit` shows zero `TS2503`/`TS2591` and no default↔named import
  errors; the ErrorFixAgent seed drops from ~65 to the ~15 genuine type/contract issues (ideally
  converging within budget); per-patcher unit tests green.
