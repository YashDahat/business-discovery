import { useQuery } from '@tanstack/react-query'
import { getRunPipeline } from '@/services/architectService'
import type { PipelineResponse } from '@/types/pipeline'

export const useRunPipeline = (runId: string) =>
  useQuery({
    queryKey: ['run-pipeline', runId],
    queryFn: () => getRunPipeline(runId),
    enabled: !!runId,
    refetchInterval: (query) => {
      const data = query.state.data as PipelineResponse | undefined
      if (!data) return 5_000
      return data.status === 'COMPLETED' || data.status === 'FAILED' ? false : 5_000
    },
  })
