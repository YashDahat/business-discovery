import { cn } from '@/lib/utils'

interface SparklineBarProps {
  value: number
  max?: number
  className?: string
  label?: string
}

export function SparklineBar({ value, max = 100, className, label }: SparklineBarProps) {
  const pct = Math.min(100, Math.max(0, (value / max) * 100))
  return (
    <div className={cn('flex flex-col gap-1', className)}>
      {label && <span className="text-xs text-[#666]">{label}</span>}
      <div className="h-1.5 w-full rounded-full bg-[#1e1e1e]">
        <div
          className="h-full rounded-full bg-[#00ff88] transition-all duration-300"
          style={{ width: `${pct}%` }}
        />
      </div>
      <span className="text-xs text-[#666]">{pct.toFixed(0)}%</span>
    </div>
  )
}
