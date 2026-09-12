import { Badge } from '@/components/ui/badge'
import { Card } from '@/components/ui/card'
import type { BlockProps } from './types'
import type { PaletteBlock as PaletteBlockSpec, PaletteColor } from './schema'

/** Relative luminance (0 dark → 1 light) for a #rgb / #rrggbb string, used for readable text overlays. */
function luminance(hex: string): number {
  let h = hex.replace('#', '')
  if (h.length === 3) h = h.split('').map(c => c + c).join('')
  const r = parseInt(h.slice(0, 2), 16) / 255
  const g = parseInt(h.slice(2, 4), 16) / 255
  const b = parseInt(h.slice(4, 6), 16) / 255
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

const readableOn = (hex: string) => (luminance(hex) > 0.6 ? '#1a1a1a' : '#ffffff')

const matches = (c: PaletteColor, ...keys: string[]) => {
  const hay = `${c.name} ${c.usage ?? ''}`.toLowerCase()
  return keys.some(k => hay.includes(k))
}

/** Map named palette colours to the four preview roles, by keyword then sensible fallbacks. */
function resolveRoles(colors: PaletteColor[]) {
  const byLum = [...colors].sort((a, b) => luminance(a.hex) - luminance(b.hex))
  const find = (keys: string[], fallback: string) =>
    (colors.find(c => matches(c, ...keys)) ?? { hex: fallback }).hex
  return {
    background: find(['background', 'off-white', 'ivory', 'cream'], byLum[byLum.length - 1]?.hex ?? '#ffffff'),
    text:       find(['text', 'body', 'charcoal'], byLum[0]?.hex ?? '#333333'),
    primary:    find(['primary', 'header', 'button', 'cta'], colors[0]?.hex ?? '#800020'),
    accent:     find(['accent', 'highlight', 'gold', 'sale', 'price'], colors[1]?.hex ?? colors[0]?.hex ?? '#D4AF37'),
  }
}

/** Display-only block: colour swatches + an optional product-card mockup rendered in the palette. */
export function PaletteBlock({ block }: BlockProps<PaletteBlockSpec>) {
  const roles = block.preview ? resolveRoles(block.colors) : null

  return (
    <div className="flex flex-col gap-3">
      {block.title && <div className="text-sm font-semibold text-white">{block.title}</div>}

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
        {block.colors.map(c => (
          <Card key={`${c.name}-${c.hex}`} className="flex items-center gap-3 border-[#2a2a2a] bg-[#0d0d0d] p-2 shadow-none">
            <div
              className="h-10 w-10 shrink-0 rounded-md border border-white/10"
              style={{ backgroundColor: c.hex }}
              title={c.hex}
            />
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <span className="text-sm font-medium text-[#ddd]">{c.name}</span>
                <Badge variant="outline" className="border-[#2a2a2a] font-mono text-[10px] uppercase text-[#888]">
                  {c.hex}
                </Badge>
              </div>
              {c.usage && <p className="mt-0.5 text-xs leading-snug text-[#888]">{c.usage}</p>}
            </div>
          </Card>
        ))}
      </div>

      {block.preview && roles && (
        <div className="flex flex-col gap-1.5">
          <span className="text-[10px] font-mono uppercase tracking-widest text-[#555]">Live preview</span>
          <div
            className="max-w-[300px] overflow-hidden rounded-lg border"
            style={{ backgroundColor: roles.background, borderColor: 'rgba(0,0,0,0.08)' }}
          >
            <div
              className="flex h-40 items-center justify-center text-sm italic"
              style={{ backgroundColor: '#e0e0e0', color: '#aaa' }}
            >
              {block.preview.imageLabel ?? 'Product image'}
            </div>
            <div className="p-4" style={{ color: roles.text }}>
              <h3 className="text-base font-semibold" style={{ color: roles.text }}>
                {block.preview.productName}
              </h3>
              {block.preview.description && (
                <p className="mt-1 text-sm" style={{ color: roles.text, opacity: 0.8 }}>
                  {block.preview.description}
                </p>
              )}
              <div className="mt-4 flex items-center justify-between gap-3">
                {block.preview.price && (
                  <span className="text-lg font-bold" style={{ color: roles.accent }}>
                    {block.preview.price}
                  </span>
                )}
                <button
                  type="button"
                  className="ml-auto rounded-md px-4 py-2 text-sm font-semibold"
                  style={{ backgroundColor: roles.primary, color: readableOn(roles.primary) }}
                >
                  {block.preview.ctaLabel ?? 'Add to Cart'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
