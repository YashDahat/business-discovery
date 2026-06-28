export interface PoolStatus {
  poolSize: number
  activeSlots: number
  availableSlots: number
  isSlotAvailable: boolean
}

export type ContainerTaskStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'RETRYING'
export type ContainerFailureType = 'CODE' | 'INFRA' | 'CONFIG_AUTH'

export interface ContainerTask {
  id: string
  briefId: string
  businessId: string
  runId: string
  status: ContainerTaskStatus
  attemptCount: number
  maxAttempts: number
  failureType: ContainerFailureType | null
  errorMessage: string | null
  dockerContainerId: string | null
  githubRepoUrl: string | null
  githubBranch: string | null
  githubPrUrl: string | null
  spawnedAt: string | null
  completedAt: string | null
  createdAt: string
}

export interface TaskLogsResponse {
  taskId: string
  status: string
  source: 'stored' | 'live'
  logs: string
}

export type GeneratedFileType = 'BACKEND' | 'FRONTEND' | 'INFRA' | 'CONFIG'
export type GeneratedFileStatus = 'PENDING' | 'GENERATED' | 'VALIDATED' | 'SPEC_COMPLIANT' | 'GENERATION_FAILED' | 'FAILED'

export interface GeneratedFile {
  id: string
  taskId: string
  filePath: string
  fileType: GeneratedFileType
  status: GeneratedFileStatus
  inputTokenCount: number | null
  outputTokenCount: number | null
  attemptNumber: number
  errorMessage: string | null
  createdAt: string
}
