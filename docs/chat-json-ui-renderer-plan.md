# Chat JSON → UI Renderer + React Flow Diagrams — Design & Implementation Plan

**Status:** BUILT (uncommitted) — 2026-09-10. Frontend `tsc -b` + vite build green; FlowBlock code-splits
into its own lazy chunk. e2e-unverified (needs a live Cline run to confirm the LLM emits valid blocks).
**Branch:** `feature/chat-json-ui` (off `master`, per feature-branch workflow)

### Implementation notes (what actually shipped)
- **No backend DTO/persistence change.** Blocks are parsed from the message `content` string on the
  frontend (`parseMessageContent`), so live replies and loaded history render identically. Only the
  sidecar system prompt changed (`cline-sidecar/src/server.ts` → `UI_BLOCKS_PROMPT`).
- Contract + defensive zod parser: `frontend/src/components/chat/blocks/schema.ts` (+ `serializeAction`).
- Renderer/registry: `chat/BlockRenderer.tsx`, `chat/blocks/registry.tsx` (flow lazy-loaded).
- Blocks: Text/Callout/Choices/Date/Form/Fallback + `Markdown.tsx`; interactive blocks lock after answer
  and feed back via `onAction` → `sendMutation` (a tagged user turn, e.g. `[form:id] {...}`).
- Flow: `chat/flow/{FlowBlock,autoLayout(dagre),nodeTypes}.tsx`, read-only + fitView + expand-to-Dialog.
- Panel: `ProjectChatPanel.tsx` — `panelMode` docked/wide/full, header Maximize/Minimize cycle,
  localStorage persistence, capped text column (900px) when widened; full-screen drops rightOffset.
- shadcn added: `textarea, checkbox, radio-group, popover, calendar (rdp v10), form`.
- Deps added: `@xyflow/react, dagre(+@types), react-markdown, remark-gfm, react-day-picker, date-fns,
  @radix-ui/react-{checkbox,radio-group,popover}`.
**Scope:** Render Cline chat replies as interactive shadcn UI (text, forms, choices, date pickers, callouts) and React Flow diagrams, driven by a typed JSON contract, instead of plain-text-only bubbles.

---

## 1. Goal

Today the Cline project chat (`ProjectChatPanel.tsx`) renders every assistant reply as a
plain string (`ChatMessageView.content`, shown with `whitespace-pre-wrap`). We want the
assistant to be able to return **structured UI** so the chat becomes an interactive surface:

