import { AlertTriangle } from 'lucide-react'

interface ErrorStateProps {
  message?: string
}

export function ErrorState({ message = 'Failed to load data' }: ErrorStateProps) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 py-16 text-center">
      <AlertTriangle className="h-8 w-8 text-red-400" />
      <p className="text-sm text-[#666]">{message}</p>
    </div>
  )
}
