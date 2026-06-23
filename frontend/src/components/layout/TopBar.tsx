import { Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'

interface TopBarProps {
  title: string
  onNewRun: () => void
}

export function TopBar({ title, onNewRun }: TopBarProps) {
  return (
    <header className="h-14 shrink-0 flex items-center justify-between px-6 border-b border-[#1e1e1e] bg-[#0a0a0a]">
      <h1 className="text-sm font-semibold text-white">{title}</h1>
      <Button
        onClick={onNewRun}
        className="h-8 px-3 text-xs bg-[#00ff88] text-[#0a0a0a] font-semibold hover:bg-[#00cc6a] transition-colors"
      >
        <Plus className="h-3.5 w-3.5 mr-1" />
        New Discovery Run
      </Button>
    </header>
  )
}
