import { useState, type FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { changePassword } from '@/services/accessService'
import { apiErrorMessage } from '@/lib/apiError'
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

/** Self-service password change for the logged-in user, opened from the profile screen. */
export function ChangePasswordModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  const reset = () => {
    setCurrent(''); setNext(''); setConfirm(''); setError(null); setDone(false)
  }
  const close = () => { reset(); onClose() }

  const mutation = useMutation({
    mutationFn: () => changePassword(current, next),
    onSuccess: () => {
      setDone(true)
      setTimeout(close, 900)
    },
    onError: (e) => setError(apiErrorMessage(e)),
  })

  const submit = (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    if (next.length < 6) { setError('New password must be at least 6 characters'); return }
    if (next !== confirm) { setError('New passwords do not match'); return }
    mutation.mutate()
  }

  const inputClass = 'bg-[#0a0a0a] border-[#2a2a2a] text-white placeholder:text-[#444]'

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) close() }}>
      <DialogContent className="bg-[#111] border-[#1e1e1e] text-white max-w-md">
        <DialogHeader>
          <DialogTitle className="text-white">Change Password</DialogTitle>
          <DialogDescription className="text-[#666]">
            Enter your current password and choose a new one.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={submit} className="mt-2 flex flex-col gap-4">
          <div className="flex flex-col gap-1.5">
            <Label className="text-[#ccc]">Current password</Label>
            <Input
              type="password" autoComplete="current-password"
              value={current} onChange={e => setCurrent(e.target.value)}
              className={inputClass}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label className="text-[#ccc]">New password</Label>
            <Input
              type="password" autoComplete="new-password"
              value={next} onChange={e => setNext(e.target.value)}
              className={inputClass} placeholder="At least 6 characters"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label className="text-[#ccc]">Confirm new password</Label>
            <Input
              type="password" autoComplete="new-password"
              value={confirm} onChange={e => setConfirm(e.target.value)}
              className={inputClass}
            />
          </div>

          {error && <p className="text-xs text-red-400">{error}</p>}
          {done && <p className="text-xs text-[#00ff88]">Password updated.</p>}

          <DialogFooter className="mt-2">
            <Button type="button" variant="ghost" onClick={close} className="text-[#666] hover:text-white">
              Cancel
            </Button>
            <Button
              type="submit"
              disabled={mutation.isPending || done}
              className="bg-[#00ff88] text-[#0a0a0a] font-semibold hover:bg-[#00cc6a]"
            >
              {mutation.isPending ? 'Updating…' : 'Update Password'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
