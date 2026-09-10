import { useState } from 'react'
import { Check } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import type { BlockProps } from './types'
import type { ChoicesBlock as ChoicesBlockSpec } from './schema'

export function ChoicesBlock({ block, onAction, disabled }: BlockProps<ChoicesBlockSpec>) {
  const [selected, setSelected] = useState<string[]>([])
  const [answered, setAnswered] = useState<string[] | null>(null)
  const locked = disabled || answered !== null

  const submit = (values: string[]) => {
    if (locked || values.length === 0) return
    setAnswered(values)
    onAction({ block, kind: 'choice_select', values })
  }

  const toggle = (value: string) =>
    setSelected(cur => (cur.includes(value) ? cur.filter(v => v !== value) : [...cur, value]))

  const chosen = answered ?? selected

  return (
    <div className="rounded-lg border border-[#2a2a2a] bg-[#141414] p-3">
      <p className="text-sm text-[#ccc] mb-2.5">{block.prompt}</p>

      {block.multi ? (
        <>
          <div className="flex flex-col gap-2">
            {block.options.map(opt => (
              <label
                key={opt.value}
                className={cn(
                  'flex items-center gap-2.5 rounded-md border border-[#2a2a2a] px-3 py-2 text-sm text-[#ddd]',
                  locked ? 'opacity-70 cursor-default' : 'cursor-pointer hover:border-[#3a3a3a]'
                )}
              >
                <Checkbox
                  checked={chosen.includes(opt.value)}
                  disabled={locked}
                  onCheckedChange={() => toggle(opt.value)}
                />
                {opt.label}
              </label>
            ))}
          </div>
          {!locked && (
            <Button size="sm" className="mt-2.5" disabled={selected.length === 0} onClick={() => submit(selected)}>
              Submit
            </Button>
          )}
        </>
      ) : (
        <div className="flex flex-wrap gap-2">
          {block.options.map(opt => {
            const isChosen = chosen.includes(opt.value)
            return (
              <Button
                key={opt.value}
                size="sm"
                variant={isChosen ? 'default' : 'outline'}
                disabled={locked && !isChosen}
                onClick={() => submit([opt.value])}
              >
                {isChosen && <Check className="h-3.5 w-3.5" />}
                {opt.label}
              </Button>
            )
          })}
        </div>
      )}
    </div>
  )
}
