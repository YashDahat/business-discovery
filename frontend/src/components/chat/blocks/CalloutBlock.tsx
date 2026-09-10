import { Info, TriangleAlert, CheckCircle2 } from 'lucide-react'
import { cn } from '@/lib/utils'
import type { BlockProps } from './types'
import type { CalloutBlock as CalloutBlockSpec } from './schema'
import { Markdown } from './Markdown'

const VARIANTS = {
  info:    { icon: Info,          border: 'border-[#1e3a5f]', bg: 'bg-[#0e1a2a]', tint: 'text-[#4aa8ff]' },
  warn:    { icon: TriangleAlert, border: 'border-[#5f4a1e]', bg: 'bg-[#241c0d]', tint: 'text-amber-400' },
  success: { icon: CheckCircle2,  border: 'border-[#1e5f3a]', bg: 'bg-[#0d241a]', tint: 'text-emerald-400' },
} as const

export function CalloutBlock({ block }: BlockProps<CalloutBlockSpec>) {
  const v = VARIANTS[block.variant]
  const Icon = v.icon
  return (
    <div className={cn('flex gap-2.5 rounded-lg border p-3', v.border, v.bg)}>
      <Icon className={cn('h-4 w-4 shrink-0 mt-0.5', v.tint)} />
      <div className="min-w-0 flex-1"><Markdown>{block.markdown}</Markdown></div>
    </div>
  )
}
