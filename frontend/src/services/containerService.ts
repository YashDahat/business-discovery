import { apiClient } from '@/api/client'
import type { PoolStatus, ContainerTask, ContainerTaskStatus, TaskLogsResponse, GeneratedFile } from '@/types/containers'

export const getPoolStatus = () =>
  apiClient.get<PoolStatus>('/api/v3/containers/pool/status').then(r => r.data)

export const getAllTasks = (status?: ContainerTaskStatus) =>
  apiClient.get<ContainerTask[]>('/api/v3/containers/tasks', { params: status ? { status } : {} }).then(r => r.data)

export const getTask = (taskId: string) =>
  apiClient.get<ContainerTask>(`/api/v3/containers/tasks/${taskId}`).then(r => r.data)

export const getTaskLogs = (taskId: string) =>
  apiClient.get<TaskLogsResponse>(`/api/v3/containers/tasks/${taskId}/logs`).then(r => r.data)

export const getTaskFiles = (taskId: string) =>
  apiClient.get<GeneratedFile[]>(`/api/v3/containers/tasks/${taskId}/files`).then(r => r.data)

export const stopTask = (taskId: string) =>
  apiClient.post(`/api/v3/containers/tasks/${taskId}/stop`).then(r => r.data)

export const retryTask = (taskId: string) =>
  apiClient.post(`/api/v3/containers/tasks/${taskId}/retry`).then(r => r.data)

export const respawnForBrief = (briefId: string) =>
  apiClient.post(`/api/v3/containers/brief/${briefId}/respawn`).then(r => r.data)

export const cleanupAll = () =>
  apiClient.post('/api/v3/containers/cleanup/all').then(r => r.data)

export const triggerMonitorScan = () =>
  apiClient.post('/api/v3/containers/monitor/scan').then(r => r.data)
