import { useState, type ReactNode } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Plus, Pencil, Ban, ShieldCheck, Trash2 } from 'lucide-react'
import { useUsers, useAccessSummary } from '@/hooks/useUsers'
import { deleteUser, updateUser } from '@/services/accessService'
import { apiErrorMessage } from '@/lib/apiError'
import { RoleBadge, ROLE_META, ROLE_ORDER } from '@/components/shared/RoleBadge'
import { UserFormModal } from '@/components/modals/UserFormModal'
import { LoadingSpinner } from '@/components/shared/LoadingSpinner'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { initials } from '@/lib/initials'
import type { AccessSummary, PlatformUser } from '@/types/access'

// What the assignment column shows for each role.
function assignment(user: PlatformUser, totalBusinesses: number): { chips: string[]; count: string } {
  const n = user.assignedBusinessIds.length
  switch (user.role) {
    case 'OPERATOR':
      return { chips: [`All ${totalBusinesses} businesses`], count: `${totalBusinesses} assigned` }
    case 'ANALYST':
      return { chips: ['Analytics (read-only)'], count: `${totalBusinesses} read-only` }
    case 'CLIENT':
      return { chips: user.assignedBusinessNames.length ? user.assignedBusinessNames : ['No business'],
               count: `${n} business${n === 1 ? '' : 'es'}` }
    case 'RESELLER':
      return { chips: [`${n} businesses`, 'portfolio'], count: `${n} assigned` }
    default:
      return { chips: [], count: '' }
  }
}

function Tile({ label, value, sub }: { label: string; value: number; sub: string }) {
  return (
    <div className="rounded-lg border border-[#1e1e1e] bg-[#111] p-5">
      <span className="text-xs font-medium uppercase tracking-wider text-[#666]">{label}</span>
      <div className="mt-2 text-4xl font-bold tabular-nums text-[#00ff88]">{value}</div>
      <span className="mt-1 block text-xs text-[#555]">{sub}</span>
    </div>
  )
}

function Chip({ children }: { children: ReactNode }) {
  return (
    <span className="inline-flex items-center rounded-md border border-[#242424] bg-[#0f0f0f] px-2.5 py-1 text-xs text-[#aaa]">
      {children}
    </span>
  )
}

