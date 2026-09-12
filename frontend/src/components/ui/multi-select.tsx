import { ChevronDown, X } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'

export interface MultiSelectOption { label: string; value: string }

/**
 * A multi-select dropdown: a popover of checkbox rows. Trigger shows selected labels (or a placeholder).
 * Controlled — `value` is the list of selected values, `onChange` receives the new list.
 */
export function MultiSelect({ options, value, onChange, placeholder = 'Select…', disabled, className }: {
  options: MultiSelectOption[]
  value: string[]
  onChange: (values: string[]) => void
  placeholder?: string
  disabled?: boolean
  className?: string
}) {
  const toggle = (v: string) =>
    onChange(value.includes(v) ? value.filter(x => x !== v) : [...value, v])

  const selectedLabels = options.filter(o => value.includes(o.value)).map(o => o.label)

  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          variant="outline"
          disabled={disabled}
          className={cn('h-10 w-full justify-between font-normal', !value.length && 'text-muted-foreground', className)}
        >
          <span className="truncate">
            {selectedLabels.length ? selectedLabels.join(', ') : placeholder}
          </span>
          {value.length > 0 && !disabled ? (
            <X
              className="h-3.5 w-3.5 shrink-0 opacity-60 hover:opacity-100"
              onClick={e => { e.preventDefault(); e.stopPropagation(); onChange([]) }}
            />
          ) : (
            <ChevronDown className="h-4 w-4 shrink-0 opacity-50" />
          )}
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-[--radix-popover-trigger-width] p-1" align="start">
        <div className="max-h-60 overflow-y-auto">
          {options.map(o => (
            <label
              key={o.value}
              className="flex cursor-pointer items-center gap-2 rounded-sm px-2 py-1.5 text-sm hover:bg-accent hover:text-accent-foreground"
            >
              <Checkbox checked={value.includes(o.value)} onCheckedChange={() => toggle(o.value)} />
              {o.label}
            </label>
          ))}
        </div>
      </PopoverContent>
    </Popover>
  )
}
