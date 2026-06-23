import { apiClient } from '@/api/client'
import type { AgentRun, ArchitectRunRequest, ArchitectRunResponse, ArchitectRunStatusResponse } from '@/types/runs'
import type { PipelineResponse } from '@/types/pipeline'
import type { AgentEvent } from '@/types/events'

export const getAllRuns = () =>
  apiClient.get<AgentRun[]>('/api/v2/architect/runs').then(r => r.data)

export const startRun = (req: ArchitectRunRequest) =>
  apiClient.post<ArchitectRunResponse>('/api/v2/architect/run', req).then(r => r.data)

export const getRunStatus = (runId: string) =>
  apiClient.get<ArchitectRunStatusResponse>(`/api/v2/architect/run/${runId}/status`).then(r => r.data)

export const getRunPipeline = (runId: string) =>
  apiClient.get<PipelineResponse>(`/api/v2/architect/run/${runId}/pipeline`).then(r => r.data)

export const getRunEvents = (runId: string) =>
  apiClient.get<AgentEvent[]>(`/api/v2/architect/run/${runId}/events`).then(r => r.data)
