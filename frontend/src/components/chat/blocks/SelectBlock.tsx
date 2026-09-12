import { useState } from 'react'
import { Check } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { MultiSelect } from '@/components/ui/multi-select'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'
import type { BlockProps } from './types'
import type { SelectBlock as SelectBlockSpec } from './schema'

// A dropdown (single) or multi-select dropdown. Emits the same choice_select action as ChoicesBlock:
// single submits on pick; multi collects then submits via the button.
export function SelectBlock({ block, onAction, disabled }: BlockProps<SelectBlockSpec>) {
  const [selected, setSelected] = useState<string[]>([])
  const [answered, setAnswered] = useState<string[] | null>(null)
  const locked = disabled || answered !== null

  const submit = (values: string[]) => {
    if (locked || values.length === 0) return
    setAnswered(values)
    onAction({ block, kind: 'choice_select', values })
  }

  const chosen = answered ?? selected
  const labelFor = (v: string) => block.options.find(o => o.value === v)?.label ?? v

  return (
    <div className="rounded-lg border border-[#2a2a2a] bg-[#141414] p-3">
      <p className="text-sm text-[#ccc] mb-2.5">{block.prompt}</p>

      {block.multi ? (
        <div className="flex flex-col gap-2.5">
          <MultiSelect
            options={block.options}
            value={chosen}
            onChange={setSelected}
            placeholder={block.placeholder}
            disabled={locked}
          />
          {!locked && (
            <Button size="sm" className="self-start" disabled={selected.length === 0} onClick={() => submit(selected)}>
              {block.submitLabel ?? 'Submit'}
            </Button>
          )}
        </div>
      ) : (
        <div className="flex items-center gap-2">
          <Select
            value={chosen[0]}
            onValueChange={v => submit([v])}
            disabled={locked}
          >
            <SelectTrigger className="max-w-xs">
              <SelectValue placeholder={block.placeholder ?? 'Select…'} />
            </SelectTrigger>
            <SelectContent>
              {block.options.map(o => (
                <SelectItem key={o.value} value={o.value}>{o.label}</SelectItem>
              ))}
            </SelectContent>
          </Select>
          {answered && <Check className="h-4 w-4 text-emerald-400" />}
        </div>
      )}

      {answered && block.multi && (
        <p className="mt-2 text-xs text-[#888]">Selected: {answered.map(labelFor).join(', ')}</p>
      )}
    </div>
  )
}
