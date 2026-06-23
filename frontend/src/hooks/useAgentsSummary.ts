import { useQuery } from '@tanstack/react-query'
import { getAgentsSummary } from '@/services/opsService'

export const useAgentsSummary = () =>
  useQuery({
    queryKey: ['agents-summary'],
    queryFn: getAgentsSummary,
    refetchInterval: 10_000,
  })
