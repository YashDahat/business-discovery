import { apiClient } from '@/api/client'
import type { BusinessSummary } from '@/types/businesses'
import type { BusinessDetailResponse } from '@/types/businessDetail'

export const getBusinesses = (params: { search?: string; category?: string; tier?: string; runId?: string }) =>
  apiClient.get<BusinessSummary[]>('/api/v1/scraper/businesses', { params }).then(r => r.data)

export const getBusinessById = (id: string) =>
  apiClient.get<BusinessDetailResponse>(`/api/v1/scraper/businesses/${id}`).then(r => r.data)

export const runCoderAgent = (briefId: string, runId: string, businessId: string) =>
  apiClient.post('/api/v3/coder/run', { briefId, runId, businessId }).then(r => r.data)

export const generateBrief = (businessId: string) =>
  apiClient.post(`/api/v2/architect/business/${businessId}/brief`).then(r => r.data)
