import { useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { LogOut, KeyRound } from 'lucide-react'
import { useProfile } from '@/hooks/useProfile'
import { useAuth } from '@/context/AuthContext'
import { initials } from '@/lib/initials'
import { RoleBadge, ROLE_TITLE } from '@/components/shared/RoleBadge'
import { LoadingSpinner } from '@/components/shared/LoadingSpinner'
import { ChangePasswordModal } from '@/components/modals/ChangePasswordModal'
import { Button } from '@/components/ui/button'
import type { UserStatus } from '@/types/access'

const STATUS_COLOR: Record<UserStatus, string> = {
  ACTIVE: '#00ff88',
  PENDING: '#d4a24e',
  DISABLED: '#ff5c5c',
}

function fmtDate(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs uppercase tracking-wider text-[#666]">{label}</span>
      <div className="text-sm text-white">{children}</div>
    </div>
  )
}

export default function ProfilePage() {
  const { data: user, isLoading, isError } = useProfile()
  const { logout } = useAuth()
  const navigate = useNavigate()
  const [pwOpen, setPwOpen] = useState(false)

  if (isLoading) return <LoadingSpinner />

  if (isError || !user) {
    return (
      <div className="flex h-[60vh] flex-col items-center justify-center gap-3 text-center">
        <p className="text-sm text-[#888]">You’re not signed in.</p>
        <Button
          onClick={() => navigate('/login')}
          className="bg-[#00ff88] text-[#0a0a0a] font-semibold hover:bg-[#00cc6a]"
        >
          Go to sign in
        </Button>
      </div>
    )
  }

  const scoped = user.role === 'CLIENT' || user.role === 'RESELLER'

  const handleLogout = async () => {
    await logout()
    navigate('/login')
  }

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-6">
      {/* Identity header */}
      <div className="flex items-center gap-4 rounded-lg border border-[#1e1e1e] bg-[#111] p-6">
        <div className="flex h-16 w-16 shrink-0 items-center justify-center rounded-xl bg-[#161616] text-xl font-semibold text-[#00ff88]">
          {initials(user.name)}
        </div>
        <div className="min-w-0 flex-1">
          <h2 className="truncate text-xl font-bold text-white">{user.name}</h2>
          <p className="truncate text-sm text-[#888]">{user.email}</p>
        </div>
        <RoleBadge role={user.role} />
      </div>

      {/* Details */}
      <div className="grid grid-cols-2 gap-6 rounded-lg border border-[#1e1e1e] bg-[#111] p-6">
        <Field label="Title">{ROLE_TITLE[user.role]}</Field>
        <Field label="Status">
          <span className="inline-flex items-center gap-2">
            <span className="h-2 w-2 rounded-full" style={{ backgroundColor: STATUS_COLOR[user.status] }} />
            {user.status.charAt(0) + user.status.slice(1).toLowerCase()}
          </span>
        </Field>
        <Field label="Last login">{fmtDate(user.lastLoginAt)}</Field>
        <Field label="Member since">{fmtDate(user.createdAt)}</Field>

        <div className="col-span-2">
          <Field label="Access scope">
            {scoped ? (
              user.assignedBusinessNames.length ? (
                <div className="mt-1 flex flex-wrap gap-2">
                  {user.assignedBusinessNames.map((name, i) => (
                    <span
                      key={i}
                      className="inline-flex max-w-full items-center truncate rounded-md border border-[#242424] bg-[#0f0f0f] px-2.5 py-1 text-xs text-[#aaa]"
                      title={name}
                    >
                      {name}
                    </span>
                  ))}
                </div>
              ) : (
                <span className="text-[#666]">No businesses assigned</span>
              )
            ) : (
              <span>All businesses</span>
            )}
          </Field>
        </div>
      </div>

      <div className="flex flex-wrap gap-3">
        <Button
          onClick={() => setPwOpen(true)}
          variant="outline"
          className="border-[#2a2a2a] bg-transparent text-[#ccc] hover:bg-[#1a1a1a] hover:text-white"
        >
          <KeyRound className="mr-2 h-4 w-4" /> Change password
        </Button>
        <Button
          onClick={handleLogout}
          variant="outline"
          className="border-[#2a2a2a] bg-transparent text-[#ccc] hover:bg-[#1a1a1a] hover:text-white"
        >
          <LogOut className="mr-2 h-4 w-4" /> Sign out
        </Button>
      </div>

      <ChangePasswordModal open={pwOpen} onClose={() => setPwOpen(false)} />
    </div>
  )
}
