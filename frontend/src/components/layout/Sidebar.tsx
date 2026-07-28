import { NavLink } from 'react-router-dom'
import { Activity, Cpu, Play, TrendingUp, Building2, BarChart2, Shield, MapPin } from 'lucide-react'
import { cn } from '@/lib/utils'
import { useContainerPool } from '@/hooks/useContainerPool'

const NAV_ITEMS = [
  { to: '/',           icon: Activity,  label: 'Mission Control' },
  { to: '/agents',     icon: Cpu,       label: 'Agents'          },
  { to: '/runs',       icon: Play,      label: 'Agent Runs'      },
  { to: '/pipeline',   icon: TrendingUp, label: 'Pipeline Funnel' },
  { to: '/businesses', icon: Building2, label: 'Business Intel'  },
  { to: '/kpi',        icon: BarChart2, label: 'KPI Framework'   },
  { to: '/access',     icon: Shield,    label: 'Access & Roles'  },
  { to: '/scraper',    icon: MapPin,    label: 'Scraper'         },
]

export function Sidebar() {
  const { data: pool } = useContainerPool()

  return (
    <aside className="w-[360px] shrink-0 h-full border-r border-[#1e1e1e] bg-[#0a0a0a] flex flex-col">
      <div className="px-4 py-5 border-b border-[#1e1e1e]">
        <span className="text-sm font-semibold text-white tracking-tight">Discovery</span>
        <span className="ml-1 text-sm font-semibold text-[#00ff88]">Ops</span>
      </div>

      <nav className="flex-1 overflow-y-auto py-2">
        {NAV_ITEMS.map(({ to, icon: Icon, label }) => (
          <NavLink
            key={to}
            to={to}
            end={to === '/'}
            className={({ isActive }) =>
              cn(
                'flex items-center gap-3 px-4 py-2.5 text-sm transition-colors',
                isActive
                  ? 'text-white border-l-2 border-[#00ff88] bg-[#111] pl-[14px]'
                  : 'text-[#666] hover:text-[#ccc] border-l-2 border-transparent hover:bg-[#0f0f0f]'
              )
            }
          >
            <Icon className="h-4 w-4 shrink-0" />
            {label}
          </NavLink>
        ))}
      </nav>

      {pool && (
        <div className="px-4 py-3 border-t border-[#1e1e1e]">
          <div className="flex items-center gap-2">
            <span
              className={cn(
                'h-2 w-2 rounded-full',
                pool.activeSlots > 0 ? 'bg-[#00ff88] animate-pulse' : 'bg-[#444]'
              )}
            />
            <span className="text-xs text-[#666]">
              {pool.activeSlots}/{pool.poolSize} containers
            </span>
          </div>
        </div>
      )}
    </aside>
  )
}
