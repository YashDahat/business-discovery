export type DemoStatus = 'PULLING' | 'STARTING' | 'RUNNING' | 'FAILED' | 'STOPPED'

export interface DemoInstance {
  id: string
  briefId: string
  slug: string
  imageRef: string
  hostPort: number | null
  status: DemoStatus
  demoUrl: string | null
  errorMessage: string | null
  startedAt: string | null
  expiresAt: string | null
}
