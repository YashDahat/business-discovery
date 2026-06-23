import { useQuery } from '@tanstack/react-query'
import { getPoolStatus } from '@/services/containerService'

export const useContainerPool = () =>
  useQuery({
    queryKey: ['container-pool'],
    queryFn: getPoolStatus,
    refetchInterval: 15_000,
  })
