import { apiClient } from '@/api/client'
import type { PoolStatus } from '@/types/containers'

export const getPoolStatus = () =>
  apiClient.get<PoolStatus>('/api/v3/containers/pool/status').then(r => r.data)
