import { Inbox } from 'lucide-react'

interface EmptyStateProps {
  message?: string
}

export function EmptyState({ message = 'No data yet' }: EmptyStateProps) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 py-16 text-center">
      <Inbox className="h-8 w-8 text-[#444]" />
      <p className="text-sm text-[#666]">{message}</p>
    </div>
  )
}
