export type Role = 'OPERATOR' | 'ANALYST' | 'CLIENT' | 'RESELLER'
export type UserStatus = 'ACTIVE' | 'PENDING' | 'DISABLED'

// One row in the Access & Roles user list (backend UserSummaryDto).
export interface PlatformUser {
  id: string
  name: string
  email: string
  role: Role
  status: UserStatus
  assignedBusinessIds: string[]
  assignedBusinessNames: string[]
  lastLoginAt: string | null
  createdAt: string | null
}

// Top-of-page tile counts (backend AccessSummaryDto).
export interface AccessSummary {
  totalUsers: number
  internalUsers: number
  externalUsers: number
  operators: number
  analysts: number
  clients: number
  resellers: number
  pendingInvites: number
  totalBusinesses: number
}

export interface CreateUserRequest {
  name: string
  email: string
  password: string
  role: Role
  assignedBusinessIds: string[]
}

// All fields optional — only sent values are applied (PATCH).
export interface UpdateUserRequest {
  name?: string
  role?: Role
  status?: UserStatus
  password?: string
  assignedBusinessIds?: string[]
}

// The logged-in identity (backend CurrentUserDto).
export interface CurrentUser {
  id: string
  name: string
  email: string
  role: Role
  status: UserStatus
  assignedBusinessIds: string[]
}
