import { cn } from '@/lib/utils'
import type { PipelineStageStatus } from '@/types/pipeline'

const CONFIG: Record<PipelineStageStatus, { bg: string; text: string; label: string; pulse: boolean }> = {
  passed:  { bg: 'bg-emerald-950',  text: 'text-emerald-400',  label: 'Passed',  pulse: false },
  running: { bg: 'bg-blue-950',     text: 'text-blue-400',     label: 'Running', pulse: true  },
  pending: { bg: 'bg-[#1e1e1e]',    text: 'text-gray-500',     label: 'Pending', pulse: false },
  failed:  { bg: 'bg-red-950',      text: 'text-red-400',      label: 'Failed',  pulse: false },
}

interface PipelineStageBadgeProps {
  status: PipelineStageStatus
  className?: string
}

export function PipelineStageBadge({ status, className }: PipelineStageBadgeProps) {
  const cfg = CONFIG[status] ?? CONFIG.pending
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium',
        cfg.bg,
        cfg.text,
        cfg.pulse && 'animate-pulse',
        className
      )}
    >
      {cfg.label}
    </span>
  )
}
