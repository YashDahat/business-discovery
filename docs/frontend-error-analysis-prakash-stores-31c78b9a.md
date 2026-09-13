# Frontend Error Analysis — Prakash Stores (`worker-95a128ac`, brief `31c78b9a`)

**Date:** 2026-09-02
**Project:** Prakash Stores (clothing-store e-commerce)
**Branch:** `feature/clothing-store-ecommerce-31c78b9a` (repo `YashDahat/prakash-stores`)
**Worker image:** `discovery-worker:latest` (contains the issue-5 `RowActionContractNormalizer` fix + UUID widening)

---

## How these errors were obtained

The pipeline **never ran the frontend build itself.** `FrontendValidationNode` performs a
route-manifest existence precheck *before* `npm run build` and *before* the `ErrorFixAgent` loop.
That precheck failed hard on both attempts:

```
[WORKER FAILED] type=CODE message=Route manifest names 1 page(s) that do not exist on disk
                — generation left them behind (GENERATION_FAILED?): NotFoundPage (*)
```

So the run died at node 10/16 with `type=CODE` → auto-retry → same failure → task FAILED. **The
`ErrorFixAgent` never engaged on the frontend**, which is why the raw error count below is high — this
is *unrepaired* generator output, not the state after the normal fix rounds.

To see the real frontend health, the branch was reproduced locally:

```bash
git clone --depth 1 --branch feature/clothing-store-ecommerce-31c78b9a \
  https://github.com/YashDahat/prakash-stores.git
cp webapp-foundation/frontend/src/pages/NotFoundPage.tsx \
  prakash-stores/frontend/src/pages/NotFoundPage.tsx   # the fix now in the foundation
cd prakash-stores/frontend && npm ci && npx tsc -b
```

