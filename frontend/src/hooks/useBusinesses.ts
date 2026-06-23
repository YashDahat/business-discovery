import { useQuery } from '@tanstack/react-query'
import { getBusinesses } from '@/services/businessService'

interface BusinessFilters {
  search?: string
  category?: string
  tier?: string
  runId?: string
}

export const useBusinesses = (filters: BusinessFilters = {}) =>
  useQuery({
    queryKey: ['businesses', filters],
    queryFn: () => getBusinesses(filters),
    refetchInterval: false,
  })
