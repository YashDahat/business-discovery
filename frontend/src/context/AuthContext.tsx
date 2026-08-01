import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { getMe, login as loginRequest, logout as logoutRequest } from '@/services/accessService'
import type { CurrentUser } from '@/types/access'

interface AuthState {
  user: CurrentUser | null
  loading: boolean
  login: (email: string, password: string) => Promise<CurrentUser>
  logout: () => Promise<void>
  refresh: () => Promise<void>
}

const AuthContext = createContext<AuthState | undefined>(undefined)

/**
 * Fetches /api/auth/me on mount to hydrate the current session (the session cookie is
 * sent automatically — same origin). Exposes login/logout used by LoginPage and TopBar.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null)
  const [loading, setLoading] = useState(true)

  const refresh = useCallback(async () => {
    try {
      setUser(await getMe())
    } catch {
      setUser(null)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void refresh() }, [refresh])

  const login = useCallback(async (email: string, password: string) => {
    const current = await loginRequest(email, password)
    setUser(current)
    return current
  }, [])

  const logout = useCallback(async () => {
    await logoutRequest()
    setUser(null)
  }, [])

  return (
    <AuthContext.Provider value={{ user, loading, login, logout, refresh }}>
      {children}
    </AuthContext.Provider>
  )
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within an AuthProvider')
  return ctx
}
