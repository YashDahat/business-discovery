import { apiClient } from '@/api/client'
import type {
  AccessSummary, CreateUserRequest, CurrentUser, PlatformUser, UpdateUserRequest,
} from '@/types/access'

// ─── Auth ───────────────────────────────────────────────
export const login = (email: string, password: string) =>
  apiClient.post<CurrentUser>('/api/auth/login', { email, password }).then(r => r.data)

export const logout = () =>
  apiClient.post<void>('/api/auth/logout').then(r => r.data)

export const getMe = () =>
  apiClient.get<CurrentUser>('/api/auth/me').then(r => r.data)

// Richer own-account view for the profile screen (adds business names, last login, created).
export const getProfile = () =>
  apiClient.get<PlatformUser>('/api/auth/profile').then(r => r.data)

// Self-service password change (verifies the current password server-side).
export const changePassword = (currentPassword: string, newPassword: string) =>
  apiClient.post<void>('/api/auth/change-password', { currentPassword, newPassword }).then(r => r.data)

// ─── User administration ────────────────────────────────
export const listUsers = () =>
  apiClient.get<PlatformUser[]>('/api/admin/users').then(r => r.data)

export const getAccessSummary = () =>
  apiClient.get<AccessSummary>('/api/admin/users/summary').then(r => r.data)

export const createUser = (body: CreateUserRequest) =>
  apiClient.post<PlatformUser>('/api/admin/users', body).then(r => r.data)

export const updateUser = (id: string, body: UpdateUserRequest) =>
  apiClient.patch<PlatformUser>(`/api/admin/users/${id}`, body).then(r => r.data)

export const deleteUser = (id: string) =>
  apiClient.delete<void>(`/api/admin/users/${id}`).then(r => r.data)
