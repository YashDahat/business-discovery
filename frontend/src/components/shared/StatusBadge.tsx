import { cn } from '@/lib/utils'

const CONFIG: Record<string, { dot: string; text: string; pulse: boolean }> = {
  RUNNING:     { dot: 'bg-status-running',  text: 'text-emerald-400', pulse: true  },
  IN_PROGRESS: { dot: 'bg-status-running',  text: 'text-emerald-400', pulse: true  },
  COMPLETED:   { dot: 'bg-status-running',  text: 'text-emerald-400', pulse: false },
  PASSED:      { dot: 'bg-status-running',  text: 'text-emerald-400', pulse: false },
  IDLE:        { dot: 'bg-status-idle',     text: 'text-amber-400',   pulse: false },
  PENDING:     { dot: 'bg-status-pending',  text: 'text-gray-400',    pulse: false },
  RETRYING:    { dot: 'bg-amber-400',       text: 'text-amber-400',   pulse: true  },
  FAILED:      { dot: 'bg-status-failed',   text: 'text-red-400',     pulse: false },
}

interface StatusBadgeProps {
  status: string
  className?: string
}

export function StatusBadge({ status, className }: StatusBadgeProps) {
  const cfg = CONFIG[status.toUpperCase()] ?? { dot: 'bg-gray-500', text: 'text-gray-400', pulse: false }
  return (
    <span className={cn('inline-flex items-center gap-1.5 text-xs font-medium', cfg.text, className)}>
      <span className={cn('h-2 w-2 rounded-full', cfg.dot, cfg.pulse && 'animate-pulse')} />
      {status}
    </span>
  )
}
