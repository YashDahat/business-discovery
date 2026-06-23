import { useQuery } from '@tanstack/react-query'
import { getBusinessById } from '@/services/businessService'

const ACTIVE_TASK_STATUSES = new Set(['PENDING', 'RUNNING', 'RETRYING'])

export const useBusinessDetail = (id: string, pollForBrief = false) =>
  useQuery({
    queryKey: ['business', id],
    queryFn: () => getBusinessById(id),
    enabled: !!id,
    refetchInterval: (query) => {
      const data = query.state.data
      if (!data) return false
      // Stop polling for brief once it arrives
      if (pollForBrief && data.brief) return false
      // Poll while brief generation was triggered and brief hasn't arrived yet
      if (pollForBrief && !data.brief) return 6_000
      // Poll while coder task is active
      const taskStatus = data.latestTask?.status
      if (taskStatus && ACTIVE_TASK_STATUSES.has(taskStatus)) return 5_000
      return false
    },
  })
