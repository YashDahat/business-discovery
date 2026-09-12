import { TriangleAlert } from 'lucide-react'

// Shown when a block has an unknown/unsupported type — visible but never fatal.
export function FallbackBlock({ raw }: { raw: unknown }) {
  return (
    <div className="flex gap-2.5 rounded-lg border border-[#5f4a1e] bg-[#241c0d] p-3">
      <TriangleAlert className="h-4 w-4 shrink-0 mt-0.5 text-amber-400" />
      <div className="min-w-0 flex-1">
        <p className="text-xs font-semibold text-amber-300 mb-1">Unsupported content</p>
        <pre className="overflow-x-auto text-[11px] text-[#bbb] whitespace-pre-wrap">
          {JSON.stringify(raw, null, 2)}
        </pre>
      </div>
    </div>
  )
}
