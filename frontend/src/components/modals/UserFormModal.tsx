import { useEffect, useMemo, useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createUser, updateUser } from '@/services/accessService'
import { useBusinesses } from '@/hooks/useBusinesses'
import { apiErrorMessage } from '@/lib/apiError'
import type { PlatformUser, Role } from '@/types/access'
import { ROLE_ORDER, ROLE_META } from '@/components/shared/RoleBadge'
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select, SelectTrigger, SelectValue, SelectContent, SelectItem,
} from '@/components/ui/select'

const ROLES = ['OPERATOR', 'ANALYST', 'CLIENT', 'RESELLER'] as const

const schema = z.object({
  name: z.string().min(2, 'Name is required'),
  email: z.string().email('A valid email is required'),
  password: z.string().optional(),
  role: z.enum(ROLES),
  status: z.enum(['ACTIVE', 'PENDING', 'DISABLED']),
  assignedBusinessIds: z.array(z.string()),
})
type FormValues = z.infer<typeof schema>

const isBusinessScoped = (role: Role) => role === 'CLIENT' || role === 'RESELLER'

interface UserFormModalProps {
  open: boolean
  onClose: () => void
  user?: PlatformUser | null   // present → edit mode
}

export function UserFormModal({ open, onClose, user }: UserFormModalProps) {
  const isEdit = !!user
  const queryClient = useQueryClient()
  const { data: businesses } = useBusinesses()
  const [businessSearch, setBusinessSearch] = useState('')
  const [formError, setFormError] = useState<string | null>(null)

  const {
    register, handleSubmit, control, watch, setValue, reset,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      name: '', email: '', password: '', role: 'ANALYST', status: 'ACTIVE', assignedBusinessIds: [],
    },
  })

  // Reset the form whenever the modal opens or the target user changes.
  useEffect(() => {
    if (!open) return
    setFormError(null)
    setBusinessSearch('')
    reset(user
      ? {
          name: user.name, email: user.email, password: '',
          role: user.role, status: user.status,
          assignedBusinessIds: user.assignedBusinessIds ?? [],
        }
      : { name: '', email: '', password: '', role: 'ANALYST', status: 'ACTIVE', assignedBusinessIds: [] })
  }, [open, user, reset])

  const role = watch('role')
  const selectedBusinessIds = watch('assignedBusinessIds')

  const filteredBusinesses = useMemo(() => {
    const q = businessSearch.trim().toLowerCase()
    const list = businesses ?? []
    if (!q) return list
    return list.filter(b => b.title?.toLowerCase().includes(q))
  }, [businesses, businessSearch])

  const toggleBusiness = (id: string) => {
    const set = new Set(selectedBusinessIds)
    if (set.has(id)) set.delete(id); else set.add(id)
    setValue('assignedBusinessIds', Array.from(set), { shouldValidate: true })
  }

  const mutation = useMutation({
    mutationFn: (values: FormValues) => {
      const businessIds = isBusinessScoped(values.role) ? values.assignedBusinessIds : []
      if (isEdit && user) {
        return updateUser(user.id, {
          name: values.name,
          role: values.role,
          status: values.status,
          password: values.password?.trim() ? values.password : undefined,
          assignedBusinessIds: businessIds,
        })
      }
      return createUser({
        name: values.name,
        email: values.email,
        password: values.password ?? '',
        role: values.role,
        assignedBusinessIds: businessIds,
      })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] })
      queryClient.invalidateQueries({ queryKey: ['access-summary'] })
      onClose()
    },
    onError: (err) => setFormError(apiErrorMessage(err)),
  })

  const onSubmit = (values: FormValues) => {
    setFormError(null)
    // Password required on create; optional on edit (blank = keep existing).
    if (!isEdit && (!values.password || values.password.length < 6)) {
      setFormError('Password must be at least 6 characters')
      return
    }
    if (isEdit && values.password && values.password.length > 0 && values.password.length < 6) {
      setFormError('New password must be at least 6 characters')
      return
    }
    if (values.role === 'CLIENT' && values.assignedBusinessIds.length === 0) {
      setFormError('A Client must be assigned at least one business')
      return
    }
    mutation.mutate(values)
  }

  const inputClass = 'bg-[#0a0a0a] border-[#2a2a2a] text-white placeholder:text-[#444]'

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose() }}>
      <DialogContent className="bg-[#111] border-[#1e1e1e] text-white max-w-lg max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle className="text-white">{isEdit ? 'Edit User' : 'Add User'}</DialogTitle>
          <DialogDescription className="text-[#666]">
            {isEdit
              ? 'Update role, access scope, status, or reset the password.'
              : 'Create a platform user. They log in immediately with the password you set.'}
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit(onSubmit)} className="flex flex-col gap-4 mt-2 min-w-0">
          <div className="grid grid-cols-2 gap-3">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="name" className="text-[#ccc]">Name *</Label>
              <Input id="name" className={inputClass} placeholder="Priya Nair" {...register('name')} />
              {errors.name && <span className="text-xs text-red-400">{errors.name.message}</span>}
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="email" className="text-[#ccc]">Email *</Label>
              <Input
                id="email"
                className={inputClass}
                placeholder="priya@discovery.in"
                disabled={isEdit}
                {...register('email')}
              />
              {errors.email && <span className="text-xs text-red-400">{errors.email.message}</span>}
            </div>
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="password" className="text-[#ccc]">
              {isEdit ? 'Set new password (optional)' : 'Password *'}
            </Label>
            <Input
              id="password"
              type="password"
              className={inputClass}
              placeholder={isEdit ? 'Leave blank to keep current' : 'At least 6 characters'}
              autoComplete="new-password"
              {...register('password')}
            />
          </div>

          <div className={`grid gap-3 ${isEdit ? 'grid-cols-2' : 'grid-cols-1'}`}>
            <div className="flex flex-col gap-1.5">
              <Label className="text-[#ccc]">Role *</Label>
              <Controller
                control={control}
                name="role"
                render={({ field }) => (
                  <Select value={field.value} onValueChange={field.onChange}>
                    <SelectTrigger className={inputClass}>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent className="bg-[#111] border-[#2a2a2a] text-white">
                      {ROLE_ORDER.map(r => (
                        <SelectItem key={r} value={r} className="focus:bg-[#1e1e1e]">
                          {ROLE_META[r].label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                )}
              />
            </div>
            {isEdit && (
              <div className="flex flex-col gap-1.5">
                <Label className="text-[#ccc]">Status</Label>
                <Controller
                  control={control}
                  name="status"
                  render={({ field }) => (
                    <Select value={field.value} onValueChange={field.onChange}>
                      <SelectTrigger className={inputClass}>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent className="bg-[#111] border-[#2a2a2a] text-white">
                        <SelectItem value="ACTIVE" className="focus:bg-[#1e1e1e]">Active</SelectItem>
                        <SelectItem value="DISABLED" className="focus:bg-[#1e1e1e]">Disabled (blocked)</SelectItem>
                        <SelectItem value="PENDING" className="focus:bg-[#1e1e1e]">Pending</SelectItem>
                      </SelectContent>
                    </Select>
                  )}
                />
              </div>
            )}
          </div>

          {isBusinessScoped(role) && (
            <div className="flex flex-col gap-1.5 min-w-0">
              <Label className="text-[#ccc]">
                Assigned businesses {role === 'CLIENT' ? '*' : '(portfolio)'}
              </Label>
              <Input
                className={inputClass}
                placeholder="Search businesses…"
                value={businessSearch}
                onChange={e => setBusinessSearch(e.target.value)}
              />
              <div className="max-h-44 min-w-0 overflow-y-auto overflow-x-hidden rounded-md border border-[#2a2a2a] bg-[#0a0a0a] divide-y divide-[#161616]">
                {filteredBusinesses.length === 0 && (
                  <p className="px-3 py-3 text-xs text-[#555]">No businesses found.</p>
                )}
                {filteredBusinesses.map(b => (
                  <label key={b.id} className="flex items-center gap-2 px-3 py-2 cursor-pointer hover:bg-[#111]">
                    <input
                      type="checkbox"
                      className="h-4 w-4 shrink-0 rounded accent-[#00ff88]"
                      checked={selectedBusinessIds.includes(b.id)}
                      onChange={() => toggleBusiness(b.id)}
                    />
                    <span className="min-w-0 flex-1 truncate text-sm text-[#ccc]" title={b.title}>{b.title}</span>
                  </label>
                ))}
              </div>
              <span className="text-xs text-[#555]">{selectedBusinessIds.length} selected</span>
            </div>
          )}

          {formError && <p className="text-xs text-red-400">{formError}</p>}

          <DialogFooter className="mt-2">
            <Button type="button" variant="ghost" onClick={onClose} className="text-[#666] hover:text-white">
              Cancel
            </Button>
            <Button
              type="submit"
              disabled={mutation.isPending}
              className="bg-[#00ff88] text-[#0a0a0a] font-semibold hover:bg-[#00cc6a]"
            >
              {mutation.isPending ? 'Saving…' : isEdit ? 'Save Changes' : 'Add User'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
