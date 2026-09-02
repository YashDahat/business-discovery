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
