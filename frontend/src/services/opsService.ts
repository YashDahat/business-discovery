import { apiClient } from '@/api/client'
import type { AgentsSummaryResponse } from '@/types/agents'
import type { AgentEvent } from '@/types/events'

export const getAgentsSummary = () =>
  apiClient.get<AgentsSummaryResponse>('/api/ops/agents/summary').then(r => r.data)

export const getEventsFeed = () =>
  apiClient.get<AgentEvent[]>('/api/ops/events/feed').then(r => r.data)
