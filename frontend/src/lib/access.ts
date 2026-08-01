import type { CurrentUser, Role } from '@/types/access'

// Route prefixes each role may reach in the console. '/profile' is available to everyone
// (opened from the user badge). OPERATOR uses '*' = everything.
const ROLE_ALLOW: Record<Role, string[]> = {
  OPERATOR: ['*'],
  ANALYST:  ['/businesses', '/kpi', '/profile'],
  CLIENT:   ['/businesses', '/profile'],
  RESELLER: ['/kpi', '/profile'],
}

// Landing route per role — also where a disallowed navigation is redirected.
// Must be a route the role can access (see ROLE_ALLOW) or RequireAuth would loop.
export const ROLE_HOME: Record<Role, string> = {
  OPERATOR: '/',
  ANALYST:  '/businesses',
  CLIENT:   '/businesses',
  RESELLER: '/kpi',
}

/** Whether a role may view a given path (prefix match; '/' matches only the index). */
export function canAccess(role: Role, pathname: string): boolean {
  const allow = ROLE_ALLOW[role]
  if (allow.includes('*')) return true
  return allow.some(prefix =>
    prefix === '/' ? pathname === '/' : pathname === prefix || pathname.startsWith(prefix + '/'),
  )
}

/**
 * Effective landing route for a user. A CLIENT goes straight to their own business
 * detail (no business-list table); everyone else uses the static ROLE_HOME.
 */
export function roleHome(user: CurrentUser): string {
  if (user.role === 'CLIENT') {
    const businessId = user.assignedBusinessIds[0]
    return businessId ? `/businesses/${businessId}` : '/profile'
  }
  return ROLE_HOME[user.role]
}
