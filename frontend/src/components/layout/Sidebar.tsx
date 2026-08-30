import { useEffect, useState } from 'react'
import { NavLink } from 'react-router-dom'
import { Activity, Cpu, Play, TrendingUp, Building2, BarChart2, Shield, MapPin, PanelLeftClose, PanelLeftOpen } from 'lucide-react'
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

const COLLAPSE_KEY = 'ops-sidebar-collapsed'

export function Sidebar() {
  const { data: pool } = useContainerPool()
  const { user } = useAuth()
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem(COLLAPSE_KEY) === '1')

  useEffect(() => {
    localStorage.setItem(COLLAPSE_KEY, collapsed ? '1' : '0')
  }, [collapsed])

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
    <aside
      className={cn(
        'shrink-0 h-full border-r border-[#1e1e1e] bg-[#0a0a0a] flex flex-col transition-[width] duration-200',
        collapsed ? 'w-[64px]' : 'w-[360px]'
      )}
    >
      <div className={cn(
        'flex items-center border-b border-[#1e1e1e] py-5',
        collapsed ? 'justify-center px-0' : 'justify-between px-4'
      )}>
        {!collapsed && (
          <span>
            <span className="text-sm font-semibold text-white tracking-tight">Discovery</span>
            <span className="ml-1 text-sm font-semibold text-[#00ff88]">Ops</span>
          </span>
        )}
        <button
          onClick={() => setCollapsed(c => !c)}
          title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          className="text-[#555] hover:text-white transition-colors"
        >
          {collapsed
            ? <PanelLeftOpen className="h-4 w-4" />
            : <PanelLeftClose className="h-4 w-4" />}
        </button>
      </div>

      <nav className="flex-1 overflow-y-auto py-2">
        {navItems.map(({ to, icon: Icon, label }) => (
          <NavLink
            key={to}
            to={to}
            end={to === '/'}
            title={collapsed ? label : undefined}
            className={({ isActive }) =>
              cn(
                'flex items-center py-2.5 text-sm transition-colors',
                collapsed ? 'justify-center px-0' : 'gap-3 px-4',
                isActive
                  ? cn('text-white border-l-2 border-[#00ff88] bg-[#111]', !collapsed && 'pl-[14px]')
                  : 'text-[#666] hover:text-[#ccc] border-l-2 border-transparent hover:bg-[#0f0f0f]'
              )
            }
          >
            <Icon className="h-4 w-4 shrink-0" />
            {!collapsed && label}
          </NavLink>
        ))}
      </nav>

      <div className="flex flex-col gap-3 border-t border-[#1e1e1e] p-3">
        {/* System status — green when the backend is reachable; container count in tooltip */}
        <div
          className={cn(
            'flex items-center rounded-lg border border-[#1e1e1e] bg-[#0d0d0d] py-2',
            collapsed ? 'justify-center px-0' : 'gap-2 px-3'
          )}
          title={pool ? `${pool.activeSlots}/${pool.poolSize} containers active` : 'connecting…'}
        >
          <span
            className={cn(
              'h-2 w-2 rounded-full',
              pool ? 'bg-[#00ff88] animate-pulse' : 'bg-[#666]'
            )}
          />
          {!collapsed && (
            <span className="font-mono text-xs text-[#888]">
              {pool ? 'all systems online' : 'connecting…'}
            </span>
          )}
        </div>

        <UserBadge compact={collapsed} />
      </div>
    </aside>
  )
}
