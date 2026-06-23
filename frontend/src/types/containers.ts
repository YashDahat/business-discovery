export interface PoolStatus {
  poolSize: number
  activeSlots: number
  availableSlots: number
  isSlotAvailable: boolean
}

export type ContainerTaskStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'RETRYING'

export interface ContainerTask {
  id: string
  briefId: string
  businessId: string
  runId: string
  status: ContainerTaskStatus
  attemptCount: number
  maxAttempts: number
  githubPrUrl: string | null
  spawnedAt: string | null
  completedAt: string | null
  createdAt: string
}