- LLM returns text → render a (markdown) text bubble (today's behaviour).
- LLM returns a form spec → render an interactive shadcn form.
- LLM wants a date → render a date picker.
- LLM wants a decision → render selectable choices.
- LLM wants to explain how a feature/project works → render a **React Flow** diagram.

Core idea: a single **JSON block contract** + a **registry-driven renderer** that maps each
block `type` to a shadcn-based component. Adding a new block type = one component + one
registry entry (Open/Closed), with a safe text fallback so a bad/unknown shape never
white-screens the chat.

### Decisions locked (from kickoff)
- **Interactivity:** Fully interactive. Form submit / choice select / date pick feed back
  into the chat as the user's next turn (`onAction` → existing `sendMutation`).
- **Flow diagrams:** Read-only, pan/zoom/fit, **auto-layout** (dagre). Cline emits
  nodes/edges only — no coordinates.
- **This doc:** Plan first, implement after review.

---

## 2. Current state (what we build on)

| Piece | File | Relevance |
|---|---|---|
| Chat panel + `ChatBubble` | `frontend/src/components/shared/ProjectChatPanel.tsx` | Render path we extend; `ChatBubble` line ~39–73 renders `message.content` as text. |
| Chat hook | `frontend/src/hooks/useClineChat.ts` | `sendMutation` (optimistic append), `stop`, `newSession`, steps polling. Reused for `onAction`. |
| Service + types | `frontend/src/services/businessService.ts` | `ChatMessageView {role, content}`, `ClineChatResult {sessionId, reply}` (line ~21–39). Contract extension lives here. |
| Backend chat | `src/main/java/com/business/discovery/api/ClineChatController.java` | Produces `reply` string. Add defensive block parse + validation here. |
| Prompt builder | `ProjectContextBuilder` / cline-sidecar server prompt | Where the response-format schema + few-shot examples go. |
| shadcn installed | `frontend/src/components/ui/` | Have: badge, button, card, dialog, input, label, select, separator. **Missing** form, textarea, checkbox, radio-group, popover, calendar. |

**Deps present:** `react-hook-form ^7.57`, `@hookform/resolvers ^5.1`, `zod ^3.25`,
`lucide-react`, `tailwindcss-animate`, `@tanstack/react-query`.
**Deps missing:** `@xyflow/react` (React Flow), `dagre` (+ `@types/dagre`) for auto-layout,
`react-markdown` (+ `remark-gfm`) for text/callout markdown.

---

## 3. The contract (UI ⇄ JSON ⇄ UI)

A discriminated union on `type`. This is the entire interface between the model and the UI.

```ts
// businessService.ts (new section: chat UI blocks)

export type UIBlock =
  | TextBlock | CalloutBlock | ChoicesBlock | DateBlock | FormBlock | FlowBlock

export interface TextBlock    { type: 'text';    markdown: string }
export interface CalloutBlock { type: 'callout'; variant: 'info' | 'warn' | 'success'; markdown: string }

export interface ChoicesBlock {
  type: 'choices'
  id: string                       // stable id, echoed back on action
  prompt: string
  options: { label: string; value: string }[]
  multi?: boolean
}

export interface DateBlock {
  type: 'date'
  id: string
  label: string
  mode?: 'single' | 'range'
}

export type FormFieldKind = 'text' | 'textarea' | 'number' | 'select' | 'checkbox' | 'radio' | 'date'
export interface FormField {
  name: string
  kind: FormFieldKind
  label: string
  placeholder?: string
  required?: boolean
  options?: { label: string; value: string }[]   // select / radio
  min?: number; max?: number                       // number
}
export interface FormBlock {
  type: 'form'
  id: string
  title?: string
  description?: string
  fields: FormField[]
  submitLabel?: string
}

// Read-only, auto-laid-out. No positions from the model.
export interface FlowNode { id: string; label: string; kind?: 'start'|'process'|'decision'|'io'|'end'; detail?: string }
export interface FlowEdge { from: string; to: string; label?: string }
export interface FlowBlock { type: 'flow'; id: string; title?: string; nodes: FlowNode[]; edges: FlowEdge[] }
```

**Message shape stays backward-compatible** — `content` remains the plaintext fallback; when
`blocks` is present the renderer uses it:

```ts
export interface ChatMessageView {
  role: 'user' | 'ai' | 'system'
  content: string        // legacy / fallback plaintext (unchanged)
  blocks?: UIBlock[]     // NEW — when present, render these
}
```

### zod validators (defensive parsing)
Mirror each interface with a zod schema and export a single
`parseBlocks(raw: unknown): UIBlock[] | null`. Used on **both** sides:
- Backend: validate what the model produced before persisting/returning.
- Frontend: validate again at the render boundary; invalid → text fallback.

---

## 4. Renderer architecture (registry, not a switch)

```
ChatBubble (assistant)
  ├─ if message.blocks?.length → <BlockRenderer blocks onAction/>
  └─ else                      → existing plaintext bubble (unchanged)

BlockRenderer
  └─ blocks.map(b => (BLOCK_REGISTRY[b.type] ?? FallbackBlock))
        └─ <TextBlock/> <CalloutBlock/> <ChoicesBlock/> <DateBlock/> <FormBlock/> <FlowBlock/>
```

```ts
// components/chat/blocks/registry.ts
export const BLOCK_REGISTRY: Record<UIBlock['type'], React.FC<BlockProps<any>>> = {
  text: TextBlock, callout: CalloutBlock, choices: ChoicesBlock,
  date: DateBlock, form: FormBlock, flow: FlowBlock,
}
export interface BlockProps<B extends UIBlock> {
  block: B
  onAction: (action: BlockAction) => void
  disabled?: boolean   // true while a turn is in flight
}
```

- Unknown `type` → `FallbackBlock` renders the raw JSON as a `callout variant="warn"` (visible,
  non-fatal). No edit to `ChatBubble`/`BlockRenderer` when adding a type — OCP, matching the
  `[[shadcn_preinstaller]]` / registry patterns already used in this project.
- Proposed file layout:
  ```
  components/chat/
    BlockRenderer.tsx
    blocks/{registry.ts, TextBlock.tsx, CalloutBlock.tsx, ChoicesBlock.tsx,
            DateBlock.tsx, FormBlock.tsx, FlowBlock.tsx, FallbackBlock.tsx}
    flow/{FlowDiagram.tsx, autoLayout.ts, nodeTypes.tsx}
  ```

### 4a. Interactivity — `onAction` round-trip
All interactive blocks emit a `BlockAction`:

```ts
export type BlockAction =
  | { blockId: string; kind: 'form_submit';   values: Record<string, unknown> }
  | { blockId: string; kind: 'choice_select';  values: string[] }
  | { blockId: string; kind: 'date_select';    value: string | { from: string; to: string } }
```

`ProjectChatPanel` passes an `onAction` that:
1. Serialises the action into a human-readable + machine-readable user turn, e.g.
   ```
   [form:trial-signup] { "name": "...", "date": "2026-09-15", "plan": "standard" }
   ```
2. Calls the existing `sendMutation.mutate(text)` — **no new endpoint**. The submission
   becomes the user's next message; Cline reads the structured payload and continues.
3. After submit, the block renders in a **disabled/answered** state (show chosen values) so
   history stays coherent and can't be double-submitted.

This keeps the whole loop on the existing `/api/v4/cline/brief/{id}/chat` pipe.

---

## 5. Block components (shadcn mapping)

| Block | shadcn / lib used | Notes |
|---|---|---|
| `text` | `react-markdown` + `remark-gfm` | Replaces raw `whitespace-pre-wrap`; supports lists/code/links. Reuse dark theme classes. |
| `callout` | `Card` + variant border/icon (lucide) | info/warn/success. Also the fallback surface. |
| `choices` | `Button` (single) or `Checkbox`+`Button` (multi) | Emits `choice_select`. Disabled after answered. |
| `date` | `Popover` + `Calendar` (react-day-picker) | single/range. Emits `date_select` (ISO). Use foundation Calendar `autoFocus` fix per `[[abs_fitness_fix_plan_f2_f6]]`. |
| `form` | shadcn `Form` (react-hook-form) + `Input`/`Textarea`/`Select`/`Checkbox`/`RadioGroup`/date | zod schema built dynamically from `fields`. Emits `form_submit`. |
| `flow` | `@xyflow/react` + dagre | See §6. |

**shadcn components to add:** `form`, `textarea`, `checkbox`, `radio-group`, `popover`,
`calendar`. Keep to shadcn-only per `[[ui_generation_shadcn_only_rule]]` (this is platform UI,
not generated client UI, but same discipline).

---

## 6. React Flow diagrams (read-only + auto-layout)

- **Lib:** `@xyflow/react` (the current React Flow package; `reactflow` is the old name).
- **Model emits topology only** (`nodes`/`edges`), never coordinates. We compute positions with
  **dagre** in `autoLayout.ts` (`rankdir: 'TB'`, sensible node sizes) → assign `position` to
  each React Flow node. Deterministic, no manual layout.
- **Read-only UX:** `nodesDraggable={false}`, `nodesConnectable={false}`,
  `elementsSelectable={false}`, `fitView`, `panOnScroll`, zoom controls + `Background`.
  `proOptions={{ hideAttribution: true }}` only if licensing allows; otherwise keep attribution.
- **Custom node types** (`nodeTypes.tsx`) keyed off `FlowNode.kind` (start/process/decision/io/end)
  with distinct shadcn-styled shapes/colors; `detail` shown in a tooltip/popover.
- **Sizing:** render inside a fixed-height container (e.g. `h-[320px]`) within the bubble;
  the panel is 560px wide (`PROJECT_CHAT_PANEL_WIDTH`) so diagrams must be horizontally
  scrollable/zoomable. Consider a "expand" affordance → open the flow in a `Dialog` full-size.
- **Perf/bundle:** React Flow is heavy — **lazy-load** `FlowBlock` (`React.lazy` + `Suspense`)
  so text-only chats don't pay for it.

Use cases this unlocks: "show how checkout works", "diagram the auth flow", "what will this new
feature touch in the codebase" — the assistant emits a `flow` block from the brief/repo context.

---

## 6b. Panel sizing — full-screen / expandable chat

Rich blocks (forms, and especially flow diagrams) need room; the current fixed 560px
(`PROJECT_CHAT_PANEL_WIDTH`) is too narrow for them. Give the panel **three width modes**
plus a full-screen mode, controlled from the panel header.

### Width modes
| Mode | Width | Use |
|---|---|---|
| Collapsed | 44px (`PROJECT_CHAT_PANEL_COLLAPSED_WIDTH`) | Existing rail toggle. |
| Docked (default) | 560px | Today's behaviour — text-heavy Q&A. |
| Wide | `min(50vw, 900px)` | Forms / medium diagrams. |
| Full-screen | 100vw × (100vh − 56px top bar) | Large flow diagrams, side-by-side reading. |

### Approach
- Replace the two width constants with a `panelMode: 'docked' | 'wide' | 'full'` state in
  `ProjectChatPanel` (collapsed stays a separate `isOpen` boolean as today).
- Header gets an **expand/restore control** (lucide `Maximize2` / `Minimize2`) cycling
  docked → wide → full → docked, next to the existing `PanelRightClose`.
- The `<aside>` already uses `fixed top-14 bottom-0 right-{offset}`; drive `width` from
  `panelMode`. For **full-screen**, also set `left-0` (or `inset-x-0`) and drop the
  `rightOffset`/`max-w-[90vw]` clamp so it truly spans the viewport; keep `top-14` so the app
  top bar stays visible (or go `top-0` + an in-panel close for a true overlay — decide in review).
- Persist the chosen mode in `localStorage` (per user) so it survives navigation.
- **Content reflow:** message bubble `max-w` is currently `82%`/`92%` of a 560px column. In
  wide/full mode, cap the *text* column (e.g. `max-w-[720px]` centered) so prose stays readable,
  while **form and flow blocks expand to use the full width**. Add a `layout` hint from the
  renderer so block components know they can go wide.
- **Responsive:** on small screens, "docked" already clamps via `max-w-[90vw]`; full-screen is
  the natural mobile default. Wide mode collapses to full below a breakpoint.
- **Interaction with the Request-Changes panel:** `rightOffset` exists so both panels can sit
  side by side. In wide/full mode the chat overlaps/replaces that layout — when entering
  full-screen, either close the other panel or render the chat above it (higher `z-index`);
  restore on exit.

### React Flow benefit
With a full-screen panel, the flow diagram's "expand to `Dialog`" affordance (§6) becomes
optional — a `flow` block can render large inline. Keep the Dialog expand as a secondary path
for when the panel is docked.

---

## 7. Backend + prompt changes

### 7a. Reply parsing (`ClineChatController`)
- Cline still returns a text `reply`. Instruct it to emit blocks either as (a) a whole-reply
  JSON envelope `{ "blocks": [...] }`, or (b) a fenced ```` ```ui ```` region embedded in prose.
- Add a `ChatBlockParser`:
  1. Extract candidate JSON (whole reply, or fenced `ui` region).
  2. Validate against the block schema.
  3. On success → return `reply` (any surrounding prose) **plus** `blocks`.
  4. On any failure → `blocks = null`, keep plaintext. **Never** hard-fail a chat turn on a
     malformed block (same defensiveness lesson as `[[plan_reliability_over_srp]]`).
- Extend `ClineChatResult` → `{ sessionId, reply, blocks? }`. Persist blocks with the message
  so history re-renders (chat_memory currently stores text — decide: store the raw JSON in the
  message content, or add a column/side-store. Simplest: store the JSON envelope as the message
  content and re-parse on load, keeping `content` as the human-text fallback).

### 7b. Prompt (system) — the real reliability work
The renderer is easy; **getting Cline to emit valid blocks is the hard part.** Add a
"RESPONSE FORMAT" section to the Cline system prompt (`ProjectContextBuilder` / sidecar
`server.ts` prompt) that:
- Describes each block type + a JSON schema summary.
- Gives **few-shot examples** (a form, a choices, a flow).
- States when to use blocks vs. plain text (don't force JSON for a simple answer).
- Tells it to interpret `[form:...] {json}` / `[choice:...]` user turns from `onAction`.
Follow `[[feedback_prompt_bans_need_replacements]]`: every "do X not Y" states the canonical shape.

---

## 8. Build order (incremental, each step shippable)

1. **Deps + shadcn**: add `@xyflow/react`, `dagre`+`@types/dagre`, `react-markdown`+`remark-gfm`;
   add shadcn `form/textarea/checkbox/radio-group/popover/calendar`.
2. **Contract**: `UIBlock` types + zod validators + `parseBlocks` in `businessService.ts`;
   extend `ChatMessageView` and `ClineChatResult`.
3. **Renderer spine**: `BlockRenderer` + `registry` + `TextBlock` + `CalloutBlock` +
   `FallbackBlock`; wire into `ChatBubble` (assistant branch only). Prove the pipe end-to-end
   with a hardcoded blocks payload.
4. **Interactive blocks**: `ChoicesBlock`, `DateBlock`, `FormBlock` + `onAction` → `sendMutation`
   round-trip + answered/disabled states.
5. **Panel sizing** (§6b): `panelMode` state + header expand/restore control + wide/full-screen
   widths + localStorage persistence + block `layout` hint (wide-capable). Independent of the
   contract work — can land before or in parallel.
6. **Flow**: `FlowBlock` (lazy) + dagre `autoLayout` + custom node types + expand-to-dialog
   (inline-large when panel is full-screen).
7. **Backend**: `ChatBlockParser` + `ClineChatResult.blocks` + persistence + defensive fallback.
8. **Prompt**: RESPONSE FORMAT section + few-shot + action-turn interpretation.
9. **Verify**: unit tests (parser both sides, dynamic zod form schema, dagre layout);
   live run — text, form submit round-trip, a flow diagram from a real brief, panel width modes.

---

## 9. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Model emits malformed/invalid JSON | Defensive parse + zod on both sides → text fallback; never fatal. |
| Model over-uses JSON for trivial answers | Prompt guidance + examples; plaintext is the default. |
| React Flow bundle weight | Lazy-load `FlowBlock`; text chats unaffected. |
| History re-render fidelity | Persist the block JSON with the message; re-parse on load. |
| Double-submit / stale interaction | Blocks render disabled/answered after action; `disabled` during in-flight turn. |
| Panel width (560px) vs. diagrams/forms | Wide/full-screen panel modes (§6b); text column capped for readability while form/flow blocks go wide; flow keeps zoom + expand-to-`Dialog`. |
| Full-screen overlaps Request-Changes panel | On entering full-screen, close/raise-z the other panel; restore `rightOffset` layout on exit (§6b). |
| Calendar focus-steal regression | Use `autoFocus` (not `initialFocus`) per `[[abs_fitness_fix_plan_f2_f6]]`. |

---

## 10. Open questions for review
1. **Block persistence:** store the JSON envelope as message content (re-parse on load) vs.
   add a dedicated store/column? (Leaning: JSON-as-content, simplest, no schema change.)
2. **Envelope vs. fenced blocks:** whole-reply `{blocks}` envelope, or allow prose + fenced
   ```` ```ui ```` regions interleaved? (Leaning: support both; fenced enables mixed prose+UI.)
3. **Scope of first cut:** ship text+callout+choices+form first, flow in a fast-follow? Or all
   six together?
