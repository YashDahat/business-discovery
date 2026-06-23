import { cn } from '@/lib/utils'

interface MetricCardProps {
  label: string
  value: string | number
  unit?: string
  accent?: boolean
  className?: string
}

export function MetricCard({ label, value, unit, accent = true, className }: MetricCardProps) {
  return (
    <div className={cn('flex flex-col gap-0.5', className)}>
      <span className={cn('text-2xl font-bold tabular-nums', accent ? 'text-[#00ff88]' : 'text-white')}>
        {value}
        {unit && <span className="ml-1 text-sm font-normal text-[#666]">{unit}</span>}
      </span>
      <span className="text-xs text-[#666]">{label}</span>
    </div>
  )
}
