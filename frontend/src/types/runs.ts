export type AgentRunStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED'

export interface AgentRun {
  id: string
  keyword: string
  category: string | null
  location: string | null
  status: AgentRunStatus
  currentStep: string | null
  scrapedCount: number | null
  filteredCount: number | null
  briefId: string | null
  errorMessage: string | null
  createdAt: string
  updatedAt: string | null
  completedAt: string | null
}

export interface ArchitectRunStatusResponse {
  runId: string
  keyword: string
  category: string | null
  location: string | null
  status: AgentRunStatus
  currentStep: string | null
  scrapedCount: number | null
  filteredCount: number | null
  briefId: string | null
  errorMessage: string | null
  createdAt: string
  completedAt: string | null
}

export interface ArchitectRunRequest {
  keyword: string
  lang?: string
  depth?: number
  zoom?: number
  lat?: string
  lon?: string
  fastMode?: boolean
  radius?: number
  email?: boolean
  maxTime?: number
}

export interface ArchitectRunResponse {
  runId: string
  status: string
  message: string
}
