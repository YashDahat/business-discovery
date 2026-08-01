import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthProvider } from '@/context/AuthContext'
import { RequireAuth } from '@/components/auth/RequireAuth'
import { AppShell } from '@/components/layout/AppShell'
import MissionControlPage from '@/pages/MissionControlPage'
import AgentsPage from '@/pages/AgentsPage'
import AgentRunsPage from '@/pages/AgentRunsPage'
import AgentRunDetailPage from '@/pages/AgentRunDetailPage'
import PipelineFunnelPage from '@/pages/PipelineFunnelPage'
import BusinessIntelPage from '@/pages/BusinessIntelPage'
import BusinessDetailPage from '@/pages/BusinessDetailPage'
import KpiFrameworkPage from '@/pages/KpiFrameworkPage'
import AccessRolesPage from '@/pages/AccessRolesPage'
import ProfilePage from '@/pages/ProfilePage'
import LoginPage from '@/pages/LoginPage'
import ScraperPage from '@/pages/ScraperPage'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 0,
      retry: 2,
      refetchOnWindowFocus: false,
    },
  },
})

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <Routes>
            {/* Public — the only route reachable without a session */}
            <Route path="/login" element={<LoginPage />} />
            {/* Everything else requires login; RequireAuth also enforces per-role route access */}
            <Route element={<RequireAuth />}>
              <Route element={<AppShell />}>
                <Route index element={<MissionControlPage />} />
                <Route path="agents" element={<AgentsPage />} />
                <Route path="runs" element={<AgentRunsPage />} />
                <Route path="runs/:runId" element={<AgentRunDetailPage />} />
                <Route path="pipeline" element={<PipelineFunnelPage />} />
                <Route path="businesses" element={<BusinessIntelPage />} />
                <Route path="businesses/:businessId" element={<BusinessDetailPage />} />
                <Route path="kpi" element={<KpiFrameworkPage />} />
                <Route path="access" element={<AccessRolesPage />} />
                <Route path="profile" element={<ProfilePage />} />
                <Route path="scraper" element={<ScraperPage />} />
                <Route path="*" element={<Navigate to="/" replace />} />
              </Route>
            </Route>
          </Routes>
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  )
}
