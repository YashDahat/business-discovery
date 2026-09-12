import { z } from 'zod'

/**
 * The wire contract between Cline and the chat UI. The assistant may embed structured "UI blocks"
 * in its reply; we render them as interactive shadcn components instead of plain text.
 *
 * Blocks are carried INSIDE the normal message `content` string (no backend DTO change), either as:
 *   - a fenced region:  ```ui  { "blocks": [ ... ] }  ```   (may be interleaved with prose), or
 *   - a whole-message JSON envelope: { "blocks": [ ... ], "text"?: "..." }
 *
 * `parseMessageContent` extracts them defensively — anything that fails validation stays as plain
 * text, so a malformed block never breaks a chat turn.
 */

const optionSchema = z.object({
  label: z.string(),
  value: z.string(),
})

export const textBlockSchema = z.object({
  type: z.literal('text'),
  markdown: z.string(),
})

export const calloutBlockSchema = z.object({
  type: z.literal('callout'),
  variant: z.enum(['info', 'warn', 'success']),
  markdown: z.string(),
})

export const choicesBlockSchema = z.object({
  type: z.literal('choices'),
  id: z.string(),
  prompt: z.string(),
  options: z.array(optionSchema).min(1),
  multi: z.boolean().optional(),
})

export const selectBlockSchema = z.object({
  type: z.literal('select'),
  id: z.string(),
  prompt: z.string(),
  options: z.array(optionSchema).min(1),
  multi: z.boolean().optional(),
  placeholder: z.string().optional(),
  submitLabel: z.string().optional(),
})

export const dateBlockSchema = z.object({
  type: z.literal('date'),
  id: z.string(),
  label: z.string(),
  mode: z.enum(['single', 'range']).optional(),
})

export const formFieldSchema = z.object({
  name: z.string(),
  kind: z.enum(['text', 'textarea', 'number', 'select', 'multiselect', 'checkbox', 'radio', 'date']),
  label: z.string(),
  placeholder: z.string().optional(),
  required: z.boolean().optional(),
  options: z.array(optionSchema).optional(),
  min: z.number().optional(),
  max: z.number().optional(),
})

export const formBlockSchema = z.object({
  type: z.literal('form'),
  id: z.string(),
  title: z.string().optional(),
  description: z.string().optional(),
  fields: z.array(formFieldSchema).min(1),
  submitLabel: z.string().optional(),
})

const flowNodeSchema = z.object({
  id: z.string(),
  label: z.string(),
  kind: z.enum(['start', 'process', 'decision', 'io', 'end']).optional(),
  detail: z.string().optional(),
})

const flowEdgeSchema = z.object({
  from: z.string(),
  to: z.string(),
  label: z.string().optional(),
})

export const flowBlockSchema = z.object({
  type: z.literal('flow'),
  id: z.string(),
  title: z.string().optional(),
  nodes: z.array(flowNodeSchema).min(1),
  edges: z.array(flowEdgeSchema),
})

const paletteColorSchema = z.object({
  name: z.string(),
  hex: z.string().regex(/^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/, 'must be a #hex colour'),
  usage: z.string().optional(),
})

const palettePreviewSchema = z.object({
  productName: z.string(),
  description: z.string().optional(),
  price: z.string().optional(),
  ctaLabel: z.string().optional(),
  imageLabel: z.string().optional(),
})

export const paletteBlockSchema = z.object({
  type: z.literal('palette'),
  title: z.string().optional(),
  colors: z.array(paletteColorSchema).min(1),
  // Optional live product-card mockup that applies the palette, so the owner sees the theme in context.
  preview: palettePreviewSchema.optional(),
})

export const uiBlockSchema = z.discriminatedUnion('type', [
  textBlockSchema,
  calloutBlockSchema,
  choicesBlockSchema,
  selectBlockSchema,
  dateBlockSchema,
  formBlockSchema,
  flowBlockSchema,
  paletteBlockSchema,
])

export type UIBlock = z.infer<typeof uiBlockSchema>
export type TextBlock = z.infer<typeof textBlockSchema>
export type CalloutBlock = z.infer<typeof calloutBlockSchema>
export type ChoicesBlock = z.infer<typeof choicesBlockSchema>
export type SelectBlock = z.infer<typeof selectBlockSchema>
export type DateBlock = z.infer<typeof dateBlockSchema>
export type FormBlock = z.infer<typeof formBlockSchema>
export type FormField = z.infer<typeof formFieldSchema>
export type FlowBlock = z.infer<typeof flowBlockSchema>
export type FlowNodeSpec = z.infer<typeof flowNodeSchema>
export type FlowEdgeSpec = z.infer<typeof flowEdgeSchema>
export type PaletteBlock = z.infer<typeof paletteBlockSchema>
export type PaletteColor = z.infer<typeof paletteColorSchema>

/** The action a rendered interactive block emits, fed back into the chat as the user's next turn. */
export type BlockAction =
  | { block: ChoicesBlock | SelectBlock; kind: 'choice_select'; values: string[] }
  | { block: DateBlock; kind: 'date_select'; value: string | { from: string; to: string } }
  | { block: FormBlock; kind: 'form_submit'; values: Record<string, unknown> }

/**
 * Serialise a block action into the user's next chat message. Both machine-readable (a tagged JSON
 * payload the assistant can parse) and human-readable (so the sent bubble reads naturally in history).
 */
