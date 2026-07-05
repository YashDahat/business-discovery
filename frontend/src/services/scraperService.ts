import { apiClient } from '@/api/client'

export type ScraperContainerState = 'RUNNING' | 'STOPPED' | 'NOT_FOUND' | 'ERROR'

export interface ContainerStatusResponse {
  state: ScraperContainerState
  containerId: string
  message?: string
}

export interface ScrapeJobRequest {
  keyword:  string
  depth:    number
  radius:   number
  fastMode: boolean
  email:    boolean
  lat?:     string
  lon?:     string
  zoom?:    number
  maxTime?: number
}

export type JobStatus = 'pending' | 'running' | 'in_progress' | 'ok' | 'failed' | 'error'

export interface JobStatusResponse { jobId: string; status: JobStatus }
export interface PersistResponse   { count: number; message: string }

export const getScraperContainerStatus = () =>
  apiClient.get<ContainerStatusResponse>('/api/v1/scraper/container/status').then(r => r.data)

export const startScraperContainer = () =>
  apiClient.post<ContainerStatusResponse>('/api/v1/scraper/container/start').then(r => r.data)

export const stopScraperContainer = () =>
  apiClient.post<ContainerStatusResponse>('/api/v1/scraper/container/stop').then(r => r.data)

export const submitScrapeJob = (req: ScrapeJobRequest) =>
  apiClient.post<JobStatusResponse>('/api/v1/scraper/jobs', req).then(r => r.data)

export const getScrapeJobStatus = (jobId: string) =>
  apiClient.get<JobStatusResponse>(`/api/v1/scraper/jobs/${jobId}/status`).then(r => r.data)

export const persistScrapeResults = (jobId: string) =>
  apiClient.post<PersistResponse>(`/api/v1/scraper/jobs/${jobId}/persist`).then(r => r.data)

export interface ContainerLogsResponse { logs: string; state: ScraperContainerState }

export const getScraperContainerLogs = () =>
  apiClient.get<ContainerLogsResponse>('/api/v1/scraper/container/logs').then(r => r.data)
