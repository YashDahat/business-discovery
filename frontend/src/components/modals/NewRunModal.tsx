import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { startRun } from '@/services/architectService'
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

const schema = z.object({
  keyword:  z.string().min(3, 'Keyword must be at least 3 characters'),
  depth:    z.number().int().min(1).max(20),
  radius:   z.number().int().min(1000).max(50000),
  fastMode: z.boolean(),
})

type FormValues = z.infer<typeof schema>

interface NewRunModalProps {
  open: boolean
  onClose: () => void
}

export function NewRunModal({ open, onClose }: NewRunModalProps) {
  const queryClient = useQueryClient()
  const { register, handleSubmit, formState: { errors }, reset } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { depth: 5, radius: 10000, fastMode: false },
  })

  const mutation = useMutation({
    mutationFn: startRun,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['runs'] })
      reset()
      onClose()
    },
  })

  const onSubmit = (values: FormValues) => mutation.mutate(values)

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose() }}>
      <DialogContent className="bg-[#111] border-[#1e1e1e] text-white max-w-md">
        <DialogHeader>
          <DialogTitle className="text-white">New Discovery Run</DialogTitle>
          <DialogDescription className="text-[#666]">
            Start an autonomous agent run. Returns a run ID immediately.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit(onSubmit)} className="flex flex-col gap-4 mt-2">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="keyword" className="text-[#ccc]">Keyword *</Label>
            <Input
              id="keyword"
              placeholder="e.g. restaurants in Koregaon Park Pune"
              className="bg-[#0a0a0a] border-[#2a2a2a] text-white placeholder:text-[#444]"
              {...register('keyword')}
            />
            {errors.keyword && <span className="text-xs text-red-400">{errors.keyword.message}</span>}
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="depth" className="text-[#ccc]">Depth</Label>
              <Input
                id="depth"
                type="number"
                className="bg-[#0a0a0a] border-[#2a2a2a] text-white"
                {...register('depth', { valueAsNumber: true })}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="radius" className="text-[#ccc]">Radius (m)</Label>
              <Input
                id="radius"
                type="number"
                className="bg-[#0a0a0a] border-[#2a2a2a] text-white"
                {...register('radius', { valueAsNumber: true })}
              />
            </div>
          </div>

          <label className="flex items-center gap-2 cursor-pointer">
            <input
              type="checkbox"
              className="h-4 w-4 rounded accent-[#00ff88]"
              {...register('fastMode')}
            />
            <span className="text-sm text-[#ccc]">Fast mode</span>
          </label>

          {mutation.isError && (
            <p className="text-xs text-red-400">Failed to start run. Check backend is running.</p>
          )}

          <DialogFooter className="mt-2">
            <Button
              type="button"
              variant="ghost"
              onClick={onClose}
              className="text-[#666] hover:text-white"
            >
              Cancel
            </Button>
            <Button
              type="submit"
              disabled={mutation.isPending}
              className="bg-[#00ff88] text-[#0a0a0a] font-semibold hover:bg-[#00cc6a]"
            >
              {mutation.isPending ? 'Starting…' : 'Start Run'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
