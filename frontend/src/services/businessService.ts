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

export const getBriefChat = (briefId: string) =>
  apiClient.get<ChatMessageView[]>(`/api/v3/coder/brief/${briefId}/chat`).then(r => r.data)

export interface ChatSendResult { sessionId: number; reply: string; taskId: string }

export const sendBriefChatMessage = (briefId: string, message: string) =>
  apiClient.post<ChatSendResult>(`/api/v3/coder/brief/${briefId}/chat`, { message }).then(r => r.data)

export type BriefGenerationStatus = 'GENERATING' | 'COMPLETED' | 'FAILED' | 'NOT_STARTED'
export interface BriefStatusResponse { status: BriefGenerationStatus; businessId: string; error?: string }

export const getBriefStatus = (businessId: string) =>
  apiClient.get<BriefStatusResponse>(`/api/v2/architect/business/${businessId}/brief/status`).then(r => r.data)