export default function AccessRolesPage() {
  const { data: users, isLoading } = useUsers()
  const { data: summary } = useAccessSummary()
  const queryClient = useQueryClient()

  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<PlatformUser | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['users'] })
    queryClient.invalidateQueries({ queryKey: ['access-summary'] })
  }

  const statusMutation = useMutation({
    mutationFn: ({ id, status }: { id: string; status: PlatformUser['status'] }) => updateUser(id, { status }),
    onSuccess: invalidate,
    onError: e => setActionError(apiErrorMessage(e)),
  })
  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteUser(id),
    onSuccess: invalidate,
    onError: e => setActionError(apiErrorMessage(e)),
  })

  const openAdd = () => { setActionError(null); setEditing(null); setModalOpen(true) }
  const openEdit = (u: PlatformUser) => { setActionError(null); setEditing(u); setModalOpen(true) }
  const toggleBlock = (u: PlatformUser) => {
    setActionError(null)
    statusMutation.mutate({ id: u.id, status: u.status === 'DISABLED' ? 'ACTIVE' : 'DISABLED' })
  }
  const remove = (u: PlatformUser) => {
    setActionError(null)
    if (window.confirm(`Delete ${u.name}? This permanently removes the user and cannot be undone.`)) {
      deleteMutation.mutate(u.id)
    }
  }

  if (isLoading) return <LoadingSpinner />

  const s: AccessSummary = summary ?? {
    totalUsers: 0, internalUsers: 0, externalUsers: 0, operators: 0, analysts: 0,
    clients: 0, resellers: 0, pendingInvites: 0, totalBusinesses: 0,
  }

  return (
    <div className="flex flex-col gap-6">
      {/* Summary tiles */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <Tile label="Total Users" value={s.totalUsers} sub={`${s.internalUsers} internal · ${s.externalUsers} external`} />
        <Tile label="Operators" value={s.operators} sub="full platform access" />
        <Tile label="Client Accounts" value={s.clients} sub="business-scoped" />
        <Tile label="Pending" value={s.pendingInvites} sub="awaiting activation" />
      </div>

      {/* Role legend */}
      <div className="flex flex-wrap gap-3">
        {ROLE_ORDER.map(r => (
          <div key={r} className="inline-flex items-center gap-2 rounded-full border border-[#1e1e1e] bg-[#0f0f0f] px-4 py-2">
            <span className="h-2 w-2 rounded-full" style={{ backgroundColor: ROLE_META[r].color }} />
            <span className="text-sm font-medium text-white">{ROLE_META[r].label}</span>
            <span className="font-mono text-xs text-[#666]">{ROLE_META[r].access}</span>
          </div>
        ))}
      </div>

      {/* User list */}
      <div className="rounded-lg border border-[#1e1e1e] bg-[#111]">
        <div className="flex items-center justify-between px-5 py-4 border-b border-[#1e1e1e]">
          <span className="font-mono text-sm text-[#888]">user.access · business assignments</span>
          <Button onClick={openAdd} className="h-8 px-3 text-xs bg-[#00ff88] text-[#0a0a0a] font-semibold hover:bg-[#00cc6a]">
            <Plus className="h-3.5 w-3.5 mr-1" /> Add User
          </Button>
        </div>

        {actionError && (
          <div className="mx-5 mt-4 rounded-md border border-red-500/30 bg-red-500/10 px-3 py-2 text-xs text-red-400">
            {actionError}
          </div>
        )}

        <div className="divide-y divide-[#161616]">
          {(users ?? []).length === 0 && (
            <p className="px-5 py-10 text-center text-sm text-[#555]">No users yet. Click “Add User” to create one.</p>
          )}
          {(users ?? []).map(u => {
            const a = assignment(u, s.totalBusinesses)
            const disabled = u.status === 'DISABLED'
            return (
              <div key={u.id} className="group flex items-center gap-4 px-5 py-4">
                <div className={cn('flex items-center gap-4 flex-1 min-w-0', disabled && 'opacity-50')}>
                  <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg bg-[#161616] text-sm font-semibold text-[#00ff88]">
                    {initials(u.name)}
                  </div>
                  <div className="min-w-0 w-52">
                    <div className="truncate font-semibold text-white">{u.name}</div>
                    <div className="truncate text-sm text-[#666]">{u.email}</div>
                  </div>
                  <div className="flex items-center gap-2">
                    <RoleBadge role={u.role} />
                    {disabled && (
                      <span className="rounded-md border border-red-500/30 bg-red-500/10 px-2 py-0.5 text-xs text-red-400">
                        Disabled
                      </span>
                    )}
                  </div>
                  <div className="flex flex-wrap items-center gap-2">
                    {a.chips.map((c, i) => <Chip key={i}>{c}</Chip>)}
                  </div>
                </div>

                <span className="hidden shrink-0 text-sm text-[#555] sm:block">{a.count}</span>

                {/* Row actions */}
                <div className="flex shrink-0 items-center gap-1 opacity-60 transition-opacity group-hover:opacity-100">
                  <button
                    onClick={() => openEdit(u)}
                    title="Edit"
                    className="rounded-md p-2 text-[#888] hover:bg-[#1a1a1a] hover:text-white"
                  >
                    <Pencil className="h-4 w-4" />
                  </button>
                  <button
                    onClick={() => toggleBlock(u)}
                    title={disabled ? 'Unblock' : 'Block'}
                    className="rounded-md p-2 text-[#888] hover:bg-[#1a1a1a] hover:text-white"
                  >
                    {disabled ? <ShieldCheck className="h-4 w-4" /> : <Ban className="h-4 w-4" />}
                  </button>
                  <button
                    onClick={() => remove(u)}
                    title="Delete"
                    className="rounded-md p-2 text-[#888] hover:bg-red-500/10 hover:text-red-400"
                  >
                    <Trash2 className="h-4 w-4" />
                  </button>
                </div>
              </div>
            )
          })}
        </div>
      </div>

      <UserFormModal open={modalOpen} onClose={() => setModalOpen(false)} user={editing} />
    </div>
  )
}
