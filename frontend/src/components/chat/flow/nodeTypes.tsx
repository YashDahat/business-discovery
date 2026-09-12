import { Handle, Position, type NodeProps } from '@xyflow/react'
import { CircleDot, Diamond, FileInput, Flag, Square } from 'lucide-react'
import { cn } from '@/lib/utils'
import type { FlowNodeSpec } from '../blocks/schema'

type Kind = NonNullable<FlowNodeSpec['kind']>

const KIND_STYLE: Record<Kind, { border: string; icon: typeof Square; tint: string }> = {
  start:    { border: 'border-emerald-500/60', icon: CircleDot, tint: 'text-emerald-400' },
  process:  { border: 'border-[#3a3a3a]',      icon: Square,    tint: 'text-[#4aa8ff]' },
  decision: { border: 'border-amber-500/60',   icon: Diamond,   tint: 'text-amber-400' },
  io:       { border: 'border-purple-500/60',  icon: FileInput, tint: 'text-purple-400' },
  end:      { border: 'border-red-500/60',      icon: Flag,      tint: 'text-red-400' },
}

// A single ops-themed flow node. `data` carries { label, kind, detail } from autoLayout.
function OpsNode({ data }: NodeProps) {
  const d = data as { label: string; kind: Kind; detail?: string }
  const style = KIND_STYLE[d.kind] ?? KIND_STYLE.process
  const Icon = style.icon
  return (
    <div
      title={d.detail}
      className={cn(
        'flex items-center gap-2 rounded-md border bg-[#141414] px-3 py-2 text-xs text-[#ddd] shadow-sm',
        'w-[180px] h-[52px]',
        style.border
      )}
    >
      <Handle type="target" position={Position.Top} className="!bg-[#3a3a3a] !border-none" />
      <Icon className={cn('h-3.5 w-3.5 shrink-0', style.tint)} />
      <span className="line-clamp-2 leading-tight">{d.label}</span>
      <Handle type="source" position={Position.Bottom} className="!bg-[#3a3a3a] !border-none" />
    </div>
  )
}

export const nodeTypes = { ops: OpsNode }
