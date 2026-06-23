export type EventType =
  | 'STEP_STARTED'
  | 'STEP_COMPLETED'
  | 'STEP_FAILED'
  | 'TOOL_CALL'
  | 'LLM_CALL'
  | 'SCRAPE_SUBMITTED'
  | 'SCRAPE_COMPLETED'
  | 'BUSINESSES_SCORED'
  | 'BRIEF_GENERATED'
  | 'ERROR'

export interface AgentEvent {
  id: string
  runId: string
  stepName: string | null
  eventType: EventType
  message: string | null
  payload: Record<string, unknown> | null
  durationMs: number | null
  createdAt: string
}
