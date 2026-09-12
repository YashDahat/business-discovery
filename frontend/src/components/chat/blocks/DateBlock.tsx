import { useState } from 'react'
import { format } from 'date-fns'
import type { DateRange } from 'react-day-picker'
import { CalendarIcon, Check } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Calendar } from '@/components/ui/calendar'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import type { BlockProps } from './types'
import type { DateBlock as DateBlockSpec } from './schema'

const iso = (d: Date) => format(d, 'yyyy-MM-dd')

export function DateBlock({ block, onAction, disabled }: BlockProps<DateBlockSpec>) {
  const isRange = block.mode === 'range'
  const [open, setOpen] = useState(false)
  const [single, setSingle] = useState<Date | undefined>()
  const [range, setRange] = useState<DateRange | undefined>()
  const [answered, setAnswered] = useState<string | null>(null)
  const locked = disabled || answered !== null

  const confirm = () => {
    if (locked) return
    if (isRange) {
      if (!range?.from || !range?.to) return
      const value = { from: iso(range.from), to: iso(range.to) }
      setAnswered(`${value.from} → ${value.to}`)
      onAction({ block, kind: 'date_select', value })
    } else {
      if (!single) return
      const value = iso(single)
      setAnswered(value)
      onAction({ block, kind: 'date_select', value })
    }
    setOpen(false)
  }

  const label = answered
    ?? (isRange
      ? (range?.from ? `${format(range.from, 'PP')}${range.to ? ` – ${format(range.to, 'PP')}` : ''}` : null)
      : (single ? format(single, 'PP') : null))

  return (
    <div className="rounded-lg border border-[#2a2a2a] bg-[#141414] p-3">
      <p className="text-sm text-[#ccc] mb-2.5">{block.label}</p>
      <div className="flex items-center gap-2">
        <Popover open={open} onOpenChange={o => !locked && setOpen(o)}>
          <PopoverTrigger asChild>
            <Button
              variant="outline"
              size="sm"
              disabled={locked}
              className={cn('justify-start font-normal', !label && 'text-muted-foreground')}
            >
              <CalendarIcon className="h-3.5 w-3.5" />
              {label ?? (isRange ? 'Pick a date range' : 'Pick a date')}
            </Button>
          </PopoverTrigger>
          <PopoverContent className="w-auto p-0" align="start">
            {isRange ? (
              <Calendar mode="range" autoFocus selected={range} onSelect={setRange} numberOfMonths={2} />
            ) : (
              <Calendar mode="single" autoFocus selected={single} onSelect={setSingle} />
            )}
          </PopoverContent>
        </Popover>
        {!locked && (
          <Button
            size="sm"
            onClick={confirm}
            disabled={isRange ? !(range?.from && range?.to) : !single}
          >
            Confirm
          </Button>
        )}
        {answered && <Check className="h-4 w-4 text-emerald-400" />}
      </div>
    </div>
  )
}
