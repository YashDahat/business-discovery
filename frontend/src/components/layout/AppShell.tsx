import { useState } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import { Sidebar } from './Sidebar'
import { TopBar } from './TopBar'
import { NewRunModal } from '@/components/modals/NewRunModal'

const PAGE_TITLES: Record<string, string> = {
  '/':            'Mission Control',
  '/agents':      'Agents',
  '/runs':        'Agent Runs',
  '/pipeline':    'Pipeline Funnel',
  '/businesses':  'Business Intel',
  '/kpi':         'KPI Framework',
  '/access':      'Access & Roles',
}

function getTitle(pathname: string): string {
  if (pathname.startsWith('/runs/'))       return 'Run Detail'
  if (pathname.startsWith('/businesses/')) return 'Business Detail'
  return PAGE_TITLES[pathname] ?? 'Discovery Ops'
}

export function AppShell() {
  const [newRunOpen, setNewRunOpen] = useState(false)
  const { pathname } = useLocation()

  return (
    <div className="flex h-screen overflow-hidden bg-[#0a0a0a]">
      <Sidebar />
      <div className="flex flex-1 flex-col overflow-hidden">
        <TopBar title={getTitle(pathname)} onNewRun={() => setNewRunOpen(true)} />
        <main className="flex-1 overflow-y-auto p-6">
          <Outlet />
        </main>
      </div>
      <NewRunModal open={newRunOpen} onClose={() => setNewRunOpen(false)} />
    </div>
  )
}
