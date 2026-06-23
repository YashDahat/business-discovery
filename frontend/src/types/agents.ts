export type AgentStatus = 'RUNNING' | 'IDLE' | 'FAILED' | 'PENDING'

export interface AgentCard {
  phase: number
  name: string
  description: string
  status: AgentStatus
  metrics: Record<string, number | string>
}

export interface AgentsSummaryResponse {
  agents: AgentCard[]
  totalRunning: number
}
