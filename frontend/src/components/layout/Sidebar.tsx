import { NavLink } from 'react-router-dom'
import { Activity, Cpu, Play, TrendingUp, Building2, BarChart2, Shield, MapPin } from 'lucide-react'
import { cn } from '@/lib/utils'
import { useContainerPool } from '@/hooks/useContainerPool'
import { useAuth } from '@/context/AuthContext'
import { canAccess } from '@/lib/access'
import { UserBadge } from '@/components/shared/UserBadge'

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
  const { user } = useAuth()

  // Show only the sections the current role may reach; a client's business item points
  // straight at their own business detail (no list) and is relabelled "My Business".
  const navItems = user
    ? NAV_ITEMS
        .filter(item => canAccess(user.role, item.to))
        .map(item => {
          if (user.role === 'CLIENT' && item.to === '/businesses') {
            const businessId = user.assignedBusinessIds[0]
            return businessId
              ? { ...item, to: `/businesses/${businessId}`, label: 'My Business' }
              : item
          }
          return item
        })
    : NAV_ITEMS

  return (
    <aside className="w-[360px] shrink-0 h-full border-r border-[#1e1e1e] bg-[#0a0a0a] flex flex-col">
      <div className="px-4 py-5 border-b border-[#1e1e1e]">
        <span className="text-sm font-semibold text-white tracking-tight">Discovery</span>
        <span className="ml-1 text-sm font-semibold text-[#00ff88]">Ops</span>
      </div>

      <nav className="flex-1 overflow-y-auto py-2">
        {navItems.map(({ to, icon: Icon, label }) => (
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

      <div className="flex flex-col gap-3 border-t border-[#1e1e1e] p-3">
        {/* System status — green when the backend is reachable; container count in tooltip */}
        <div
          className="flex items-center gap-2 rounded-lg border border-[#1e1e1e] bg-[#0d0d0d] px-3 py-2"
          title={pool ? `${pool.activeSlots}/${pool.poolSize} containers active` : undefined}
        >
          <span
            className={cn(
              'h-2 w-2 rounded-full',
              pool ? 'bg-[#00ff88] animate-pulse' : 'bg-[#666]'
            )}
          />
          <span className="font-mono text-xs text-[#888]">
            {pool ? 'all systems online' : 'connecting…'}
          </span>
        </div>

        <UserBadge />
      </div>
    </aside>
  )
}
