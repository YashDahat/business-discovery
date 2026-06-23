import { useQuery } from '@tanstack/react-query'
import { getAllRuns } from '@/services/architectService'

export const useRuns = () =>
  useQuery({
    queryKey: ['runs'],
    queryFn: getAllRuns,
    refetchInterval: 10_000,
  })