export function serializeAction(action: BlockAction): string {
  switch (action.kind) {
    case 'choice_select': {
      const labels = action.values.map(v =>
        action.block.options.find(o => o.value === v)?.label ?? v)
      return `${labels.join(', ')}\n\n[choice:${action.block.id}] ${JSON.stringify(action.values)}`
    }
    case 'date_select':
      return `Selected date: ${typeof action.value === 'string'
        ? action.value
        : `${action.value.from} → ${action.value.to}`}\n\n[date:${action.block.id}] ${JSON.stringify(action.value)}`
    case 'form_submit':
      return `Submitted "${action.block.title ?? action.block.id}"\n\n[form:${action.block.id}] ${JSON.stringify(action.values)}`
  }
}

/**
 * A short, human label for an assistant turn that rendered UI components — used when replying to it.
 * We reference the component ("[Form: Trial signup]") rather than quoting/rendering the whole thing.
 */
export function describeBlocks(blocks: UIBlock[]): string {
  return blocks
    .map(b => {
      switch (b.type) {
        case 'text': return b.markdown
        case 'callout': return b.markdown
        case 'form': return `[Form: ${b.title ?? b.id}]`
        case 'choices': return `[Choice: ${b.prompt}]`
        case 'select': return `[${b.multi ? 'Multi-select' : 'Dropdown'}: ${b.prompt}]`
        case 'date': return `[Date: ${b.label}]`
        case 'flow': return `[Flow diagram: ${b.title ?? 'diagram'}]`
        case 'palette': return `[Colour palette: ${b.title ?? b.colors.map(c => c.name).join(', ')}]`
      }
    })
    .filter(Boolean)
    .join(' ')
    .trim()
}

/** A message the user is replying to (WhatsApp-style quote). */
export interface ReplyTarget {
  role: 'user' | 'ai'
  /** Display text of the referenced message (already cleaned of tags). */
  excerpt: string
}

const REPLY_EXCERPT_MAX = 280
const REPLY_RE = /^> \[Replying to (Cline|you)\]: "([^"]*)"\n\n([\s\S]*)$/

/**
 * Prepend a quote of the referenced message to the outgoing turn — both human-readable (renders as a
 * reply chip) and explicit enough that Cline sees exactly what is being referenced instead of guessing.
 */
export function serializeReply(target: ReplyTarget, body: string): string {
  const who = target.role === 'user' ? 'you' : 'Cline'
  const clean = target.excerpt.replace(/\s+/g, ' ').replace(/"/g, "'").trim().slice(0, REPLY_EXCERPT_MAX)
  return `> [Replying to ${who}]: "${clean}"\n\n${body}`
}

/** Split a user turn into its reply quote (if any) + the actual body. Never throws. */
export function parseReply(content: string): { quotedWho: 'Cline' | 'you'; quotedText: string; body: string } | null {
  const m = content.match(REPLY_RE)
  if (!m) return null
  return { quotedWho: m[1] as 'Cline' | 'you', quotedText: m[2], body: m[3] }
}

export interface ParsedMessage {
  /** Human prose with any recognised UI fences stripped out. */
  prose: string
  /** Validated blocks, in the order they appeared. */
  blocks: UIBlock[]
}

// Fenced regions the assistant may use for blocks: ```ui ... ``` (also tolerate ```json:ui).
const UI_FENCE_RE = /```(?:ui|json:ui)\s*\n?([\s\S]*?)```/g

// Coerce a parsed JSON value into a list of block candidates, tolerating three shapes:
//   { blocks: [...] } | [ ...blocks ] | { type: '...' } (single block)
function candidatesFrom(parsed: unknown): unknown[] {
  if (Array.isArray(parsed)) return parsed
  if (parsed && typeof parsed === 'object') {
    const obj = parsed as Record<string, unknown>
    if (Array.isArray(obj.blocks)) return obj.blocks
    if (typeof obj.type === 'string') return [parsed]
  }
  return []
}

function validate(candidates: unknown[]): UIBlock[] {
  const out: UIBlock[] = []
  for (const c of candidates) {
    const r = uiBlockSchema.safeParse(c)
    if (r.success) out.push(r.data)
  }
  return out
}

/**
 * Split a message's raw content into prose + validated UI blocks. Never throws; on any parse/validation
 * failure the original text is returned as prose with no blocks. Identical for live replies and history,
 * since both arrive as the same `content` string.
 */
export function parseMessageContent(content: string): ParsedMessage {
  if (!content || !content.includes('{') || !content.includes('}')) {
    return { prose: content ?? '', blocks: [] }
  }

  // 1) Whole-message JSON envelope.
  const trimmed = content.trim()
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try {
      const parsed = JSON.parse(trimmed)
      const blocks = validate(candidatesFrom(parsed))
      if (blocks.length > 0) {
        const text = (parsed && typeof parsed === 'object' && !Array.isArray(parsed)
          ? (parsed as Record<string, unknown>).text
          : undefined)
        return { prose: typeof text === 'string' ? text : '', blocks }
      }
    } catch {
      /* fall through to fence scan */
    }
  }

  // 2) Fenced ```ui regions interleaved with prose. Only strip a fence that yielded >=1 valid block.
  const blocks: UIBlock[] = []
  let prose = content
  let match: RegExpExecArray | null
  UI_FENCE_RE.lastIndex = 0
  const toStrip: string[] = []
  while ((match = UI_FENCE_RE.exec(content)) !== null) {
    try {
      const parsed = JSON.parse(match[1].trim())
      const valid = validate(candidatesFrom(parsed))
      if (valid.length > 0) {
        blocks.push(...valid)
        toStrip.push(match[0])
      }
    } catch {
      /* leave this fence in the prose */
    }
  }
  for (const fence of toStrip) prose = prose.replace(fence, '')

  return { prose: prose.trim(), blocks }
}
