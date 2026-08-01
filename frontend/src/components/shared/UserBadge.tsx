import { useNavigate } from 'react-router-dom'
import { useAuth } from '@/context/AuthContext'
import { initials } from '@/lib/initials'
import { ROLE_TITLE } from '@/components/shared/RoleBadge'

/**
 * Sidebar identity chip for the logged-in user (avatar initials + name + role title).
 * Clicking opens the profile screen. Renders nothing when signed out.
 */
export function UserBadge() {
  const { user } = useAuth()
  const navigate = useNavigate()
  if (!user) return null

  return (
    <button
      onClick={() => navigate('/profile')}
      title="View your profile"
      className="flex w-full items-center gap-3 rounded-lg px-2 py-2 text-left transition-colors hover:bg-[#111]"
    >
      <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg bg-[#161616] text-sm font-semibold text-[#00ff88]">
        {initials(user.name)}
      </div>
      <div className="min-w-0">
        <div className="truncate text-sm font-semibold text-white">{user.name}</div>
        <div className="truncate text-xs text-[#888]">{ROLE_TITLE[user.role]}</div>
      </div>
    </button>
  )
}
