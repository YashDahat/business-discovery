import { useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '@/context/AuthContext'
import { apiErrorMessage } from '@/lib/apiError'
import { LoginBackdrop } from '@/components/auth/LoginBackdrop'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

export default function LoginPage() {
  const { user, loading, login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname ?? '/'

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  // Already signed in — bounce to where they were headed.
  if (!loading && user) return <Navigate to={from} replace />

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      await login(email.trim(), password)
      navigate(from, { replace: true })
    } catch (err) {
      setError(apiErrorMessage(err, 'Invalid email or password'))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="relative flex h-screen items-center justify-center overflow-hidden bg-[#0a0a0a] px-4">
      <LoginBackdrop />
      <div className="relative z-10 w-full max-w-sm">
        <div className="mb-6 text-center">
          <span className="text-lg font-semibold tracking-tight text-white">Discovery</span>
          <span className="ml-1 text-lg font-semibold text-[#00ff88]">Ops</span>
          <p className="mt-1 text-xs text-[#666]">Sign in to the operations console</p>
        </div>

        <form
          onSubmit={onSubmit}
          className="flex flex-col gap-4 rounded-lg border border-[#1e1e1e] bg-[#111]/95 p-6 shadow-2xl shadow-black/60 backdrop-blur-sm"
        >
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="email" className="text-[#ccc]">Email</Label>
            <Input
              id="email"
              type="email"
              autoComplete="username"
              value={email}
              onChange={e => setEmail(e.target.value)}
              className="bg-[#0a0a0a] border-[#2a2a2a] text-white placeholder:text-[#444]"
              placeholder="you@discovery.in"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="password" className="text-[#ccc]">Password</Label>
            <Input
              id="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              className="bg-[#0a0a0a] border-[#2a2a2a] text-white placeholder:text-[#444]"
              placeholder="••••••••"
            />
          </div>

          {error && <p className="text-xs text-red-400">{error}</p>}

          <Button
            type="submit"
            disabled={submitting}
            className="mt-1 bg-[#00ff88] text-[#0a0a0a] font-semibold hover:bg-[#00cc6a]"
          >
            {submitting ? 'Signing in…' : 'Sign In'}
          </Button>
        </form>
      </div>
    </div>
  )
}
