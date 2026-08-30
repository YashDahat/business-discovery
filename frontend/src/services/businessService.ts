import { apiClient } from '@/api/client'
import type { BusinessSummary } from '@/types/businesses'
import type { BusinessDetailResponse } from '@/types/businessDetail'
import type { ContainerTask } from '@/types/containers'

export const getBusinesses = (params: { search?: string; category?: string; tier?: string; runId?: string }) =>
  apiClient.get<BusinessSummary[]>('/api/v1/scraper/businesses', { params }).then(r => r.data)

export const getBusinessById = (id: string) =>
  apiClient.get<BusinessDetailResponse>(`/api/v1/scraper/businesses/${id}`).then(r => r.data)

export const runCoderAgent = (briefId: string, runId: string, businessId: string) =>
  apiClient.post('/api/v3/coder/run', { briefId, runId, businessId }).then(r => r.data)

export const generateBrief = (businessId: string) =>
  apiClient.post(`/api/v2/architect/business/${businessId}/brief`).then(r => r.data)

export const getTasksForBrief = (briefId: string) =>
  apiClient.get<ContainerTask[]>(`/api/v3/coder/brief/${briefId}/tasks`).then(r => r.data)

export interface ChatMessageView {
  role: 'user' | 'ai' | 'system'
  content: string
}

// Cline-backed, read-only project Q&A. Separate from the change-request thread above:
// this never resets the worker task / regenerates the site. Shares the same brief
// chat_memory session, so history renders with the same ChatMessageView shape.
export interface ClineChatResult { sessionId: number; reply: string }

export const getClineChat = (briefId: string) =>
  apiClient.get<ChatMessageView[]>(`/api/v4/cline/brief/${briefId}/chat`).then(r => r.data)

export const sendClineChatMessage = (briefId: string, message: string, signal?: AbortSignal) =>
  apiClient.post<ClineChatResult>(`/api/v4/cline/brief/${briefId}/chat`, { message }, { signal }).then(r => r.data)

// Start a fresh chat session for the brief (clear chat / new session).
export const startNewClineSession = (briefId: string) =>
  apiClient.post<{ sessionId: number }>(`/api/v4/cline/brief/${briefId}/chat/new`).then(r => r.data)

// Live stepper feed: the MCP tool operations (git/code/web/brief) Cline performs during a turn.
// Polled while a message is in flight; turnSeq changes each turn so stale steps can be discarded.
export type ClineStepStatus = 'running' | 'done' | 'error'
export interface ClineStep {
  id: string
  tool: string
  label: string
  status: ClineStepStatus
  detail?: string | null
  ts: number
}
export interface ClineStepsView { turnSeq: number; steps: ClineStep[] }

export const getClineSteps = (briefId: string) =>
  apiClient.get<ClineStepsView>(`/api/v4/cline/brief/${briefId}/steps`).then(r => r.data)

export type BriefGenerationStatus = 'GENERATING' | 'COMPLETED' | 'FAILED' | 'NOT_STARTED'
export interface BriefStatusResponse { status: BriefGenerationStatus; businessId: string; error?: string }

export const getBriefStatus = (businessId: string) =>
  apiClient.get<BriefStatusResponse>(`/api/v2/architect/business/${businessId}/brief/status`).then(r => r.data)
