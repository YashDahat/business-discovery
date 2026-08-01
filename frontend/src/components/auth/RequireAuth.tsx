import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '@/context/AuthContext'
import { LoadingSpinner } from '@/components/shared/LoadingSpinner'
import { canAccess, roleHome } from '@/lib/access'

/**
 * Gates the console: while the session is resolving show a spinner; if signed out send
 * to /login (remembering where they were headed); if signed in but the role can't view
 * the current route, bounce to that role's home.
 */
export function RequireAuth() {
  const { user, loading } = useAuth()
  const location = useLocation()

  if (loading) {
    return (
      <div className="flex h-screen items-center justify-center bg-[#0a0a0a]">
        <LoadingSpinner />
      </div>
    )
  }

  if (!user) {
    return <Navigate to="/login" replace state={{ from: location }} />
  }

  const home = roleHome(user)

  // Client has no business-list table — the bare /businesses goes to their own business.
  if (user.role === 'CLIENT' && location.pathname === '/businesses') {
    return <Navigate to={home} replace />
  }

  if (!canAccess(user.role, location.pathname)) {
    return <Navigate to={home} replace />
  }

  return <Outlet />
}
