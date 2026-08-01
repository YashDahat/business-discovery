import { Plus, LogOut, LogIn } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/context/AuthContext'
import { ROLE_META } from '@/components/shared/RoleBadge'

interface TopBarProps {
  title: string
  onNewRun: () => void
}

export function TopBar({ title, onNewRun }: TopBarProps) {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  const handleLogout = async () => {
    await logout()
    navigate('/login')
  }

  return (
    <header className="h-14 shrink-0 flex items-center justify-between px-6 border-b border-[#1e1e1e] bg-[#0a0a0a]">
      <h1 className="text-sm font-semibold text-white">{title}</h1>
      <div className="flex items-center gap-3">
        {/* Starting a discovery run is a write action — operators only */}
        {user?.role === 'OPERATOR' && (
          <Button
            onClick={onNewRun}
            className="h-8 px-3 text-xs bg-[#00ff88] text-[#0a0a0a] font-semibold hover:bg-[#00cc6a] transition-colors"
          >
            <Plus className="h-3.5 w-3.5 mr-1" />
            New Discovery Run
          </Button>
        )}

        {user ? (
          <div className="flex items-center gap-2 pl-3 border-l border-[#1e1e1e]">
            <div className="text-right leading-tight">
              <div className="text-xs font-medium text-white">{user.name}</div>
              <div className="text-[10px]" style={{ color: ROLE_META[user.role].color }}>
                {ROLE_META[user.role].label}
              </div>
            </div>
            <button
              onClick={handleLogout}
              title="Log out"
              className="rounded-md p-2 text-[#888] hover:bg-[#1a1a1a] hover:text-white transition-colors"
            >
              <LogOut className="h-4 w-4" />
            </button>
          </div>
        ) : (
          <button
            onClick={() => navigate('/login')}
            className="flex items-center gap-1.5 pl-3 border-l border-[#1e1e1e] text-xs text-[#888] hover:text-white transition-colors"
          >
            <LogIn className="h-3.5 w-3.5" />
            Log in
          </button>
        )}
      </div>
    </header>
  )
}
