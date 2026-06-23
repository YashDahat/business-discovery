export type PipelineStageStatus = 'passed' | 'running' | 'pending' | 'failed'

export interface PipelineStage {
  name: string
  description: string
  status: PipelineStageStatus
}

export interface PipelineResponse {
  runId: string
  status: string
  query: string
  location: string | null
  scraped: number
  tier1: number
  briefs: number
  sitesLive: number
  stages: PipelineStage[]
}
