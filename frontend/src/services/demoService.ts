import { apiClient } from '@/api/client'
import type { DemoInstance } from '@/types/demos'

export const startDemo = (briefId: string) =>
  apiClient.post<DemoInstance>(`/api/v4/demo/${briefId}`).then(r => r.data)

export const getDemo = (briefId: string) =>
  apiClient.get<DemoInstance>(`/api/v4/demo/${briefId}`).then(r => r.data)

export const stopDemo = (briefId: string) =>
  apiClient.delete<DemoInstance>(`/api/v4/demo/${briefId}`).then(r => r.data)

export const listDemos = () =>
  apiClient.get<DemoInstance[]>('/api/v4/demo').then(r => r.data)
