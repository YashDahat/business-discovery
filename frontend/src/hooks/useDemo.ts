import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { getDemo, startDemo, stopDemo } from '@/services/demoService'
import type { DemoInstance } from '@/types/demos'

const isSettling = (demo: DemoInstance | undefined) =>
  demo?.status === 'PULLING' || demo?.status === 'STARTING'

/**
 * Demo lifecycle for one brief: start/stop mutations plus a status query that
 * polls every 3s while the demo is pulling/starting and goes quiet once it
 * settles (RUNNING / FAILED / STOPPED).
 */
export const useDemo = (briefId: string | null, enabled: boolean) => {
  const queryClient = useQueryClient()
  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: ['demo', briefId] })

  const demoQuery = useQuery({
    queryKey: ['demo', briefId],
    queryFn: () => getDemo(briefId!),
    enabled: enabled && !!briefId,
    refetchInterval: query => (isSettling(query.state.data) ? 3_000 : false),
    retry: false, // 404 = no demo yet, a normal state — don't hammer the API
  })

  const startMutation = useMutation({
    mutationFn: () => startDemo(briefId!),
    onSuccess: () => invalidate(),
  })

  const stopMutation = useMutation({
    mutationFn: () => stopDemo(briefId!),
    onSuccess: () => invalidate(),
  })

  return { demoQuery, startMutation, stopMutation }
}
