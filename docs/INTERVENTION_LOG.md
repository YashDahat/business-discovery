# Manual Intervention Log

Every manual change made to a *generated client repo* is a defect in the platform:
if a human had to touch the repo, the pipeline failed to. Each entry maps the
intervention to its platform fix so the same class can never require hands again.

Format: date | repo | what was done manually | why | platform fix + status

---

## 2026-07-04 — multifit-aundh

### 1. Branch reset: `feature/gym-booking-c49116b8` force-reset to initial commit
- **What:** wiped all generated code + docs/ARCHITECTURE.json from the branch so the
  next run replans from scratch.
- **Why:** the spec was generated before the platform-stack pin existed and was
  architecturally poisoned by the brief's "decoupled Next.js application" note:
  `App.tsx` rendered an empty `<div />` while all page content sat in unrouted
  Next-style `pages/` files (`_app.tsx`, `_document.tsx`, `trainers/[slug].tsx`).
  The site would have been a blank page. Patching was rejected — the architecture,
  not the code, was wrong.
- **Platform fix (SHIPPED same day):**
  - `arch_spec.txt`: PLATFORM STACK section — fixed stack, FORBIDDEN list
    (`pages/_app.tsx`, `next/*` imports, etc.), conflict rule voiding brief tech directives
  - `ProjectPlanningNode`: brief tech stack never reaches prompts (`PLATFORM_TECH_STACK`
    constant); npm deps union with defaults + `FORBIDDEN_NPM_PACKAGES` strip
  - `FrontendValidationNode.assertAppHasRouting`: blank-SPA guard — App.tsx without
    router wiring fails validation with an actionable message
  - Phase 2 follow-up still open: constrain `SynthesizeBriefNode` so briefs cannot
    specify non-platform stacks at the source.

### 2. (Avoided) pom.xml patch: `spring-boot-starter-mail`
- **What would have been done manually:** add the mail starter + `spring.mail.*` props —
  the generated `NotificationService` autowires `JavaMailSender`; compiles fine, but the
  app fails to boot (caught by the smoke boot gate — its first production catch).
- **Platform fix (SHIPPED instead of manual patch):** `mail` added to
  `DEFAULT_SPRING_STARTERS`; boot-safe `spring.mail.*` defaults written to generated
  application.properties; `SMTP_*` keys added to generated .env.example.
  Real SMTP creds remain a client-onboarding concern.

### 3. (Historical, earlier session) user manually adjusted repo structure toward React/Spring
- **Why:** same Next.js poisoning, pre-dating the stack pin.
- **Platform fix:** same as entry 1.

---

## Earlier interventions recorded retroactively (log-house-restaurant, 2026-07-03 analysis)

These were found in review, not manually fixed — listed so their platform fixes are tracked:

- **Duplicate JWT filters** (`JwtAuthFilter` wired + dead `JwtAuthenticationFilter`, both
  `@Component`; live one lacks try/catch → stale browser token = sitewide 500).
  Platform fix: OPEN — dead-filter sweep / prompt constraint.
- **`@PreAuthorize hasAuthority('ADMIN')` vs `ROLE_ADMIN`** — dormant only because
  `@EnableMethodSecurity` is absent. Platform fix: OPEN — prompt consistency rule.
- **index.html `<title>frontend</title>`, no meta/OG/JSON-LD head tags.** Platform fix:
  OPEN — deterministic scaffold-time injection of business title/meta/OG (agreed SEO plan).
- **About/Contact pages generated but never routed.** Platform fix: PARTIAL —
  assertAppHasRouting catches a routeless App.tsx, but not individual orphan pages;
  route-coverage check vs manifest still open.

---

## 2026-07-04 (later) — multifit-aundh regeneration runs: no manual repo edits, two platform bugs + four generation defect classes found

### 4. Sisyphus loop: retries destroyed ErrorFixAgent work
- **Observed:** attempt 2 regenerated all frontend files over attempt 1's ~20 fix-agent
  patches (branch history: attempt-2 layer commits directly bury the attempt-1 fix commit);
  the agent re-applied byte-identical patches. Retries burned $0.44 each and never converged.
- **Root cause:** generator `shouldSkip` skipped only VALIDATED/SPEC_COMPLIANT files, and
  those statuses are written only when validation PASSES — a failed fix session left all
  files GENERATED, so the next attempt regenerated everything.
- **Platform fix (SHIPPED):** `shouldSkip` in both generator nodes also skips
  GENERATED files that exist on disk; only PLANNED and GENERATION_FAILED regenerate.
  Post-generation, the fix loop owns files; fixes now persist across attempts.

### 5. Four systematic frontend generation defect classes (~80% of fix-agent workload)
From the preserved attempt-2 seeded error list; all recurred across every generation to date:
- **A. `: JSX.Element` annotations** (TS2503, dominant) → prompt rule (never annotate;
  ReactNode/inferred) + deterministic `JsxTypeImportFixer` (adds `import type { JSX }`)
- **B. TanStack Query v4 idioms vs installed v5** (`isLoading` on mutations, positional
  useQuery) → prompt rules pinning v5 object API + isPending
- **C. Radix/shadcn confusion** (DialogHeader from @radix-ui, capitalized ui/Button paths)
  → SUPERSEDED same day by the generic UI inventory system (user: "the solution must be
  generic"): `UiComponentInventory` enumerates REAL exports (node require() per installed
  @radix-ui package + parsed src/components/ui/*.tsx exports), injects an AVAILABLE UI
  IMPORTS ground-truth section into every frontend generation prompt, and
  `UiImportRewriter` mechanically relocates/canonicalizes imports the inventory disproves.
  Hardcoded component rules removed from prompts. Zero component names in code.
- **D. zod numeric fields without coerce** (resolver type mismatch) → z.coerce.number()
  contract in both prompts
Plus: `export type` re-export rule (isolatedModules) and boolean JSX attribute rule.
All SHIPPED as prompt rules; A additionally has a mechanical fixer.

## 2026-07-05 — platform fixes for the 8 multifit hand-interventions (all shipped + unit-tested)

- **Mail health 503** → `management.health.mail.enabled=false` in generated application.properties.
- **ROLE_ prefix 403** → `RolePrefixPatcher` (runs UNCONDITIONALLY in BackendValidationNode, since the
  bug compiles): when hasRole/hasAnyRole is used anywhere, prefixes role-derived SimpleGrantedAuthority
  args with ROLE_. arch_spec.txt standardized on hasRole + ROLE_ consistency.
- **Provider nesting blank page** → file_generate.txt rule: router-hook contexts (AuthContext →
  useNavigate) must render inside BrowserRouter; App.tsx nesting order pinned.
- **Blank App.tsx** → `AppRouteSynthesizer` (unconditional frontend pre-pass): rebuilds a routeless
  App.tsx from page files + link graph, correct provider nesting, ProtectedRoute wrapping for admin.
- **API prefix doubling / wrong path / method mismatch** → `ApiContractChecker` (frontend pre-pass):
  deterministically empties a doubled baseURL; reports method/path mismatches to
  docs/API_CONTRACT_REPORT.md (non-fatal). file_generate.txt rule pins baseURL-owns-prefix + admin paths.

Discipline note: RolePrefixPatcher and AppRouteSynthesizer run UNCONDITIONALLY (not only on compile
failure) — both bugs compile fine and are runtime-only.

Still open: index.html title/meta (SEO), hero/card image sourcing, behavioral gate (Playwright),
retry ground rules + stage pass-rate dashboard (master-side Falcon-discipline items).
