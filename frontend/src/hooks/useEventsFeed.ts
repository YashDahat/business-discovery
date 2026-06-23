import { useQuery } from '@tanstack/react-query'
import { getEventsFeed } from '@/services/opsService'

export const useEventsFeed = () =>
  useQuery({
    queryKey: ['events-feed'],
    queryFn: getEventsFeed,
    refetchInterval: 5_000,
  })
