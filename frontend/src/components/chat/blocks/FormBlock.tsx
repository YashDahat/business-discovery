import { useMemo, useState } from 'react'
import { useForm, Controller, type FieldValues, type Resolver } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { format } from 'date-fns'
import { CalendarIcon, Check } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Checkbox } from '@/components/ui/checkbox'
import { Calendar } from '@/components/ui/calendar'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'
import type { BlockProps } from './types'
import type { FormBlock as FormBlockSpec, FormField } from './schema'

// Build a zod object + default values from the field specs.
function buildSchema(fields: FormField[]) {
  const shape: Record<string, z.ZodTypeAny> = {}
  const defaults: Record<string, unknown> = {}
  for (const f of fields) {
    switch (f.kind) {
      case 'checkbox':
        shape[f.name] = f.required ? z.literal(true, { message: 'Required' }) : z.boolean()
        defaults[f.name] = false
        break
      case 'number': {
        let n = z.coerce.number({ message: 'Enter a number' })
        if (typeof f.min === 'number') n = n.min(f.min, `Min ${f.min}`)
        if (typeof f.max === 'number') n = n.max(f.max, `Max ${f.max}`)
        shape[f.name] = f.required ? n : n.optional()
        defaults[f.name] = ''
        break
      }
      default: {
        // text, textarea, select, radio, date all carry a string value
        const s = z.string()
        shape[f.name] = f.required ? s.min(1, 'Required') : s.optional()
        defaults[f.name] = ''
      }
    }
  }
  return { schema: z.object(shape), defaults }
}

export function FormBlock({ block, onAction, disabled, wide }: BlockProps<FormBlockSpec>) {
  const { schema, defaults } = useMemo(() => buildSchema(block.fields), [block.fields])
  const [answered, setAnswered] = useState(false)
  const locked = disabled || answered

  const form = useForm<FieldValues>({
    resolver: zodResolver(schema) as Resolver<FieldValues>,
    defaultValues: defaults,
  })

  const onSubmit = (values: FieldValues) => {
    if (locked) return
    setAnswered(true)
    onAction({ block, kind: 'form_submit', values })
  }

  return (
    <div className="rounded-lg border border-[#2a2a2a] bg-[#141414] p-3.5">
      {block.title && (
        <div className="mb-2 flex items-center gap-2">
          <h4 className="text-sm font-semibold text-white">{block.title}</h4>
          {answered && <Check className="h-4 w-4 text-emerald-400" />}
        </div>
      )}
      {block.description && <p className="mb-3 text-xs text-[#888]">{block.description}</p>}

      <form
        onSubmit={form.handleSubmit(onSubmit)}
        className={cn('grid gap-3.5', wide && block.fields.length > 3 && 'sm:grid-cols-2')}
      >
        {block.fields.map(f => {
          const err = form.formState.errors[f.name]?.message as string | undefined
          return (
            <div key={f.name} className={cn('flex flex-col gap-1.5', f.kind === 'textarea' && 'sm:col-span-2')}>
              {f.kind !== 'checkbox' && (
                <label className="text-xs font-medium text-[#bbb]">
                  {f.label}{f.required && <span className="text-red-400"> *</span>}
                </label>
              )}

              <Controller
                name={f.name}
                control={form.control}
                render={({ field }) => {
                  switch (f.kind) {
                    case 'textarea':
                      return <Textarea {...field} placeholder={f.placeholder} disabled={locked} rows={3} />
                    case 'number':
                      return <Input {...field} type="number" placeholder={f.placeholder} disabled={locked} min={f.min} max={f.max} />
                    case 'checkbox':
                      return (
                        <label className="flex items-center gap-2 text-sm text-[#ddd]">
                          <Checkbox checked={!!field.value} disabled={locked} onCheckedChange={field.onChange} />
                          {f.label}{f.required && <span className="text-red-400"> *</span>}
                        </label>
                      )
                    case 'select':
                      return (
                        <Select value={field.value || undefined} onValueChange={field.onChange} disabled={locked}>
                          <SelectTrigger><SelectValue placeholder={f.placeholder ?? 'Select…'} /></SelectTrigger>
                          <SelectContent>
                            {(f.options ?? []).map(o => (
                              <SelectItem key={o.value} value={o.value}>{o.label}</SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      )
                    case 'radio':
                      return (
                        <RadioGroup value={field.value} onValueChange={field.onChange} disabled={locked}>
                          {(f.options ?? []).map(o => (
                            <label key={o.value} className="flex items-center gap-2 text-sm text-[#ddd]">
                              <RadioGroupItem value={o.value} /> {o.label}
                            </label>
                          ))}
                        </RadioGroup>
                      )
                    case 'date':
                      return <DateField value={field.value} onChange={field.onChange} disabled={locked} placeholder={f.placeholder} />
                    default:
                      return <Input {...field} placeholder={f.placeholder} disabled={locked} />
                  }
                }}
              />
              {err && <p className="text-[0.72rem] font-medium text-red-400">{err}</p>}
            </div>
          )
        })}

        {!locked && (
          <div className="sm:col-span-2">
            <Button type="submit" size="sm">{block.submitLabel ?? 'Submit'}</Button>
          </div>
        )}
      </form>
    </div>
  )
}

// A single-date field for use inside a form (stores an ISO yyyy-MM-dd string).
function DateField({ value, onChange, disabled, placeholder }: {
  value?: string
  onChange: (v: string) => void
  disabled?: boolean
  placeholder?: string
}) {
  const [open, setOpen] = useState(false)
  const selected = value ? new Date(value) : undefined
  return (
    <Popover open={open} onOpenChange={o => !disabled && setOpen(o)}>
      <PopoverTrigger asChild>
        <Button variant="outline" size="sm" disabled={disabled} className={cn('justify-start font-normal', !value && 'text-muted-foreground')}>
          <CalendarIcon className="h-3.5 w-3.5" />
          {value ? format(new Date(value), 'PP') : (placeholder ?? 'Pick a date')}
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-auto p-0" align="start">
        <Calendar
          mode="single"
          autoFocus
          selected={selected}
          onSelect={d => { if (d) { onChange(format(d, 'yyyy-MM-dd')); setOpen(false) } }}
        />
      </PopoverContent>
    </Popover>
  )
}
