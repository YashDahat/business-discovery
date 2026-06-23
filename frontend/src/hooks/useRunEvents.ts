import { useQuery } from '@tanstack/react-query'
import { getRunEvents } from '@/services/architectService'

export const useRunEvents = (runId: string) =>
  useQuery({
    queryKey: ['run-events', runId],
    queryFn: () => getRunEvents(runId),
    enabled: !!runId,
    refetchInterval: false,
  })