Result: **54 TypeScript errors** (`tsc -b`, the `build` script's first stage).

### Two validations that came back clean ✅

| Check | Result |
|---|---|
| **Issue-5 admin-table row-action callbacks** (`ProductTable`, `EventTable`, `OrderTable`) | **0 errors** — the reconciled full-object callback contracts compile. |
| **`NotFoundPage` (the foundation fix)** | **0 errors** — compiles as shipped; adding it lets the build run. |

---

## Error totals by TypeScript code

| Code | Count | Meaning |
|---|---|---|
| `TS2339` | 19 | Property does not exist on type |
| `TS2305` | 15 | Module has no exported member (missing named export) |
| `TS2322` | 6 | Type not assignable (all `unknown` cascades) |
| `TS7006` | 3 | Parameter implicitly has an `any` type |
| `TS2613` | 3 | Module has no **default** export (imported as default) |
| `TS2307` | 3 | Cannot find module |
| `TS2614` | 2 | Module has no **named** export (imported as named) |
| `TS2554` | 1 | Wrong number of arguments |
| `TS2353` | 1 | Unknown property in object literal |
| `TS2304` | 1 | Cannot find name |
| **Total** | **54** | |

---

## Root-cause themes

All 54 errors map to **7 themes**. Each is the same underlying producer/consumer drift: a consumer
binds to something a producer never declared.

### Theme A — Invented `ProductDto` fields (`variants`, `additionalImages`) — 20 errors

**Ground truth** — `frontend/src/types/product.ts` declares:
```ts
export interface ProductDto {
  id: number; name: string; description: string; price: number; imageUrl: string;
  stockQuantity: number; size: string; color: string; material: string; gender: string;
  active: boolean; category: string; brand: string;
}
```
There is **no `variants` and no `additionalImages`.** The product components invented a richer
variant/gallery model and read fields that don't exist. The `unknown`/implicit-`any` errors are
downstream cascades: `product.variants` is `any`→`unknown`, so `.map(v => …)` gives `v: any` and the
rendered values are `unknown`.

| # | Location | Code | Message |
|---|---|---|---|
| 15 | `components/admin/product/ProductForm.tsx(126,30)` | TS2339 | `'variants' does not exist on type 'ProductDto'` |
| 13 | `components/admin/product/ProductForm.tsx(125,7)` | TS2353 | `'additionalImages' does not exist in type 'ProductDto'` |
| 14 | `components/admin/product/ProductForm.tsx(125,38)` | TS2339 | `'additionalImages' does not exist on type 'ProductDto'` |
| 22–28,30 | `components/product/ProductDetails.tsx(17…24)` | TS2339 (×8) | `'variants' does not exist on type 'ProductDto'` |
| 29,31 | `components/product/ProductDetails.tsx(23,67)/(24,68)` | TS7006 (×2) | `Parameter 'v' implicitly has an 'any' type` |
| 32–37 | `components/product/ProductDetails.tsx(64…83)` | TS2322 (×6) | `Type 'unknown' is not assignable to type 'Key\|string\|ReactNode'` |
| 50 | `pages/ProductDetailPage.tsx(49,52)` | TS2339 | `'additionalImages' does not exist on type 'ProductDto'` |

**Owner:** model-alignment gap. The `ContractReconciler` reconciles *declared* interfaces (DTO fields,
props, method signatures) but does **not** see field access inside component bodies, so `product.variants`
slips through. Fix requires either the backend/DTO to add the fields or a body-level field-access
alignment pass (the "Theme D" gap noted on abs-fitness).

---

### Theme B — `category`/`brand` modeled as string, but consumed as DTO objects — 12 errors

**Ground truth:** `ProductDto.category` and `ProductDto.brand` are **`string`**. `productService.ts`
exports only `getAllProducts`, `getProductById`, `createProduct`, `updateProduct`, `deleteProduct`,
`updateProductStock` — **no** category/brand getters. `types/product.ts` exports only `ProductDto`.
The admin form and filter sidebar assume a normalized catalog (`ProductCategoryDto`/`BrandDto` entities +
list endpoints) that the backend never produced.

| # | Location | Code | Message |
|---|---|---|---|
| 11 | `components/admin/product/ProductForm.tsx(97,39)` | TS2339 | `'name' does not exist on type 'string'` (treating `category` as an object) |
| 12 | `components/admin/product/ProductForm.tsx(98,33)` | TS2339 | `'name' does not exist on type 'string'` (treating `brand` as an object) |
| 9 | `components/admin/product/ProductForm.tsx(27,10)` | TS2305 | `'@/types/product' has no exported member 'ProductCategoryDto'` |
| 10 | `components/admin/product/ProductForm.tsx(27,30)` | TS2305 | `'@/types/product' has no exported member 'BrandDto'` |
| 39 | `components/product/ProductFilterSidebar.tsx(21,32)` | TS2305 | `… no exported member 'ProductCategoryDto'` |
| 40 | `components/product/ProductFilterSidebar.tsx(21,52)` | TS2305 | `… no exported member 'BrandDto'` |
| 38 | `components/product/ProductFilterSidebar.tsx(21,10)` | TS2305 | `… no exported member 'ProductFilterRequest'` |
| 51 | `pages/ProductsPage.tsx(9,27)` | TS2305 | `… no exported member 'ProductFilterRequest'` |
| 7 | `components/admin/product/ProductForm.tsx(26,10)` | TS2305 | `'@/services/productService' has no exported member 'getAllProductCategories'` |
| 8 | `components/admin/product/ProductForm.tsx(26,35)` | TS2305 | `… no exported member 'getAllBrands'` |
| 41 | `components/product/ProductFilterSidebar.tsx(22,10)` | TS2305 | `… no exported member 'getAllProductCategories'` |
| 42 | `components/product/ProductFilterSidebar.tsx(22,35)` | TS2305 | `… no exported member 'getAllBrands'` |

**Owner:** same producer/consumer drift as Theme A, at the type/service boundary. The frontend planned a
richer catalog than the backend feature; the reconciler didn't reconcile the invented `ProductCategoryDto`/
`BrandDto` into producing files (nothing declares them).

---

### Theme C — Invented order type names — 5 errors

**Ground truth:** `types/order.ts` exports `OrderResponse`, `CreateOrderRequest`, `OrderStatus`,
`OrderType`, `OrderItemResponse`, `OrderItemRequest` (+ `*Values` tuples). It does **not** export
`Order`, `OrderDetailsForPayment`, or `ShippingDetails`. The checkout/payment components reference
type names that don't exist (the real response type is `OrderResponse`, not `Order`).

| # | Location | Code | Message |
|---|---|---|---|
| 17 | `components/checkout/PaymentSelection.tsx(9,30)` | TS2305 | `'@/types/order' has no exported member 'Order'` |
| 18 | `components/checkout/PaymentSelection.tsx(9,37)` | TS2305 | `… no exported member 'OrderDetailsForPayment'` |
| 19 | `components/checkout/ShippingAddressForm.tsx(19,10)` | TS2305 | `… no exported member 'ShippingDetails'` |
| 48 | `pages/CheckoutPage.tsx(11,10)` | TS2305 | `… no exported member 'ShippingDetails'` |
| 49 | `pages/CheckoutPage.tsx(11,27)` | TS2305 | `… no exported member 'OrderDetailsForPayment'` |

**Owner:** producer/consumer drift; naming divergence the reconciler didn't unify.

---

### Theme D — `getAllProducts` returns `void` → `never` cascade — 4 errors

**Ground truth:** `productService.ts` declares `getAllProducts = async (): Promise<void>` — it returns
**nothing**. Consumers then read `.content`/`.length` off a `never`/`void` result, and one call site
passes an argument to the zero-arg function.

| # | Location | Code | Message |
|---|---|---|---|
| 45 | `pages/admin/AdminDashboardPage.tsx(14,35)` | TS2339 | `'length' does not exist on type 'never'` |
| 52 | `pages/ProductsPage.tsx(32,73)` | TS2554 | `Expected 0 arguments, but got 1` |
| 53 | `pages/ProductsPage.tsx(33,48)` | TS2339 | `'content' does not exist on type 'never'` |
| 54 | `pages/ProductsPage.tsx(42,16)` | TS7006 | `Parameter 'prevFilters' implicitly has an 'any' type` |

**Owner:** backend/service contract bug — a list endpoint typed `Promise<void>` instead of
`Promise<ProductDto[]>` (or a paged response). The service signature is the producer defect here; the
four consumer errors all cascade from it.

---

### Theme E — Export-shape drift (default vs named) — 5 errors

Sibling imports disagree with how the target file exports. This goes **both ways**, confirming it's
uncoordinated. Two of these are **deterministic worker-generator bugs** (both sides are worker/foundation-owned,
not LLM), which makes them the cleanest to fix.

| # | Location | Code | Consumer import | Actual export | Owner |
|---|---|---|---|---|---|
| 2 | `AppRoutes.tsx(9,8)` | TS2613 | `import siteConfig from …` (default) | `export const siteConfig` (named) | **Worker** — `RouteManifestGenerator.emitAppRoutes` vs `SiteConfigGenerator` |
| 1 | `AppRoutes.tsx(6,8)` | TS2613 | `import ProtectedRoute from …` (default) | `export function ProtectedRoute` (named) | **Worker** — `RouteManifestGenerator.emitAppRoutes` |
| 20 | `components/event/EventList.tsx(1,8)` | TS2613 | `import EventCard` (default) | `export function EventCard` (named) | LLM sibling |
| 21 | `components/home/FeaturedProducts.tsx(3,10)` | TS2614 | `import { ProductCard }` (named) | `export default function ProductCard` | LLM sibling |
| 43 | `components/review/ReviewList.tsx(4,10)` | TS2614 | `import { ReviewForm }` (named) | `export default function ReviewForm` | LLM sibling |

**Related latent bug (masked, not yet counted):** `AppRoutes.tsx(32)` renders
`<ProtectedRoute allowedRoles={['ADMIN']}>`, but `ProtectedRoute`'s props are `{ children, roles?: string[] }`
— there is no `allowedRoles`. `tsc` reports the export-shape error (#1) on that import first; once that's
fixed, the `allowedRoles` vs `roles` prop mismatch surfaces. Both originate in
`RouteManifestGenerator.emitAppRoutes` — a worker/component contract mismatch worth fixing at the source.

**Owner:** the three LLM siblings are normally repaired by `TypeScriptImportFixer`/`ErrorFixAgent`
(which never ran here). The two `AppRoutes` errors + the `allowedRoles` prop are deterministic worker bugs.

---

### Theme F — Foundation `AuthUser` contract drift — 4 errors

**Ground truth:** the foundation identity type is `interface AuthUser { username: string; role: string }`
(`context/AuthContext.tsx`). `ProfileDetails` reads a full profile the foundation user doesn't carry.

| # | Location | Code | Message |
|---|---|---|---|
| 3 | `components/account/ProfileDetails.tsx(17,46)` | TS2339 | `'firstName' does not exist on type 'AuthUser'` |
| 4 | `components/account/ProfileDetails.tsx(21,45)` | TS2339 | `'lastName' does not exist on type 'AuthUser'` |
| 5 | `components/account/ProfileDetails.tsx(25,55)` | TS2339 | `'email' does not exist on type 'AuthUser'` |
| 6 | `components/account/ProfileDetails.tsx(29,53)` | TS2339 | `'phone' does not exist on type 'AuthUser'` |

**Owner:** foundation-contract adherence. The component should bind to the foundation `AuthUser` shape
(or fetch a profile via a dedicated endpoint). The `FOUNDATION_CONTRACT` should have steered this.

---

### Theme G — Missing modules / utility — 4 errors

| # | Location | Code | Message | Cause |
|---|---|---|---|---|
| 46 | `pages/CartPage.tsx(6,32)` | TS2307 | `Cannot find module '@/components/cart/CartItemsTable'` | Foundation cart lives at `@/cart/` (context + hooks only). `FoundationRefReconciler` **stripped** `components/cart/CartItemsTable.tsx` (per run log) but did not repair `CartPage`'s import. |
| 47 | `pages/CartPage.tsx(7,29)` | TS2307 | `Cannot find module '@/components/cart/CartSummary'` | Same as above (`CartSummary` stripped). |
| 16 | `components/checkout/PaymentSelection.tsx(8,57)` | TS2307 | `Cannot find module '@/hooks/paymentHooks'` | Referenced hook module never generated. |
| 44 | `components/review/ReviewList.tsx(64,40)` | TS2304 | `Cannot find name 'cn'` | Missing `import { cn } from '@/lib/utils'`. |

**Owner:** #46/#47 are a foundation-path/strip-without-fixing-referencers bug (`FoundationRefReconciler`
stripped the files but `CartPage` should import from `@/cart`). #16/#44 are ordinary missing-import errors
the `ErrorFixAgent` normally fixes.

---

## Cross-cutting conclusions

1. **The `NotFoundPage` precheck masked the entire frontend's health and blocked its repair.** Because it
   throws before `npm build` + `ErrorFixAgent`, none of these 54 errors ever reached the fix loop. With
   `NotFoundPage` now shipped from the foundation, future runs will build and the `ErrorFixAgent` will
   engage on whatever remains.

2. **Issue 5 is confirmed in a real project** — 0 errors on `ProductTable`/`EventTable`/`OrderTable`
   row-action callbacks.

3. **Recoverable by the existing fix loop (≈13 errors):** Theme E LLM siblings (#20,#21,#43), Theme G
   #16/#44, and likely several cascades once their root is fixed. These are the `TypeScriptImportFixer`/
   `ErrorFixAgent`'s normal job.

4. **Deterministic worker bugs (cleanest, highest-leverage — recur every project):**
   - `RouteManifestGenerator.emitAppRoutes` imports `siteConfig` and `ProtectedRoute` as **default**;
     they are **named** exports (#1, #2).
   - `emitAppRoutes` passes `allowedRoles` to `ProtectedRoute`, whose prop is `roles` (latent, masked).
   - `FoundationRefReconciler` strips `components/cart/*` without repairing `CartPage`'s import (#46, #47).

5. **Deepest, highest-value class (≈41 errors): invented DTO fields/types/methods** (Themes A–D + F).
   The frontend routinely models a richer domain than the backend/foundation declares (`variants`,
   `additionalImages`, `ProductCategoryDto`, `BrandDto`, `Order`/`ShippingDetails`, `AuthUser` profile),
   and `getAllProducts` is typed `Promise<void>`. The `ContractReconciler` can't catch these because it
   reconciles declared interfaces, not body-level field access or invented type imports that no producer
   declares.

## Suggested fix order

1. **Worker generator fixes** (deterministic, recur everywhere): `emitAppRoutes` export shapes + the
   `allowedRoles`/`roles` prop; `FoundationRefReconciler` cart-import repair.
2. **Service/DTO contract** (`getAllProducts: Promise<void>` → list/paged type) — unblocks Theme D.
3. **Model-alignment pass** for body-level field access + invented type imports (Themes A–C) — the
   largest, hardest class; needs a new mechanism beyond the declared-interface reconciler.
4. Foundation-contract steer for `AuthUser` usage (Theme F).
5. Let the `ErrorFixAgent` mop up the residual export/import siblings (Themes E-LLM, G-#16/#44).

---

## Solution A — Cross-layer wire-contract reconciliation (detailed design)

### The gap it closes

The same wire object is reconciled into **two conflicting shapes** because `ContractReconciler`
operates *per feature* and follows *import edges* — and a frontend TS type never "imports" a Java DTO:

| Producer | Feature | Reconciled `ProductDto` |
|---|---|---|
| `backend/.../dto/ProductDto.java` | `product-catalog-core` (BACKEND) | `{ …, category: String, brand: String }` (flat) |
| `frontend/src/types/product.ts` | `product-browsing` (FRONTEND) | `{ …, category: ProductCategoryDto, brand: BrandDto, variants: ProductVariantDto[], additionalImages: string[] }` (rich) |

Both are stamped `contract_reconciled: true`. Neither is wrong *within its feature*; there is simply
**no edge in the reconciler's graph that makes the two agree.** Then `ApiArtifactGeneratorNode`
regenerates `types/product.ts` from the *actual* (flat) backend, while the components were authored
against the *rich* plan — 41 of the 54 errors. Note this is **not** cured by injecting the DTO into the
prompt: `ApiContractCard` already feeds the on-disk flat `ProductDto` verbatim as "ground truth, NOT
editable," and the model still overrode it (shown-but-ignored) because a conflicting rich contract was
also present. The cure is to **remove the contradiction** so only one shape exists.

### Design: pair DTO ↔ FE type by name and reconcile them as ONE contract

A new deterministic pass, `CrossLayerContractReconciler`, run in `ProjectPlanningNode` **immediately
after** `ContractReconciler.reconcile`, before `ARCHITECTURE.json` is written.

1. **Index the backend wire contracts.** Collect every `DTO` / `TYPE` layer file under `backend/**/dto`
   (and enums), keyed by simple name → its reconciled fields (`public_variables`).
2. **Match frontend type files by symbol name.** For each `TYPE`/`UTIL` file under `frontend/src/types`,
   match each exported interface/type to a backend DTO of the same simple name (`ProductDto`↔`ProductDto`,
   `OrderResponse`↔`OrderResponse`).
3. **Conform the frontend type to the backend wire truth** — the backend DTO is what actually
   serializes over HTTP, so it is the source of truth. Rewrite the FE type's fields to the DTO's fields
   with a fixed Java→TS mapping:

   | Java | TS |
   |---|---|
   | `Long`,`Integer`,`int`,`BigDecimal`,`double` | `number` |
   | `String`,`UUID`,`LocalDate`,`LocalDateTime`,`Instant` | `string` |
   | `Boolean` | `boolean` |
   | `List<X>`,`Set<X>`,`X[]` | `X[]` (mapped) |
   | enum | the TS string-literal union already emitted for it |
   | entity ref (`ProductCategory`) | resolve to its DTO if one exists, else the DTO's serialized form (usually the flattened scalar the backend actually returns) |

4. **Propagate the resolved shape into every consumer's contract**, not just the type file: rewrite the
   `RECONCILED CONTRACT` in each frontend file whose role references the changed type, and drop invented
   sibling types the DTO doesn't justify (`ProductVariantDto`, `BrandDto`) unless a backend producer
   declares them. This is what stops `ProductDetails` from being *told* `variants` exists.
5. **Record every conformance** to `docs/CONTRACTS.json` (planned FE shape → conformed shape) for
   observability, same as `ContractReconciler`.

### Two legs (plan-time is necessary but not sufficient)

- **Leg 1 — plan-time (above):** reconciles the *planned* FE type to the *planned* BE DTO. Removes the
  in-plan divergence so components are authored against the real wire shape.
- **Leg 2 — post-backend re-alignment:** the actual backend shifts again during
  `BackendValidationNode` + `ErrorFixAgent` (here the `ProductCategory`/`Brand` entities were flattened
  to strings and `getAllProducts` degraded to `void` to make it compile). So after backend validation
  and *before* `FrontendGeneratorNode`, re-run the conformance against the **regenerated** on-disk
  `types/*.ts` (which `ApiArtifactGeneratorNode` already derives from the real backend) and re-align the
  frontend feature contracts + enrichment cards to it. Without Leg 2, components are still authored from
  a plan the post-fix backend no longer honors.

### The product caveat (why this is a decision, not just a mechanic)

Conforming FE→BE makes the site **compile**, but it silently **drops** the variants/brands/gallery the
plan (and likely the brief) intended — because the *backend* was the degraded side. The higher-quality
outcome is to reconcile the pair toward the **richer** contract and make the backend actually implement
it: if `Product` has `ProductCategory`/`Brand` entities, keep them as DTOs + list endpoints instead of
letting the compile-driven fix loop flatten them. That requires enforcing plan-internal DTO/entity
consistency at planning (fix order #3 below is the mechanical backstop; this is the upstream cure). For
a fast, correct-but-lean build, conform FE→BE; for a faithful build, conform toward the rich contract
and hold the backend to it.

### Relationship to the mechanical field-access guard (Solution B)

Solution A removes the *contradiction*; a body-level field-access guard (Solution B) is still the
deterministic backstop for residual shown-but-ignored drift (a component reading `x.field` not on the
conformed DTO). A is the cure, B is the guarantee — same "steer at the source + mechanical backstop"
split used for the issue-5 row-action normalizer.

---

## Appendix — Generation context: backend vs frontend

Both generators end at the **same** LLM call —
`LlmGeneratorService.generateFileContent(filePath, fileRole, depFiles, existingContent, sharedContext, featureContext)`
(`LlmGeneratorService.java:525`) — which assembles the prompt as:

- **System prompt** = `system/file_generate_{backend|frontend}.txt` **+ `sharedContext`** (the cacheable, run-constant prefix)
- **User prompt** (`user/file_content.txt`) = `filePath` + **`featureContext`** + **`fileRole`** + **`depFiles`** (`formatFilesSection`) + `existingSection`

So the *shape* is identical on both sides. What differs is **what each side pours into `depFiles` and `sharedContext`** — and that difference is the whole story of why the frontend drifts more.

### The two slots that differ

| Prompt slot | Backend (`BackendGeneratorNode.generateWithLlm:340`) | Frontend (`FrontendGeneratorNode.runGenerateStage:631`) |
|---|---|---|
| **`depFiles`** (user prompt) | **Real dependency file BODIES** — `loadDependencyFiles:421` reads every `importsFrom`/`dependsOn` path off disk, **stamps the RECONCILED interface** on top (`stampReconciledInterface:468`), **plus auto-resolves class names** mentioned in the role/instruction to their on-disk source. The model sees actual code of its dependencies. | **No bodies.** Only two *catalogs*: `AVAILABLE UI IMPORTS` (shadcn/ui inventory) and `MODULES THAT ALREADY EXIST` (`exportRegistry.toImportCatalog()` — names/paths only). Registry-only referencing (`:636`); `importsFrom` was abandoned here (~27% empty). |
| **`sharedContext`** (system prompt) | `foundationContract` + **`backendContractCard`** (the reconciled backend contract). Two byte-identical blocks (`sharedContext():362`). | `foundationContract` + **`ApiContractCard`** (backend DTO/endpoint ground truth) + **route card** + **`frontendContractCard`** (incremental hook/context/type sigs from prior layers) + **`plannedPropsCard`** (component props, for component/page files) + **`frontendPlannedContractCard`** (planned hooks/services/types). Six blocks (`:664-700`). |

### What is IDENTICAL on both sides

- **`fileRole`** = `FileContractCard.render(spec, fileRole)` — this file's own contract (purpose + fields + signatures + endpoints).
- **`featureContext`** = `FeatureCard.buildFeatureContext(card, path, instruction)` — enrichment identity + sibling map + effective instruction.
- **`existingContent`** — only populated in requested-changes mode.

### Diagram

```
                     generateFileContent(path, fileRole, depFiles, existingContent, sharedContext, featureContext)
                                                          │
              ┌───────────────────────────────────────────┴───────────────────────────────────────────┐
              │                                                                                         │
        ┌─────▼──────┐  BACKEND                                                          FRONTEND  ┌─────▼──────┐
        │ SYSTEM     │                                                                             │ SYSTEM     │
        │ PROMPT     │  file_generate_backend.txt                                                  │ PROMPT     │  file_generate_frontend.txt
        │            │  + sharedContext:                                                           │            │  + sharedContext (contractSection):
        │            │      • foundationContract                                                   │            │      • foundationContract   (FIRST)
        │            │      • backendContractCard  (reconciled BE contract)                        │            │      • ApiContractCard      (BE DTOs/endpoints = ground truth)
        │            │                                                                             │            │      • route card
        │            │                                                                             │            │      • frontendContractCard (incremental, prior layers)
        │            │                                                                             │            │      • plannedPropsCard     (component props — comp/page only)
        │            │                                                                             │            │      • frontendPlannedContractCard (planned hooks/services/types)
        └────────────┘                                                                             └────────────┘
        ┌────────────┐  USER PROMPT (user/file_content.txt)                                        ┌────────────┐  USER PROMPT (same template)
        │ featureCtx │  = FeatureCard.buildFeatureContext        ── IDENTICAL MECHANISM ──         │ featureCtx │  = FeatureCard.buildFeatureContext
        │ fileRole   │  = FileContractCard.render(spec, role)    ── IDENTICAL MECHANISM ──         │ fileRole   │  = FileContractCard.render(spec, role)
        │            │                                                                             │            │
        │ depFiles   │  = REAL DEPENDENCY FILE BODIES               ◄── THE KEY DIFFERENCE ──►     │ depFiles   │  = CATALOGS ONLY (no bodies):
        │            │      • importsFrom / dependsOn bodies                                       │            │      • AVAILABLE UI IMPORTS (shadcn inventory)
        │            │      • + RECONCILED interface stamped on top                                │            │      • MODULES THAT ALREADY EXIST
        │            │      • + auto-resolved class-name mentions                                  │            │        (exportRegistry catalog: names/paths)
        └────────────┘                                                                             └────────────┘
```

### Why this matters for the drift in this report

The backend consumer is handed the **actual source** (or at least the stamped reconciled interface) of every type it depends on, so field-level drift is caught at authoring time. The frontend consumer is handed only **names, paths, and card-level signatures** — never the producer's body. That is precisely why Themes A–D land on the frontend: a component authored against a *catalog entry* for `ProductDto` can still invent `variants`/`additionalImages`, and a page binding to `ClassTable`'s *planned prop line* can still restyle the handler symmetrically — the real signature is never in front of it. This is the gap the `import_from`-injection follow-up targets: give the frontend the backend's treatment (inject the dependency's reconciled interface into `depFiles`), interface-only to control tokens.

---

## Appendix B — Frontend context after the overlap refactor

### The problem being removed

Today every frontend file (component, page, hook, service, type) receives the **same six cards**, several of which describe the *same entity twice* — once from the stale static plan, once from disk — and one whole layer (the API service SDK) that a component/page must never call directly (it consumes hooks). Three overlaps: **services** (triple-covered + irrelevant to components/pages), **DTOs/wire types** (full verbatim in `ApiContractCard` *and* interface-only in `FrontendPlannedContractCard`), **hooks/contexts** (planned *and* actual, possibly divergent).

### Key fact that drives the cut: hooks and services are MECHANICAL, not LLM

`ApiArtifactGeneratorNode.execute` (`:103-120`) derives **types** (`TsTypeGenerator`), **services** (`TsSdkGenerator` — one function per endpoint), and **hooks** (`FrontendHookGenerator` — one TanStack hook per service) and writes them to disk with **no LLM**. Consequences:

- **No LLM call ever authors a hook or service** → the **API SDK / service signature has no LLM consumer** → it is dropped from generation context **entirely** (not merely gated). The only files that call the SDK are hooks, which are mechanical.
- Derivation runs **before** component/page generation, so the **actual** hook/type signatures are already on disk → the *planned* card (`FrontendPlannedContractCard`) is redundant on the normal path (only the rare parser-gap LLM fallback needs it).
- The **only** wire-contract context an LLM generation needs: **full DTO/model shapes** + **hook signatures**.

### The refactor in one line

Own each entity once, keep the deterministically-enumerable contracts **global**, and apply "**full body for DTOs/models, signature-only for hooks**":

- **DTOs / wire models** → `ApiContractCard` **full verbatim**, all files (global).
- **Hook signatures** → from **actual** derived hooks on disk, all component/page files (global). *Planned* only as parser-gap fallback.
- **API services (SDK)** → **NOT injected to any LLM prompt** (no LLM consumer exists).
- **Component props, local types, utils** → unchanged owners.
- **`import_from`** → additive-only, for the *same-layer sibling-component* seam; mechanically derived from the render graph, never trusted from the planner.

### Context by file role (after)

Only **components and pages** are LLM-generated on the frontend (types/services/hooks are mechanical, above). So there is effectively one LLM-context profile:

```
   LLM-GENERATED FRONTEND FILES = COMPONENTS + PAGES only
   (types/services/hooks are derived mechanically → never hit generateFileContent)

                         ┌───────────── COMPONENT / PAGE prompt ─────────────┐
                         │  SYSTEM PROMPT = file_generate_frontend.txt + sharedContext (cache order ↓)
                         │                                                    │
   cache-stable prefix ──┤   1. foundationContract              (static, global)
   (leads the prompt)    │   2. WIRE TYPES — FULL verbatim DTOs (static, global) ◄── models: full body
                         │   3. route card                      (static, global)
                         │   4. component props (PlannedProps)  (static)      │
                         │        ✗ NO API service / SDK context (no LLM consumer — hooks are mechanical)
                         │        ✗ NO planned hook/type card   (actual is on disk; see tail)
                         │                                                    │
   cache-tail (GROWING) ─┤   N. frontendContractCard (actual, incremental) — placed LAST
                         │        • ACTUAL hook signatures (derived hooks already on disk)
                         │        • actual sibling-component props (prior layers)
                         └───────────────────────┬────────────────────────────

   USER PROMPT (per file):
     • featureContext  = FeatureCard.buildFeatureContext
     • fileRole        = FileContractCard.render(spec, role)
     • depFiles        = ── EXISTENCE LAYER (registries — names/paths, NOT shape) ──
                         • UiComponentInventory          → "AVAILABLE UI IMPORTS" (real shadcn/radix)
                         • TypeScriptExportRegistry       → "MODULES THAT ALREADY EXIST" (toImportCatalog)
                         ── SHAPE LAYER (this file's own deps) ──
                         • [NEW] SIBLING-COMPONENT interfaces (import_from, render-graph derived, interface-only)

   POST-GENERATION (deterministic, never in prompt):
     • TypeScriptExportRegistry → TypeScriptImportFixer (fix @/ + relative paths)
     • NodeModuleExportRegistry → LucideIconValidator / UiImportRewriter (validate node_modules exports)
```

**Cards vs registries — two different layers.** Everything in `sharedContext` above is a **Card** (semantic *shape*: fields, signatures, endpoints). The registries in `depFiles` are the **existence layer** (*namespace*: which modules/symbols exist and their exact import path). A registry gives the LLM a real *name* to import; a Card/`import_from` gives its *shape*. The export catalog is the global existence net that keeps import specifiers real even when `import_from` is incomplete — so it stays global and is untouched by the scoping.

**Duplicates between the two layers (audited):**
- **Registry ↔ registry: none.** `UiComponentInventory` and `TypeScriptExportRegistry` are cleanly partitioned — `seedExportRegistryFromDisk` explicitly excludes `/components/ui/` (`FrontendGeneratorNode.java:800`, comment *"shadcn handled by UiInventory"*), and `ui/` components are installed by shadcn, never planned as generation entries, so they never reach the export registry via the generated/skipped register paths either. UiInventory owns `components/ui/` + the shadcn/radix kit; exportRegistry owns everything else on disk.
- **Card ↔ registry: identifier-level only, benign.** Every module a shape Card renders (`ApiContractCard` types, `FrontendContractCard` hooks/contexts/local-types) also has its name/path listed in the export catalog — so the module *identity* is stated twice (once as "it exists at path X," once as "here is X's shape"). This is complementary, not contradictory (names can't disagree), and costs only tokens. The catalog's unique value is the existence-only modules **no** Card covers — pages, `ui/`, derived types, services. Optional trim: scope the catalog to those, so it stops restating identities the Cards already carry. Much lower priority than the card↔card dedup, which removes actual *contradiction*.

**What about an import NOT in `import_from`?** It still resolves, because the enumerable contracts stay global and never depend on `import_from`:

| Import kind | Source | Needs `import_from`? |
|---|---|---|
| DTO / model | global WIRE TYPES (full) | no |
| hook | global actual hook signatures | no |
| service / SDK | never imported by components (arch rule) | no |
| any existing module's path | global export catalog | no |
| same-layer sibling component | `import_from` (render-graph-derived) → else `ErrorFixAgent` | **only case** |

So `import_from` is purely additive for the same-layer sibling-component seam; its incompleteness degrades to an `ErrorFixAgent` fix, never a silent invented field.

### Backend vs refactored frontend (same view as Appendix A)

```
                     generateFileContent(path, fileRole, depFiles, existingContent, sharedContext, featureContext)
                                                          │
              ┌───────────────────────────────────────────┴───────────────────────────────────────────┐
              │                                                                                         │
        ┌─────▼──────┐  BACKEND (unchanged)                                    FRONTEND (refactored)  ┌─────▼──────┐
        │ SYSTEM     │  file_generate_backend.txt              (LLM authors ALL .java)                │ SYSTEM     │  file_generate_frontend.txt   (LLM authors COMPONENTS + PAGES only;
        │ PROMPT     │  + sharedContext:                                                              │ PROMPT     │                                types/services/hooks are MECHANICAL)
        │            │      • foundationContract                                                      │            │  + sharedContext (cache order):
        │            │      • backendContractCard  (reconciled BE contract)                           │            │   ── static prefix (leads, prefix-cached) ──
        │            │                                                                                │            │      • foundationContract
        │            │                                                                                │            │      • WIRE TYPES — FULL verbatim DTOs  ◄── models: full body
        │            │                                                                                │            │      • route card
        │            │                                                                                │            │      • component props (PlannedProps)
        │            │                                                                                │            │      ✗ NO API SDK / service context (no LLM consumer)
        │            │                                                                                │            │   ── growing tail (last) ──
        │            │                                                                                │            │      • frontendContractCard (actual): ACTUAL hook sigs
        │            │                                                                                │            │        + sibling-component props from prior layers
        └────────────┘                                                                                └────────────┘
        ┌────────────┐  USER PROMPT (user/file_content.txt)                                           ┌────────────┐  USER PROMPT (same template)
        │ featureCtx │  = FeatureCard.buildFeatureContext        ── IDENTICAL MECHANISM ──            │ featureCtx │  = FeatureCard.buildFeatureContext
        │ fileRole   │  = FileContractCard.render(spec, role)    ── IDENTICAL MECHANISM ──            │ fileRole   │  = FileContractCard.render(spec, role)
        │            │                                                                                │            │
        │ depFiles   │  = SHAPE: REAL DEPENDENCY FILE BODIES         ──► NOW CONVERGING ◄──           │ depFiles   │  = EXISTENCE (registries, in-prompt):
        │            │      • importsFrom / dependsOn bodies                                          │            │      • UiComponentInventory (shadcn/radix)
        │            │      • + RECONCILED interface stamped on top                                   │            │      • TypeScriptExportRegistry catalog
        │            │      • + auto-resolved class-name mentions                                     │            │    SHAPE: [NEW] sibling-component interfaces
        │            │    EXISTENCE registry NOT in prompt:                                           │            │      • import_from (render-graph, interface-only)
        │            │      • JavaClassRegistry → post-gen only                                       │            │                                            │
        │            │        (JavaImportResolver rewrites imports)                                   │            │  EXISTENCE registry also post-gen:         │
        │            │                                                                                │            │      • TS/Node registries → ImportFixer     │
        └────────────┘                                                                                └────────────┘
                        FULL BODIES  ◄─────────────── the one deliberate difference ──────────────►  INTERFACE-ONLY
      registry: POST-GEN RESOLVER (JavaImportResolver)  ◄── existence-layer asymmetry ──►  registry: IN-PROMPT CATALOG + post-gen fixer
```

**Read against Appendix A:** the two sides used to diverge on *both* slots — backend fed real dependency bodies, frontend fed only app-wide catalogs. After the refactor they **converge in shape**: both scope `sharedContext` (backend by contract; frontend to just what a component/page consumes) and both inject *this file's own dependency interfaces* into `depFiles`. Deliberate differences that remain:

1. **Body depth** — backend pastes full bodies, frontend interface-only (JSX bodies are noise a consumer never needs).
2. **DTOs are the frontend exception** — they ride `sharedContext` as **full verbatim**, because the field list *is* the contract.
3. **No service-layer context on the frontend** — services and hooks are generated mechanically, so no LLM ever consumes an SDK signature (unlike the backend, where the LLM writes every layer).
4. **Existence-layer (registry) asymmetry** — the frontend hands the LLM an **in-prompt catalog** of what exists (`UiComponentInventory` + `TypeScriptExportRegistry`) so it imports real symbols, then `TypeScriptImportFixer` cleans residue. The backend puts **no registry in the prompt**: `JavaClassRegistry` is used purely **post-generation** by `JavaImportResolver` to rewrite wrong package prefixes and add missing project imports. Same goal (bind to real symbols), opposite ends — prompt-time catalog (FE) vs post-gen resolver (BE).

### Before → after, at a glance

| Entity | Before (all files) | After |
|---|---|---|
| Wire DTOs / models | full (ApiContract) **+** planned interface (FE-Planned) — duplicated, can contradict | **full only** (ApiContract), all files |
| API services / SDK | on **every** file (ApiContract SDK + FE-Planned) | **removed from all LLM prompts** — no LLM authors hooks/services |
| Hooks | planned **+** actual, both always on, can diverge | **actual signatures only** (derived hooks on disk); planned dropped |
| Contexts | planned **+** actual | actual wins over planned |
| Component props | planned (+ actual) | unchanged |
| Cache order | growing card mid-prompt → evicts static tail | static leads, **growing card last** |

### Why this is expected to reduce drift, not just tokens

A component/page prompt drops from six broad, partly-contradictory cards to **exactly what it consumes**: full DTO shapes, the hook signatures it calls, its own props. Removing the service layer eliminates a block it should never act on; deduping planned-vs-actual removes the fork that lets the model pick the wrong signature (the shown-but-ignored / contradiction class behind Themes A–E). The `import_from` interface injection in `depFiles` then adds the one thing still missing — the *specific* reconciled interface of this file's own dependencies — mirroring what the backend already gets.

### The three edits (implementation note)

Status: **edits 1 & 2 applied + unit-verified (58/58: ApiContractCard 5, FrontendGeneratorNode 10, FrontendContractCard 32, PlannedComponentPropsCard 8, FrontendPlannedContractCard 3); edit 3 revised — see note below.** All three touch `FrontendGeneratorNode.runGenerateStage` / the cards it assembles.

| # | Edit | Where | What changes | Why |
|---|---|---|---|---|
| **1** | **Drop the API SDK / service layer from LLM context** | `ApiContractCard.toPromptSection()` (`:124`) split into `types` vs `sdk`; `runGenerateStage` (`:664`) injects **WIRE TYPES only** for the frontend LLM path | Frontend component/page prompts no longer receive the `API SDK` section or backend routes; **full verbatim DTOs stay** | Services **and** hooks are mechanically derived by `ApiArtifactGeneratorNode` (`:103-120`) — no LLM authors them, so the SDK signature has no consumer. Only DTO *shape* is required. |
| **2** | **Drop `FrontendPlannedContractCard`, dedupe to actual, reorder for cache** | `runGenerateStage` (`:690`) stops appending the planned card on the normal path (retain only as parser-gap fallback, `ApiArtifactGeneratorNode:95`); move the growing `frontendContractCard` to **last** in `contractSection` | Hook/type/context signatures come from **actual** on-disk derived files (`FrontendContractCard`), not the stale plan; static cards lead the prompt, growing card trails | Kills the planned-vs-actual fork (contradiction class behind Themes A–E) and stops the growing card from evicting the cache-stable static prefix. |
| **3** | **~~Add per-file `import_from` interface injection~~ — NOT IMPLEMENTED (redundant)** | — | — | On implementation it turned out the same-layer sibling-component seam is **already closed** by `PlannedComponentPropsCard.toPromptSection()` (`:97`), which injects **every** component/page's props (= its interface) into every component/page prompt, from the static plan, cached, with no planner dependency. Injecting sibling interfaces again via `import_from` would only add a redundant, possibly-divergent second copy. See revision note. |

> **Edit 3 revision.** `PlannedComponentPropsCard` already carries the sibling-component contract globally (props *are* the component interface). So edit 3 was **not** adding missing coverage — a faithful build would either (a) duplicate `plannedPropsCard` (adding a contradiction fork, the opposite of edits 1–2), or (b) *scope* those props per-file to reduce dilution, which **breaks `plannedPropsCard`'s cacheability** and needs a render graph the plan doesn't reliably expose (`import_from` was 31/81 empty). Since the seam is already closed, edit 3 is dropped; the only remaining motive (dilution reduction) is speculative and deferred until a run shows it bites. Edits 1 & 2 stand on their own — they remove real contradiction (SDK block + planned-vs-actual fork) and fix cache ordering.

---

## Appendix C — Frontend generation: full context provenance map

Every piece of context a frontend file (component/page — the only LLM-authored frontend files) receives, traced to its **origin → mechanism → prompt slot → coverage**. The goal is a complete "what comes from where" so the one remaining hole (model/DTO internal fields) is unambiguous.

### The four origins

| Origin | What it produces | Trust |
|---|---|---|
| **`ARCHITECTURE.json`** (plan) | this file's `FileSpec` (role), reconciled contracts, feature→file map, **`foundation_features`** (§6b, committed `4df3761`) | authoritative for *intent* |
| **Enrichment layer** (`ENRICHMENT.json`) | per-feature `FeatureCard` (identity, sibling map, instruction) | authoritative for *requirement* |
| **Derived-from-backend disk** (`ApiArtifactGeneratorNode`) | `types/*.ts` (DTOs), services (SDK), hooks — all **mechanical** | ground truth (compiled backend) |
| **Foundation + workspace** | `FOUNDATION_CONTRACT.md`, `AuthContext`/cart/shell, installed shadcn/radix, prior-layer files | ground truth (on disk) |

### Provenance table (by your categories)

| # | Context category | Origin | Mechanism (card / registry) | Prompt slot | Carries | Status |
|---|---|---|---|---|---|---|
| 1 | **This file's details / role** | ARCHITECTURE.json `FileSpec` | `FileContractCard.render(spec, fileRole)` | USER (`fileRole`) | purpose, fields to build, fn sigs | ✅ |
| 2 | **Feature requirement / flow** | Enrichment `ENRICHMENT.json` | `FeatureCard.buildFeatureContext` | USER (`featureContext`) | identity + sibling map + instruction + **`consumes_foundation`** (§6b, `4df3761`: fed via `{{foundationFeaturesSection}}`, sets `FeatureCard.consumesFoundation`, rendered as "look up the fenced handle VERBATIM") | ✅ |
| 3a | **Backend comms — hooks** (existence) | derived hooks on disk | `TypeScriptExportRegistry.toImportCatalog` | USER (`depFiles`) | import name + path | ✅ |
| 3b | **Backend comms — hooks** (shape) | derived hooks on disk | `FrontendContractCard` (actual, trailing) | SYSTEM | hook **return signature** | ✅ |
| 3c | **App-context sharing** (`useAuth`, `useCart`, checkout) | foundation `context/` | `FrontendContractCard` (context sigs) + `FoundationContractCard` | SYSTEM | accessor **signatures** (prose in foundation card) | ⚠️ partial — see #6 |
| 3d | **Generic utils / computation** (`cn`, formatters) | `lib/`, `services/local/` | `FrontendContractCard` (module) + export catalog | SYSTEM + USER | util signature + import path | ✅ |
| 4a | **Models / DTOs — derived** (`ProductDto`, `OrderResponse`) | derived `types/*.ts` | `ApiContractCard` **WIRE TYPES (full verbatim)** | SYSTEM | **every field, verbatim** | ✅ |
| 4b | **Models / DTOs — foundation** (`AuthUser`) | foundation `context/AuthContext.tsx` | — **none** — | — | — | ❌ **GAP (Theme F)** |
| 4c | **Models / DTOs — nested / non-`export`** | `types/**` subdirs | `ApiContractCard` scan is **non-recursive** (`Files.list`) | SYSTEM | fields only if top-level + `// GENERATED` | ⚠️ partial |
| 4d | **Models / DTOs — invented** (`variants`, `BrandDto`) | no producer | n/a — **Solution A** (plan-time) | — | — | ❌ owned by Solution A |
| 4e | **Models / DTOs — degraded artifact** (`getAllProducts: void`) | derived on disk, **flattened by ErrorFix** | `ApiContractCard` passes it **verbatim — including the wrong type** | SYSTEM | a degraded signature as if it were truth | ❌ **BLIND SPOT** — map trusts derived as ground truth; **Solution A leg 2** (post-backend re-align) |
| 5 | **shadcn / radix UI components** | installed workspace | `UiComponentInventory` (registry) | USER (`depFiles`) | real export names | ✅ |
| 6 | **Foundation features** (auth/cart/checkout/shell) | `FOUNDATION_CONTRACT.md` | `FoundationContractCard` → `foundationContractSection` **+ `FOUNDATION LOOKUP` rule** in `file_generate_{frontend,backend}.txt` (§6b Part C, `4df3761`) | SYSTEM (leads) | usage rules (**prose, not field lists**) **+ a directive to read/import the fenced block VERBATIM** | ✅ usage / ⚠️ field-level — the `FOUNDATION LOOKUP` rule now supplies the *obedience* lever for Theme F (shown-but-ignored); the *field-level* injection ([4b]/task 3) is still open |
| 7 | **Sibling component props** | ARCHITECTURE.json | `PlannedComponentPropsCard` | SYSTEM | each component's prop contract | ✅ |
| 8 | **Routes** | `RouteManifest` | route card | SYSTEM | path → page table | ✅ |
| 9 | **Module existence namespace** | prior-layer disk | `TypeScriptExportRegistry.toImportCatalog` | USER (`depFiles`) | every module's name/path | ✅ |
| 9b | **Export STYLE — default vs named** | prior-layer disk | registry catalog carries name/path **but not export kind** | USER (`depFiles`) | — (kind omitted) | ❌ **BLIND SPOT** — `import { X }` vs `import X` guessed → Theme E siblings |

### Flow diagram

```
 ORIGINS                         MECHANISM (card = SHAPE, registry = EXISTENCE)                SLOT
 ───────                         ───────────────────────────────────────────                 ────

 ARCHITECTURE.json ──► FileSpec ─────────► FileContractCard.render ──────────────────────►  USER · fileRole        [1] ✅
                   └─► component props ──► PlannedComponentPropsCard ─────────────────────►  SYSTEM                 [7] ✅

 ENRICHMENT.json  ───► FeatureCard ───────► FeatureCard.buildFeatureContext ──────────────►  USER · featureContext  [2] ✅

 DERIVED (backend) ─┬─ types/*.ts (DTO) ──► ApiContractCard · WIRE TYPES (FULL verbatim) ──►  SYSTEM                 [4a] ✅ ◄── models: fields
                    ├─ degraded sig ──────► ApiContractCard passes void VERBATIM as truth ──►  SYSTEM                [4e] ❌ blind spot (getAllProducts:void)
                    ├─ hooks ─────────────► FrontendContractCard (actual return sigs) ─────►  SYSTEM (trailing)      [3b] ✅
                    ├─ hooks/services ────► TypeScriptExportRegistry (names/paths) ────────►  USER · depFiles        [3a][9] ✅
                    └─ services (SDK) ─────► ✗ DROPPED (no LLM consumer — edit 1)             —                      —

 FOUNDATION ───────┬─ FOUNDATION_CONTRACT ► FoundationContractCard (usage rules, PROSE) ───►  SYSTEM (leads)         [6] ✅
                   ├─ context/ sigs ──────► FrontendContractCard (context accessor sigs) ──►  SYSTEM                 [3c] ⚠️
                   ├─ AuthUser fields ─────► ✗✗✗ NOTHING — non-exported interface,            —                     [4b] ❌ THE GAP
                   │                          matched by no card, not under types/ ─────────►  (invented → Theme F)
                   └─ shadcn/radix ───────► UiComponentInventory (real export names) ───────►  USER · depFiles        [5] ✅

 WORKSPACE ────────┬─ prior-layer files ──► TypeScriptExportRegistry (existence catalog) ──►  USER · depFiles        [9] ✅
                   └─ export KIND ────────► ✗ registry omits default-vs-named ─────────────►  (guessed)             [9b] ❌ blind spot
```

### §6b foundation-feature context channels (committed `4df3761`, added after this map was first drawn)

The original map delivered foundation context through **one** channel — `FoundationContractCard` as the
leading SYSTEM block (row 6). The `foundation_features` work (`foundation-feature-manifest-plan.md` §6b,
task 9) added **two more** context channels; both are now committed and folded into the rows above:

| # | Channel | Origin → slot | Mechanism | What it adds |
|---|---|---|---|---|
| §6b-A | **`foundation_features` declaration** | `ARCHITECTURE.json` (planner) | `arch_outline.txt` OUTLINE RULE 4 + `ArchitectureSpec.foundationFeatures` | records *which* foundation features this project consumes + per-file `imports_from` coupling — the plan now states the coupling instead of it being inferred from the registry |
| §6b-B | **`consumes_foundation` enrichment edge** (row 2) | Enrichment → USER `featureContext` | `{{foundationFeaturesSection}}` in `feature_enrichment.txt` → `FeatureCard.consumesFoundation` → rendered in `toPromptSection` | tells each feature's generation *which fenced handles it consumes* and to look them up VERBATIM in the foundation block |
| §6b-C | **`FOUNDATION LOOKUP` rule** (row 6) | static SYSTEM prompt | rule in `file_generate_{frontend,backend}.txt` | directs the LLM to import/call the fenced foundation block VERBATIM and never invent a variant — the **obedience lever** the prose block lacked |

**Relationship to Theme F.** §6b-C attacks the *obedience* half of Theme F (`AuthUser` was
shown-but-ignored) by explicitly ordering VERBATIM use of the fenced block. It does **not** close the
*field-level* half — the structured, field-for-field injection of foundation model shapes ([4b] / task 3)
is still open. So Theme F is now **partially** addressed at the prompt layer; the derive-step fix remains.

### The single blocker for "never fails again"

Everything an LLM component/page consumes is delivered **except [4b] foundation-model internal fields** (`AuthUser` — Theme F, the last-run failure) and, secondarily, **[4c]** nested/non-`export` types missed by the non-recursive WIRE TYPES scan. Both are *absence* bugs, fixable deterministically:

- **[4b] fix:** inject the foundation identity/model interfaces (`AuthUser`, shell/context model types) **field-for-field** into the SYSTEM prefix. **CORRECTION (see task 3, revised):** `AuthUser` is **already** documented in `FOUNDATION_CONTRACT.md` (`{ username; role }` + "no email/name"), already parsed by `FoundationSymbolRegistry`, and already injected as prose — so Theme F is **shown-but-ignored**, not pure absence, and the fix is a **structured, field-for-field injection sourced from `FoundationSymbolRegistry`** (the OCP-compliant derived channel), **not** a hardcoded worker-side read of `context/AuthContext.tsx` (that would re-create the seam task 9 removes).
- **[4c] fix:** make `ApiContractCard.readDerived` recursive (`Files.walk` over `types/**`).

`[4d]` (invented DTOs, the ~41-error bulk) is **not** an absence bug — the fields aren't missing, a competing rich contract wins. That stays owned by **Solution A** (plan-time reconciliation); injection alone can't cure it.

> **Reading rule for the map:** a Card carries **shape** (fields/signatures) into the SYSTEM prompt; a registry carries **existence** (name/path) into the USER `depFiles`. A category is only fully covered when its shape *and* its existence are both present. [4b] fails because its shape is carried by nothing — the one row where a consumer has neither.

### Every one of the 54 errors, mapped to a map row

| Theme | Errs | Map row | Delivered? | Root class |
|---|---|---|---|---|
| A — invented `ProductDto` fields | 20 | [4a] | yes (full) | **redundant/contradictory context** → Solution A |
| B — `category`/`brand` objects + invented types/getters | 12 | [4a]/[4d] | partial | invented/contradiction → Solution A |
| C — invented order type names | 5 | [4a]/[4d] | yes | naming drift → Solution A |
| D — `getAllProducts: void` cascade | 4 | **[4e]** | wrong artifact passed as truth | **degraded-artifact gap** → Solution A leg 2 |
| E — export default/named | 5 | **[9b]** + worker | 3× kind guessed, 2× worker bug | **export-style gap** (3) + codegen bug (2) |
| F — `AuthUser` fields | 4 | **[4b]** | no — absent everywhere | **delivery gap** |
| G — missing modules (`cn`, `paymentHooks`, cart) | 4 | worker + [9] | 2× worker strip, 2× ErrorFix | codegen bug (2) + ErrorFix (2) |

### Closure checklist — the path to 0 errors from context gaps or redundant context

Split by whether the cause is **context** (your target) vs **not context** (worker codegen / build-precheck — listed so nothing is silently out of scope).

**A. Context-attributable — close ALL of these for 0 context errors (48 of 54):**

| Gap | Map row | Fix | Errors closed | Status |
|---|---|---|---|---|
| Foundation-model fields absent | [4b] | Inject `AuthUser` + shell/context model interfaces **field-for-field** into SYSTEM prefix (edit-3-redirected) | F = **4** | ✅ **DONE (2026-09-13, task 3)** — all three legs: (1) obedience lever `4df3761`; (2) foundation `export interface AuthUser` pushed to `main`; (3) `FoundationSymbolRegistry.renderFrontendModelShapes()` emits the FRONTEND-layer models field-for-field into the generation prompt (injected in `FrontendGeneratorNode` after the prose block). 10/10 registry green |
| Nested / non-`export` types missed | [4c] | Make `ApiContractCard.readDerived` recursive (`Files.walk` over `types/**`) | 0 seen, latent | ✅ **DONE (2026-09-13, task 4)** — `Files.walk` over `types/**` + `services/**`, dir-relative keys; 6/6 green |
| Export kind not carried | [9b] | Registry catalog records **default vs named** per module; feed it to import rendering | E-siblings = **3** | ✅ **DONE (2026-09-13, task 5)** — `toImportCatalog()` renders `default X` / `{ X }`; needs worker image rebuild to ship |
| Degraded artifact passed as truth | [4e] | **Solution A leg 2** — re-align FE contracts to the *regenerated* backend after `BackendValidationNode` (so `void` never reaches the FE as truth) | D = **4** | ✅ **DONE (2026-09-13, task 6)** — re-derivation already post-validation via node order @8→@9→@10 (verified; no degradation recurred). Plan/enrichment re-alignment leg folds into task 1 |
| Redundant/contradictory rich contract | [4d] | **Solution A** — pair BE DTO ↔ FE type by name at plan time; drop invented sibling types with no producer | A+B+C = **37** | TODO — the big lever |
| SDK block on components (redundant) | — | **DONE** (edit 1) — no LLM consumer, removed | prevents recurrence | ✅ applied |
| Planned-vs-actual fork (redundant) | — | **DONE** (edit 2) — planned card dropped off normal path; actual wins | prevents recurrence | ✅ applied |

**B. NOT context — still block a clean run, must be fixed separately (6 of 54):**

| Cause | Errors | Fix |
|---|---|---|
| `RouteManifestGenerator.emitAppRoutes` default-imports named exports + `allowedRoles`/`roles` | E = 2 | worker codegen fix |
| `FoundationRefReconciler` strips `components/cart/*` without repairing `CartPage` import | G = 2 | worker codegen fix |
| ordinary missing import (`cn`) / ungenerated `paymentHooks` | G = 2 | `ErrorFixAgent` — **but the build must survive the `NotFoundPage` precheck to reach it** |

**Bottom line for "0 context errors":** close the six rows in table A. Two are already done (edits 1–2). The remaining four — **[4b] foundation fields, [4c] recursive scan, [9b] export kind, and Solution A ([4d]+[4e])** — are the complete context-gap set. Solution A is doing the heaviest lifting (41 of the 48). The 6 rows in table B are real but are **codegen / pipeline** bugs, not context — they need their own fixes and cannot be closed by anything in the provenance map.

### Post-simplification: backend vs frontend (Appendix B view, after the changes)

The simplification thesis — **one wire truth, derived, LLM as pure consumer** — replaces reconciliation with prevention. Shown in the same two-column form as Appendix B, so the before/after is directly comparable:

```
                     generateFileContent(path, fileRole, depFiles, existingContent, sharedContext, featureContext)
                                                          │
              ┌───────────────────────────────────────────┴───────────────────────────────────────────┐
              │                                                                                         │
        ┌─────▼──────┐  BACKEND (unchanged — LLM authors ALL .java)          FRONTEND (simplified)   ┌─────▼──────┐
        │ SYSTEM     │  file_generate_backend.txt                                                     │ SYSTEM     │  file_generate_frontend.txt
        │ PROMPT     │  + sharedContext:                                                              │ PROMPT     │  + sharedContext (cache order):
        │            │      • foundationContract                                                      │            │      • foundationContract              (leads)
        │            │      • backendContractCard (reconciled BE contract)                            │            │      • WIRE TYPES — FULL DTOs = THE ONE  ◄── [4b] now also carries
        │            │                                                                                │            │        wire truth (+ foundation models      foundation model fields
        │            │                                                                                │            │        AuthUser field-for-field)            (AuthUser) field-for-field
        │            │                                                                                │            │      • route card
        │            │                                                                                │            │      • plannedProps (same-layer siblings only)
        │            │                                                                                │            │      • frontendContractCard (actual hook+ctx sigs, trailing)
        │            │                                                                                │            │      ✗ FrontendPlannedContractCard — DELETED (actual over planned)
        │            │                                                                                │            │      ✗ SDK / service block — DELETED (mechanical hooks)
        │            │                                                                                │            │      ✗ Solution A reconcile — NOT NEEDED (no 2nd source)
        └────────────┘                                                                                └────────────┘
        ┌────────────┐  USER PROMPT (user/file_content.txt)                                           ┌────────────┐  USER PROMPT (same template)
        │ featureCtx │  = FeatureCard.buildFeatureContext        ── IDENTICAL MECHANISM ──            │ featureCtx │  = FeatureCard.buildFeatureContext — UI/FLOW intent
        │            │                                                                                │            │      ONLY (✗ no data shapes → no 2nd wire source)
        │ fileRole   │  = FileContractCard.render(spec, role)    ── IDENTICAL MECHANISM ──            │ fileRole   │  = FileContractCard.render — PURE CONSUMER
        │            │                                                                                │            │      (JSX + local state; no type decls, no service calls)
        │ depFiles   │  = REAL DEPENDENCY FILE BODIES                                                 │ depFiles   │  = EXISTENCE registries:
        │            │      • importsFrom / dependsOn bodies                                          │            │      • UiComponentInventory (shadcn/radix)
        │            │      • + RECONCILED interface stamped                                          │            │      • TypeScriptExportRegistry  ◄── [9b] now carries
        │            │      • + auto-resolved class mentions                                          │            │        (names/paths + export KIND)   default-vs-named
        └────────────┘                                                                                └────────────┘
             LLM authors EVERY layer → needs full bodies    ◄──────►    LLM authors ONLY components/pages → consumes ONE derived truth
```

**What the simplification changed vs Appendix B:**
- **Deletions, not additions.** `FrontendPlannedContractCard`, the SDK block, and the whole Solution-A reconciliation engine are gone. The frontend `sharedContext` is now **foundation + one wire truth + routes + props + actual sigs** — five blocks, none of which can contradict another (there is no planned twin left to disagree with).
- **One wire truth.** WIRE TYPES is now the *sole* source of DTO/model shape — the plan/enrichment no longer emits a data model, so the 41-error contradiction class cannot form. This is why "no 2nd source" appears where Solution A used to.
- **Foundation models folded into the truth** ([4b]) and **export-kind into the registry** ([9b]) — the two absence gaps closed at the derive step, not with new cards.
- **The backend is untouched** and the two sides are now cleanly *dual*: backend = LLM authors every layer, so it needs full dependency bodies; frontend = LLM authors only components/pages as pure consumers, so it needs exactly one derived truth + existence. Same goal (bind to real symbols), each sized to what its LLM actually writes.

**Invariants preserved (do NOT move into per-file scope):** full DTO shapes, actual hook signatures, and the `TypeScriptExportRegistry` export catalog stay **global** — they are the existence/shape net that makes edit #3's scoping safe. See the "imports not in `import_from`" table above.

> **Cross-reference — these three edits do NOT include Solution A, and do NOT fix Themes A–D.** Solution A (§ *Cross-layer wire-contract reconciliation*, above) is a **separate, still-required** fix that operates at **plan time** (`ProjectPlanningNode`, after `ContractReconciler`, + post-backend re-alignment), reconciling the planned frontend type to the backend DTO in `ARCHITECTURE.json` so a component is never *authored* against a shape the backend doesn't serve. The three edits here are **generation-time context** changes only. The one point of overlap is **edit #2**: dropping `FrontendPlannedContractCard` removes the conflicting rich FE type from the **prompt** — but that is only the prompt-level half of the contradiction; the plan/enrichment can still carry the rich model, which only Solution A removes at the source. Consequently the **invented-DTO-field class (Themes A–D, ≈41 of 54 errors) is owned by Solution A, not by these three edits.** They are complementary: the three edits cut contradiction/dilution and close the sibling-component seam; Solution A cures the cross-layer wire drift.

---

## Appendix D — Implementation task list (simplification sequence)

The ordered work to reach a reliable run, derived from the simplification thesis in Appendix C (*one wire truth, derived, LLM as pure consumer*). **Status: not started** (edits 1 & 2 from Appendix B's "three edits" are already applied + unit-verified; the tasks below are the remaining sequence).

Ordered by leverage (errors closed per effort). Tasks 3–6 are independent and parallelizable; 7 depends on 1 & 3; 8 is orthogonal but table-stakes.

| # | Task | Closes | Errors | Type | Key files |
|---|---|---|---|---|---|
| **1** | **Strip data-model shapes from plan/enrichment** — plan/enrichment describes feature/flow/UI intent ONLY, never data shapes; backend-derived WIRE TYPES becomes the sole DTO source. Prevents the 2nd-source contradiction ⇒ **obviates Solution A**. | [4d] | ~41 (A/B/C) | simplification | `ENRICHMENT.json` gen + enrichment prompt; `ProjectPlanningNode` |
| **2** | ✅ **DONE (by edit 2) — the fork is neutralized; the class is intentionally KEPT as a parser-gap fallback.** Task 2's *goal* (kill the planned-vs-actual contradiction fork) is achieved: edit 2 dropped `FrontendPlannedContractCard` from the normal prompt path and gated it to fire ONLY when derivation produced no wire types (`FrontendGeneratorNode:713` — `contractCard == null \|\| !contractCard.hasWireTypes()`). It therefore never coexists with an actual contract, so it can no longer contradict one — the fork is impossible. The literal "delete the class" step is deliberately **NOT** done: as gated it is a harmless parser-gap safety net (the only type contract the frontend LLM gets when `ApiArtifactGeneratorNode` extracts no endpoints from a validated backend), and removing it is pure subtraction with a small downside and zero fork-elimination gain (the doc's "delete" framing predates the gating that made it safe). | contradiction fork | — | deletion | `FrontendGeneratorNode`, `FrontendPlannedContractCard` |
| **3** | **Surface foundation model shapes field-for-field — via `FoundationSymbolRegistry` (OCP), NOT a hardcoded read** *(revised; unblocked — task 9 onboarding half now done)* — `AuthUser` is ALREADY in `FOUNDATION_CONTRACT.md` (`{ username; role }` + "no email/name"), already parsed by `FoundationSymbolRegistry`, already injected as prose → Theme F was **shown-but-ignored**, not absence. Inject the registry's model shapes **structured/field-for-field** (stronger than prose) + a prompt bind; if a model is missing, add it to the contract (extend the foundation). A hardcoded worker-side disk read would re-create the exact seam task 9 removes. ✅ **DONE (2026-09-13):** all three legs shipped — (1) *obedience* lever `4df3761` (`FOUNDATION LOOKUP` §6b-C + `consumes_foundation` §6b-B); (2) foundation `export interface AuthUser` pushed to `main` (was the `[4b]`/#1 defect); (3) *field-level* structured injection — new `FoundationSymbolRegistry.renderFrontendModelShapes()` emits the FRONTEND-layer fenced models as TS declarations (`interface AuthUser { username: string; role: string }`) into the frontend generation SYSTEM prompt (wired in `FrontendGeneratorNode` right after the prose contract; backend DTOs excluded — they arrive as derived WIRE TYPES). 10/10 registry (+1 render case) + 10/10 generator green. **Uncommitted; needs worker image rebuild.** | [4b] ✅ | 4 (F) | derive-step | `FoundationSymbolRegistry`, `FrontendGeneratorNode` |
| **4** | ✅ **DONE (2026-09-13) — `ApiContractCard.readDerived` now recursive ([4c]).** Swapped `Files.list` → `Files.walk` (regular-files + `.ts` + `// GENERATED` marker filter kept), covering **both** `types/**` and `services/**`; keyed by the dir-relative path so a nested file (`types/admin/orderSummary.ts`) can't collide with a same-named top-level one and the label stays informative. Unmarked nested files still excluded (not laundered). 6/6 tests green (+1 recursion case). **Uncommitted; needs worker image rebuild.** | [4c] | 0 latent | derive-step | `ApiContractCard` |
| **5** | ✅ **DONE (2026-09-13) — export kind surfaced in the import catalog ([9b]).** The registry already *captured* `Binding` (DEFAULT/NAMED); the gap was the prompt catalog omitting it, so the model guessed the import form. `toImportCatalog()` now renders `default X` / `{ X }` per module (defaults before named); `FrontendGeneratorNode`'s catalog header explains the notation. The post-gen `TypeScriptImportFixer` already consumes `resolveBinding`, so prevention + repair now reinforce. 10/10 registry (+ mixed-binding case) + 4/4 fixer + 10/10 generator green. **Uncommitted on `feature/env-typed-defaults`; needs a `discovery-worker:latest` image rebuild to reach the pipeline.** | [9b] | 3 (E) | derive-step | `TypeScriptExportRegistry`, `FrontendGeneratorNode` |
| **6** | ✅ **DONE (2026-09-13) — re-derivation already in place via node ordering; verified.** The deterministic half of this task is satisfied by the existing pipeline order: `@Order(8) BackendValidationNode` (compile + ErrorFixAgent → backend is truth) → `@Order(9) ApiArtifactGeneratorNode` (re-derives `types/`+services+hooks from that backend; javadoc: *"Runs AFTER BackendValidationNode… Idempotent: re-derives… on every attempt"*) → `@Order(10) FrontendGeneratorNode`. Live attempt-1 log confirmed ErrorFixAgent ran inside @8 before @9 derived. Verified on `worker-1f470904`: the degraded class did **not** recur (`getAllProducts(): Promise<ProductDto[]>`, not `void`). **Note:** this run's `useProducts(arg)` arg-count (#16) is the frontend *inventing* a filter param → owned by **task 1**, not a degraded artifact. The *plan/enrichment re-alignment* leg of the doc's Leg 2 folds into task 1 (and edit 2 already dropped the planned FE card from the prompt), so no separate work remains here. | [4e] | 4 (D) | derive-step | `ApiArtifactGeneratorNode`, orchestration order |
| **7** | **Constrain components to pure consumers + retire dead patchers** — prompt/scaffold: JSX + local state only, no type decls / service calls / contract authoring. Then delete patchers whose root is now upstream (`RowActionContractNormalizer`, `EnumValueImportPatcher`, `SiteConfigAccessPatcher`, parts of `AdminLayoutWrapperPatcher`) — verify each dead first. *Depends on 1 & 3.* | prevents recurrence | — | simplification | `file_generate_frontend.txt`; the named patchers |
| **8** | ✅ **DONE — `NotFoundPage` precheck fixed at the foundation.** `NotFoundPage.tsx` is committed (`592541a`) + pushed to `origin/main`; the worker clones it, so the route-manifest precheck now passes and the build reaches `ErrorFixAgent`. Shipping from the foundation also sidesteps the planner-mis-file root. *Verify on next run (prakash predated the commit).* | enables ~6 residual | — | pipeline | `webapp-foundation` |
| **9** | ✅ **DONE — foundation seams now derive from a manifest (onboarding half).** All three hardcoded `Set`s deleted: `FOUNDATION_CONTROLLERS`, `GUARD_NAMES`+fenced-name sets, `AUTH_KEYS`/`NON_NAV_KEYS` now project from `FoundationManifest` (`DEFAULT` reproduces the old constants; `load(Path)` honours a foundation-shipped `foundation.manifest.json`). Planner ingests the feature list via `ARCHITECTURE.json.foundation_features` + enrichment `consumes_foundation` + `FOUNDATION LOOKUP` generator clause. Regression-proven each projection == old literal set (`FoundationManifestTest` 8/8). **Per-project *pruning* deferred** (out of Task 9 scope). See `foundation-feature-manifest-plan.md`. | OCP compliance | — | simplification | see OCP detail below |
| **10** | **Emit shared contracts as imported artifacts (contract-as-artifact)** — deterministic replacement for `PlannedComponentPropsCard`. Emit each component's props interface ONCE as a fenced artifact both sides `import` (referencing DTOs by import), so producer + consumer bind to one definition and `tsc` enforces agreement — silent cross-file drift becomes a localized compile error. *Depends on 8 (build must run to enforce); composes with 1 & 7.* | drift → compile error | prevents cascade | simplification | new props-artifact generator; `PlannedComponentPropsCard` → generator; `FrontendGeneratorNode`. See Appendix F |
| **11** | ✅ **DONE (2026-09-13) — hook contracts delivered by the generator, not re-parsed.** `FrontendHookGenerator` now embeds its exact signature in each derived hook file as `// @hook-contract useX(params): {return}` (the canonical shape it already computes at emission); `FrontendContractCard.extractHookSignatures` reads those verbatim and skips the lossy `HOOK_DECL` regex when present. Embedding it in the file (vs cross-node `WorkerContext` plumbing) means the disk-scan rebuild paths — resume + ErrorFixAgent — get the authoritative contract for free. Closes the query-hook-param weak point (the sidecar carries `useGymClass(classId: number): …` exactly). 14/14 generator + 33/33 card + 10/10 registry green. **Uncommitted on `feature/env-typed-defaults`; needs a worker image rebuild to reach the pipeline.** | hook-signature fidelity | — | simplification | `FrontendHookGenerator`, `FrontendContractCard`, `ApiArtifactGeneratorNode` |
| **12** | ✅ **DONE — `emitAppRoutes` guards fixed (40/40 tests).** Real cause: it imported a **phantom `ProtectedRoute`** (foundation never shipped one) + default `siteConfig`. Now uses the foundation's real **`RequireAuth`/`RequireAdmin`** (default exports, `children`-based) + **named `siteConfig`**; flags key on the guards; prompt rule 3 rewritten (foundation owns the guards). | Theme E | 2 (+1) | codegen | `RouteManifestGenerator`, `FrontendGeneratorNode`, prompt |
| **13** | ✅ **DONE — `FoundationRefReconciler` cart fence narrowed (10/10 tests).** The bare `"/cart/"` fence matched app UI at `components/cart/` and stripped `CartItemsTable`/`CartSummary`. Narrowed to **`"/src/cart/"`** (fence the foundation spine only); app cart UI survives + consumes `@/cart`. | Theme G | 2 | codegen | `FoundationRefReconciler` |
| **14** | ✅ **DONE (2026-09-13) — shadcn-only + lucide rule enforced.** `file_generate_frontend.txt`: rule 8 rewritten shadcn-**only** (no radix fallback; banned native interactive primitives `<button>/<input>/<select>/<textarea>/<dialog>` with their shadcn replacements named; added the Calendar `initialFocus` gotcha → #3); rule 11 rewritten to lucide-**only** (bans react-icons/heroicons/etc.), real-names-only with a brand/social-icon caveat (Instagram/Facebook often absent → #8), and "use SPARINGLY." `UiComponentInventory.toPromptSection()` no longer offers the raw `@radix-ui/*` FALLBACK block (radix still parsed for JSON + the import rewriter, just never shown). 3/3 inventory (+suppression case) + 10/10 generator + 8/8 lucide green. **Uncommitted; needs worker image rebuild.** | UI consistency | — | prompt+context | `file_generate_frontend.txt`, `UiComponentInventory` |

### Task 9 detail — Open/Closed for foundation features  ✅ ONBOARDING HALF DONE (2026-09-07)

> **Status:** the derive-don't-hardcode *onboarding* half is **built + unit-verified** on branch
> `feature/env-typed-defaults` (uncommitted). The per-project *pruning* half is designed but
> **deferred** (was always the plan's own extension, never part of Task 9's ask). Full design +
> completion mapping: `docs/foundation-feature-manifest-plan.md` (§7 Gap coverage).

**Already OCP-compliant:** `FoundationSymbolRegistry` (`util/FoundationSymbolRegistry.java:83-87`) **derives** fenced symbols by parsing `backend/` + `frontend/FOUNDATION_CONTRACT.md` from the cloned foundation — a new fenced type/interface is auto-ingested with zero pipeline edits.

**Hardcoded seams that VIOLATED OCP** — all three now derive from `FoundationManifest`, hardcoded `Set`s deleted:

| Seam | Was (hardcoded) | Now (RUN-ACTIVE manifest projection) |
|---|---|---|
| `FOUNDATION_CONTROLLERS` (skip set) | `util/ApiInventory.java:53` | ✅ resolved in `extract()` → `FoundationManifest.active().foundationControllers()` |
| `GUARD_NAMES` (+ `FENCED_BACKEND/FRONTEND_NAMES`) | `util/FoundationRefReconciler.java:81` | ✅ accessors `guardNames()` / `fencedBackendNames()` / `fencedFrontendNames()` → `FoundationManifest.active()` |
| `AUTH_KEYS` / `NON_NAV_KEYS` (route gates) | `util/RouteManifest.java:50,53` | ✅ accessors `authPageKeys()` / `nonNavPageKeys()` → `FoundationManifest.active()` |

> **Disk-manifest → seam wiring closed (Gap 1).** The seams originally read `FoundationManifest.defaultManifest()` in `static final` fields resolved at class-load — so a foundation-shipped `foundation.manifest.json` was *loaded and logged but ignored* by the seams. Now `ProjectPlanningNode` calls `FoundationManifest.activate(load(workspace))` right after the clone, and every seam reads the **run-active** manifest via `active()`. A foundation that ships a manifest declaring a new controller/guard/gated page actually drives the seams — no Java edit. One project per worker process makes the process-scoped active manifest correct. Proven by `ApiInventoryTest.extract_projectsFromActiveManifest_notJustDefault` (activate a custom manifest → the seam skips the newly-declared controller).

**Fix (derive-don't-hardcode, one source of truth) — all three steps shipped:**
1. ✅ Foundation is the single source — `FoundationManifest` (`DEFAULT` reproduces today's constants; `load(Path)` honours a foundation-shipped `foundation.manifest.json` with graceful fallback). Card-sync discipline added to `webapp-foundation/CLAUDE.md` + pipeline `FoundationCardIntegrity` sanity check.
2. ✅ The three hardcoded sets derive from the manifest at run start (loaded inline in `ProjectPlanningNode` beside `FoundationSymbolRegistry.buildFromWorkspace`, then `activate()`d as the run-active declaration the seams read); **hardcoded `Set`s deleted**. Regression-proven each projection == old literal set (`FoundationManifestTest` 8/8) + the disk-manifest→seam wiring proven by `ApiInventoryTest.extract_projectsFromActiveManifest_notJustDefault`.
3. ✅ Planner ingests the feature list — `ARCHITECTURE.json.foundation_features` (§6b Part A) + enrichment `consumes_foundation` edge (Part B) + `FOUNDATION LOOKUP` clause in both `file_generate_*.txt` (Part C, shipped).

**Outcome (achieved for onboarding):** adding a foundation feature = append to `foundation.manifest.json` + its `##` contract-card section — a foundation-side change only; every seam adapts with zero pipeline `Set` edit.

**Hardening (not part of Task 9's ask) — now DONE:** the `FoundationRefReconciler` planning-time cross-check (*file imports a fenced symbol ⟺ its feature is in `foundation_features`*) is wired — `crossCheckFoundationRefs(spec)` runs before `reconcile` in `ProjectPlanningNode`, WARNs on any non-core dangling foundation reference (attribution via `FoundationManifest.owningFeatureId`), advisory-only, 6 tests green. **Still pending:** no end-to-end run has yet confirmed `foundation_features` populates live (unit-verified only). **Deferred (this plan's extension, not Task 9):** the per-project pruning half (`FeaturePruneNode`, guardrails, config-surface strip).

**Codegen bugs (now tracked as tasks 12 & 13):** `RouteManifestGenerator.emitAppRoutes` default-imports named exports + `allowedRoles`/`roles` (E, 2); `FoundationRefReconciler` strips `components/cart/*` without repairing `CartPage` import (G, 2). Deterministic, recur every project — no longer just notes.

**Readiness / recommended order.** Task 8 is ✅ done (foundation ships `NotFoundPage`). The **first executable batch is tasks 12 + 13** (route/cart codegen) — with 8 done, these let a run *reach* `ErrorFixAgent` and produce **real** post-fix data. The plan is otherwise built on ONE run that died early (unrepaired output), so run once after 12+13 to measure the true residual **before** the biggest/riskiest change (task 1, enrichment data-shape strip). Then tasks 3/4/5/6 (deterministic derive-step, ~11 errors) — task 3 now unblocked (task 9's onboarding half done) — then 1/2/7/10/11 (structural); task 9 ✅ done (pruning half deferred), 14 orthogonal.

**Coverage:** tasks 1 + 3 + 5 + 6 close the 48 context-attributable errors (with edits 1–2 already applied); tasks 2 + 4 + 7 are structural/hardening; task 8 + the two codegen fixes above clear the remaining 6.

---

## Appendix E — Same-layer parallel generation: how it holds together

Components in one layer generate **in parallel** (`FrontendGeneratorNode`, up to `MAX_PARALLEL_PER_LAYER = 5`). If B imports A, B cannot see A's *live* output — registration is deferred until after the parallel block (`:758`). Consistency is held not by ordering but by **both sides binding to the same static plan entry**, with a post-gen path reconcile as the safety net. Invariant: components interact via **props only** (task 7).

```
 ── BEFORE THE LAYER — built once from ARCHITECTURE.json (static, cached) ─────────────
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │  PlannedComponentPropsCard — the SINGLE shared contract (all components)       │
 │    @/components/A : { items: Dto[]; onSelect: (i: Dto) => void }   ◄─ A's truth │
 │    @/components/B : { ... }                                                     │
 └──────────────────────────────────────────────────────────────────────────────┘
                 │ injected IDENTICALLY into every prompt in the layer
        ┌────────┴─────────┐
        ▼                  ▼
 ── PARALLEL (same layer, ≤5 at once) — A and B run simultaneously ────────────────────
 ┌─────────────────────────┐        ┌─────────────────────────────────────┐
 │ generate A              │        │ generate B  (renders A)             │
 │  reads plannedProps[A]  │        │  reads plannedProps[A]              │
 │  → DECLARES those props │        │  → <A items={..} onSelect={..} />   │
 └───────────┬─────────────┘        └───────────────┬─────────────────────┘
             │   ✗ neither sees the other's live output                     │
             │     (registry + FrontendContractCard NOT updated mid-layer)  │
             │   ✔ both pinned to the SAME plannedProps[A]                  │
             │     → A (declares) and B (renders) AGREE by construction     │
             └───────────────────────────┬─────────────────────────────────┘
                                         ▼
 ── AFTER THE PARALLEL BLOCK — batch reconcile ────────────────────────────────────────
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │  register A, B  → TypeScriptExportRegistry + FrontendContractCard (now actual) │
 │  TypeScriptImportFixer → fix B's import PATH of A against the complete registry│
 └──────────────────────────────────────────────────────────────────────────────┘

 ── INVARIANT (task 7): components are PURE CONSUMERS — interact via PROPS ONLY ───────
    A↔B edge  ==  props edge  ==  fully carried by PlannedComponentPropsCard   ✔ safe
    ✗ B imports a TYPE / HELPER / CONST from A  → not in plannedProps, mid-layer race
      returns → FORBIDDEN by task 7 (such shared symbols live in a types/ or hook file,
      i.e. a PRIOR layer, already on disk).
```

**Why it holds — three guarantees:**
1. **Single source, both sides.** `PlannedComponentPropsCard` pins the producer (A *declares*) and the consumer (B *renders*) to the *same* static entry — so they agree with each other regardless of who finishes first. Consistency is by construction, not by generation order.
2. **Paths self-heal after the layer.** The registry is stale mid-layer, but `TypeScriptImportFixer` runs post-block against the now-complete registry, correcting any guessed `@/` import path.
3. **The props-only invariant makes the sibling edge fully expressible.** Task 7 guarantees the only A→B coupling is props — which `PlannedComponentPropsCard` carries in full. Any non-prop coupling (a shared type/helper) must live in an earlier layer (types/hook file), already on disk, so it never races.

**Where it would break (and the guard):** if a component exported an internal symbol a sibling consumed, parallel generation would race and no card could win — which is exactly the coupling task 7 forbids. The dependency-topological alternative (serialize A before B for *actual* contracts) is only needed if that invariant can't be held; it costs parallelism and a reliable render graph, so it's the fallback, not the default.

---

## Appendix F — Contract as artifact vs contract as card (task 10)

The reliability ceiling of Appendix E is that `PlannedComponentPropsCard` hands **each side a copy of the contract to re-author from the prompt** — two authored copies that *can* diverge. One divergence in a shared contract cascades across every file that imports it. Task 10 removes the possibility structurally: emit the contract **once** as an artifact both sides `import`, so the **compiler** binds them, not the model.

```
 ── TODAY: CONTRACT AS CARD (prompt-steered → drift possible) ─────────────────────────
 ARCHITECTURE.json ──► PlannedComponentPropsCard (PROMPT TEXT: "A: { item: ProductDto }")
                         │  injected into both prompts
        ┌────────────────┴────────────────┐
        ▼                                  ▼
   generate A                         generate B
   RE-AUTHORS  interface AProps        RE-AUTHORS  <A item={..} />
     { item: ProductDto }               against its OWN idea of AProps
        └──────────► TWO authored copies ◄──────────┘
                     can diverge → SILENT drift → cascades across importers

 ── TASK 10: CONTRACT AS ARTIFACT (compiler-bound → drift impossible to ship silently) ─
 ARCHITECTURE.json ──► props-artifact generator ──► frontend/src/.../AProps.ts   (ONE definition, FENCED)
                                                      │   import { ProductDto } from '@/types'
                                                      │   export interface AProps { item: ProductDto; ... }
        ┌─────────────────────────────────────────────┼─────────────────────────────────┐
        ▼                                              ▼                                   │
   generate A                                     generate B                              │
   import { AProps }                              import A                                 │
   const A: React.FC<AProps> = ...                <A item={..} />  ── typed vs AProps ─────┘
   (IMPLEMENTS; never declares the contract)      (usage checked against the SAME AProps)
        └──────────────► ONE definition, both import ◄──────────────┘
                          tsc BINDS producer + consumer
                          deviation = LOCALIZED compile error, never silent drift

 ── WHERE THE DTO IMPLEMENTATION COMES FROM (the prop's field-level truth) ─────────────
   AProps.ts     ── import ──►  @/types/product.ts  (ProductDto = { id; name; price; … })   ◄─ ONE DTO def
        ▲                              ▲
        │ compile-time: TS resolves    │ generation-time: LLM sees the fields via
        │ ProductDto from the real     │ ApiContractCard WIRE TYPES (reads the SAME
        │ on-disk file                 │ types/*.ts verbatim into the SYSTEM prompt)
   NOT a registry — registries carry name/path only; the FIELDS come from the WIRE TYPES card.
```

**What this locks down:**
- **The LLM never authors a shared contract** — only the JSX/logic *body*. Contract = artifact; implementation = LLM. The only thing that can vary is the body, and `tsc` checks the body against the imported interface.
- **DTO implementation is resolved through imports, not restated.** `AProps` imports `ProductDto`; the component imports `AProps`; there is exactly **one** `ProductDto` definition (`@/types/product.ts`). At generation time the LLM sees `ProductDto`'s fields via **`ApiContractCard` WIRE TYPES** (which reads that same file verbatim) — *not* via any registry (registries carry existence only). Compile time and generation time therefore point at the identical source.
- **Order-independent.** The artifact is on disk before A or B generate, so parallel siblings bind to one definition regardless of who finishes first (closes the Appendix E residual: consistency is now by *import*, not by *both obeying the same card*).

**Dependencies:** the compiler is the enforcer, so the deviation-as-error is only *caught and fixed* when the build runs (task 8) — but the shared-import structure means a disagreement **cannot be expressed silently** even before `tsc` runs. Gaps in the field-level truth feeding this chain — foundation models (`AuthUser`, task 3) and nested/non-`export` types (task 4) — must be closed for the WIRE TYPES source to be complete.

### Hook contracts — the same rule applied to TanStack hooks (task 11)

A component calling a hook needs four things — **name, location, parameters, return type**. All four are **deterministically known by `FrontendHookGenerator`** at emission (it writes them as templates), but today they reach the component via mixed, partly-lossy paths:

| Attribute | Source of truth (ground) | How the component gets it today | Reliable? |
|---|---|---|---|
| Name (`useCreateProduct`) | `HookNaming.hookFor()` (single source) | export catalog | ✅ |
| Location (`@/hooks/productHooks`) | derived path `hooks/<domain>Hooks.ts` | `TypeScriptExportRegistry` catalog (existence) | ✅ |
| Return type | canonical TanStack shape (emitted) | `FrontendContractCard` **regex re-scan** (brace-matched) | ⚠️ lossy round-trip |
| Parameters | derived service fn params (emitted) | `FrontendContractCard` regex re-scan | ⚠️ **query-hook params = weak point** |

The **canonical return shape** removes any need to re-scan it: query → `{ data: T | undefined; isLoading; isError; error }`; mutation → `{ mutate: (vars, options?) => void; mutateAsync; isPending; isError; error }` — a pure function of *(kind, dataType, varsType)*. So the reliable design (task 11) is: **`FrontendHookGenerator` delivers its own contract** (register the exact `(name, params, return, path)` at generation time, or emit a hook-contract artifact), and the component binds to *that* — never to a regex re-parse of a file the generator already authored. This matters more after **task 2** deletes `FrontendPlannedContractCard`, which makes the regex re-scan the *sole* hook-signature source.

**Registries vs source-of-truth, restated:** for hooks, the registry (`TypeScriptExportRegistry`) answers **name + location** (existence); it does **not** carry params/return. Those are *shape*, and their ground truth is the **generator**, not a registry and not a re-scan — exactly the "derive, don't re-parse" rule that makes the whole chain solid.

---

## Follow-up — Re-run `worker-1f470904` (2026-09-13)

Second end-to-end run of the **same brief** (`31c78b9a`, Prakash Stores) on the current worker image — the
first run *after* tasks 8/9/12/13 and edits 1–2 all landed. Recorded here to measure what those fixes
actually moved, against the 54-error baseline above.

### How this run ended (neither attempt completed)

- **Attempt 1** reached `FrontendValidationNode` (node 10/16) but was **killed by the 30-min container
  lifetime cap** (`MAX_CONTAINER_LIFETIME_MINUTES`) mid-`npm run build` → classified **INFRA** →
  auto-retry. Root cause was wall-clock, not correctness: `ProjectPlanningNode` alone consumed **~19.5 min**
  (outline + 15-feature enrichment + 12-feature contract reconciliation, all Pro calls with tool-reads),
  leaving too little for generation + validation.
- **Attempt 2** resumed correctly — loaded the pushed `ARCHITECTURE.json`
  (`skipGeneration=true skipEnrichment=true`), saw all 51 backend files "already done," passed backend
  validation patchers in ~16 s — then was **stopped manually** before frontend validation.

Consequence: **the `ErrorFixAgent` never engaged on the frontend this run either** (timeout, then manual
stop). So, exactly as in the original report, the errors below are **raw, unrepaired generator output** —
an apples-to-apples comparison of generator quality against the 54 baseline, *not* post-fix residual. The
doc's standing caveat — *"run once after 12+13 to measure the true residual"* — is **still unsatisfied**:
no run has yet reached the fix loop. The binding constraint this run was the lifetime cap, not context.

### Result: 54 → 17 raw TS errors, and the build actually runs

Reproduced the same way as the baseline (clone `feature/clothing-store-ecommerce-31c78b9a`, `npm ci`,
`npx tsc -b`). The `NotFoundPage` route-manifest precheck — which blocked the *entire* build last time —
**passed** (task 8 shipped `NotFoundPage.tsx` from the foundation), so `tsc` ran to completion and produced
a real list: **17 errors**.

#### Solved since the baseline ✅ (≈40 of 54 gone)

| Baseline item | Then | Now | Evidence |
|---|---|---|---|
| **Theme A** — invented `ProductDto.variants` / `additionalImages` | 20 | **0** | no `.variants`/`additionalImages` reads remain |
| **Worker bug #1/#2** — `AppRoutes` default-imports named `siteConfig`/`ProtectedRoute` (Task 12) | 2 (+latent `allowedRoles`) | **0** | `import { siteConfig }`; real `RequireAuth`/`RequireAdmin` used |
| **Theme G #46/#47** — `FoundationRefReconciler` stripped `components/cart/*` (Task 13) | 2 | **0** | `CartItemsTable.tsx`/`CartSummary.tsx` survive, imports resolve |
| **Theme G #16/#44** — `@/hooks/paymentHooks`, missing `cn` | 2 | **0** | gone |
| **NotFoundPage precheck** (fatal, blocked the build) (Task 8) | fatal | **passes** | build reached `tsc` |
| **Theme C** — invented `Order`/`OrderDetailsForPayment`/`ShippingDetails` | 5 | **1** | residual is `ShippingAddressDto` imported from the wrong module (exists in `@/types/shipping`) |
| **Theme F** — `AuthUser` profile fields (`firstName`/`lastName`/`email`/`phone`) | 4 | **1** | component now binds to `AuthUser`; residual is just "`AuthUser` not exported" |
| **Theme D** — `getAllProducts: void` → `never` cascade | 4 | **1** | `.content`/`.length`-on-`never` gone; residual is one `useProducts(arg)` arg-count |

#### The 17 errors, and the key re-classification

**Most of the 17 are wrong-import-path / post-gen-validation, NOT context gaps** — the producers exist
correctly on disk, just at a different module, and the post-gen fixers that repair this never ran:

| Error(s) | Consumer imports from | Real producer | Owner |
|---|---|---|---|
| #4/#9/#14 `useBrands` | `@/hooks/productHooks` | `@/hooks/brandHooks` ✅ | `TypeScriptImportFixer` (map [9]) |
| #10 `BrandDto` | `@/types/product` | `@/types/brand` ✅ | `TypeScriptImportFixer` (map [9]) |
| #13 `ShippingAddressDto` | `@/types/order` | `@/types/shipping` ✅ | `TypeScriptImportFixer` (map [9]) |
| #8 `Instagram` | `lucide-react` (not exported) | — | `LucideIconValidator` (post-gen) |
| #2, #12, #5 | — | — | `ErrorFixAgent` (nullable / implicit-`any`) |

`ProductForm` even carries the LLM's own hint — `// Assuming these hooks are in productHooks` — i.e. it
**guessed** the path. That's ~10 of 17 owned by fixers that were time-boxed out, not by Appendix C holes.

**The genuine context/shape residual (7 of 17):**

| # | Error | Map row | Class |
|---|---|---|---|
| 6 | `imageUrl` invented on `ProductCategoryDto` (DTO shown full, field still invented) | [4a]/[4d] | shown-but-ignored invented field |
| 15 | `ProductFilterRequest` — no producer anywhere | [4d] | invented type |
| 16 | `useProducts(filter)` — hook takes 0 args | [4e]/cascade | degraded/invented-filter cascade |
| 17 | `categories`/`brands` props not on `ProductFilterSidebarProps` | [7] | sibling prop drift |
| 1 | `AuthUser` declared but **not exported** in foundation `AuthContext` | [4b] | foundation export gap |
| 7 | `{ ProductCard }` named vs default export | [9b] | export-kind guessed |
| 3, 11 | `initialFocus` on `<Calendar>`; `useDeleteCategory` missing | [5]/hooks | foundation-component prop shape / hook coverage |

### Headline finding for context accuracy

The **invented-contract class (baseline Themes A+B+C = 37 errors, owned by Solution A / Task 1) has
collapsed to ~2–3** this run (`ProductFilterRequest`, `imageUrl`). The reconciler + mechanical hooks now
actually **generate** `ProductCategoryDto`, `BrandDto`, `brandHooks`, etc. as real producers, so what used
to be "invented with no producer" (a hard context-gap) has largely **converted into wrong-import-path
drift** — a far cheaper class owned by `TypeScriptImportFixer`. Context delivery is materially more accurate
than when this doc was first drawn; the dominant remaining failure mode is "the build didn't reach the fix
loop," not "the LLM wasn't told the shape."

### Which Appendix D tasks are relevant to this run's residual

Ordered by what actually manifested (baseline-only items omitted):

| Task | Map row | This run's error(s) | Status | Priority |
|---|---|---|---|---|
| **(prereq)** let a full run reach `ErrorFixAgent` — raise the 30-min cap or let the retry finish | — | the ~10 import-path/lucide/trivial errors | — | **P0** — measure true residual first |
| **5** — record export kind (default/named) in `TypeScriptExportRegistry` | [9b] | #7 | ✅ **DONE (2026-09-13)** — `toImportCatalog()` now renders `default X` / `{ X }` per module so the model doesn't guess the import form; prompt header in `FrontendGeneratorNode` explains the notation; 10/10 registry + 4/4 fixer + 10/10 generator tests green (uncommitted) | P1 |
| **3** — foundation model fields field-for-field / extend the contract | [4b] | #1 (`export AuthUser`) | ✅ **DONE (2026-09-13)** — all three legs: obedience lever (`4df3761`) + `export interface AuthUser` pushed to `main` + `FoundationSymbolRegistry.renderFrontendModelShapes()` field-for-field into the generation prompt; 10/10 + 10/10 green; needs worker image rebuild | P1 |
| **14** — shadcn-only + lucide rule | [5] | #8, #3 | ✅ **DONE (2026-09-13)** — rule 8 shadcn-only + no native primitives + Calendar `initialFocus` gotcha; rule 11 lucide-only/real/sparingly + brand-icon caveat; radix dropped from `UiComponentInventory` prompt; 3/3 + 10/10 + 8/8 green; needs image rebuild | P1 |
| **1** — strip data-model shapes from plan/enrichment | [4d]/[4a] | #6, #15 (+#16) | TODO — still the deepest class, **now tiny** | P2 |
| **10** — contract-as-artifact for component props | [7] | #17 | TODO | P2 |
| **11** — deliver hook contracts from `FrontendHookGenerator` | [3a]/[3b] | #11 (also hardens the useBrands path-guess) | ✅ **DONE (2026-09-13)** — `// @hook-contract` sidecar emitted by the generator, read verbatim by `FrontendContractCard`; 14/14 + 33/33 + 10/10 green; needs worker image rebuild | P2 |

**Confirmed done / not relevant this run:** Task 8 (precheck — *verified passing*), Task 12 (AppRoutes
guards — no errors), Task 13 (cart fence — components survive), Task 9 (manifest OCP), edits 1 & 2.
**Task 4** ([4c] recursive `readDerived`) is **not** implicated — `ShippingAddressDto` is exported from its
own module; #13 is a wrong import *target*, not a missed scan.

---

## Measurement run (rebuilt image) + smoke boot-death (2026-09-13)

First run on the image rebuilt with tasks 3/4/5/6/11/14 (`worker-1f470904`, a **resume** — backend reused;
frontend files were skipped as already-done, so the new component prompts did not re-author this run; the
derived hooks *were* re-emitted with the Task-11 `@hook-contract` sidecars). Key results:

- **P0 answered — `ErrorFixAgent` converges.** The frontend build reached the fix loop (NotFoundPage
  precheck passed) and **went green in ~2 min** on ~15 raw errors — the first time any run reached the loop.
  The import-path cluster + residual were all cleared; `FrontendValidationNode` completed → `SmokeTestNode`.
- **New boot-death (class #7) at smoke — MinIO.** The app crash-looped: `MinioStorageService.ensureBucket()`
  (`@PostConstruct`) calls `s3.headBucket` against host `minio`, but the generated `docker-compose.yml` had
  only `app + db` — the gallery feature's manifest `config.compose: ["minio"]` was ignored → `UnknownHostException`
  → context dies. `SmokeTestNode` has no fix loop, so it fails the gate (and it was attempt-3 → FAILED).

### MinIO boot-death fix (both legs implemented, 2026-09-13, uncommitted)
- **Leg 1 (worker, primary):** `FoundationManifest.composeServices()` (⋃ features' `config.compose`) +
  `ProjectPlanningNode.writeDockerArtifacts` now emits a canonical `minio` service + app `depends_on: minio`
  + `miniodata` volume + S3 env, gated on the workspace manifest declaring `minio`. Smoke inherits it
  (`ComposeLaunchService` launches `-f docker-compose.yml`). Compose YAML validated via `docker compose config`;
  `FoundationManifestTest.composeServices` green (the 2 `ProjectPlanningNodeTest` failures are pre-existing,
  confirmed via stash).
- **Leg 2 (foundation, defense-in-depth):** `MinioStorageService.ensureBucket()` catches `SdkClientException`
  (connectivity) → WARN + defer; bucket is ensured lazily on first `store()` (`ensureBucketNow`/`bucketReady`).
  A boot race or storage outage no longer kills the app. Foundation BUILD SUCCESS.

**Status:** needs a worker image rebuild + fresh end-to-end run to confirm smoke boots green. Note the new
component-prompt work (3/5/14) still hasn't been exercised on a *fresh* generation (this run resumed and
skipped frontend authoring) — a clean branch is needed to measure its effect on raw generation quality.
