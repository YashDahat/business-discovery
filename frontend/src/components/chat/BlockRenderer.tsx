import { Suspense } from 'react'
import { Loader2 } from 'lucide-react'
import type { BlockAction, UIBlock } from './blocks/schema'
import { BLOCK_REGISTRY } from './blocks/registry'
import { FallbackBlock } from './blocks/FallbackBlock'

/**
 * Renders a validated list of UI blocks. Each block is looked up in the registry; an unknown type
 * falls back to a visible (non-fatal) raw view. Flow blocks are lazy-loaded, hence the Suspense.
 */
export function BlockRenderer({ blocks, onAction, disabled, wide }: {
  blocks: UIBlock[]
  onAction: (action: BlockAction) => void
  disabled?: boolean
  wide?: boolean
}) {
  return (
    <div className="flex w-full flex-col gap-2.5">
      {blocks.map((block, i) => {
        const Comp = BLOCK_REGISTRY[block.type]
        if (!Comp) return <FallbackBlock key={i} raw={block} />
        return (
          <Suspense
            key={i}
            fallback={
              <div className="flex items-center gap-2 rounded-lg border border-[#2a2a2a] bg-[#141414] p-3 text-xs text-[#666]">
                <Loader2 className="h-3.5 w-3.5 animate-spin text-[#4aa8ff]" /> Loading…
              </div>
            }
          >
            <Comp block={block} onAction={onAction} disabled={disabled} wide={wide} />
          </Suspense>
        )
      })}
    </div>
  )
}
