import { useAgentsSummary } from '@/hooks/useAgentsSummary'
import { useEventsFeed } from '@/hooks/useEventsFeed'
import { useContainerPool } from '@/hooks/useContainerPool'
import { LoadingSpinner } from '@/components/shared/LoadingSpinner'
import { cn } from '@/lib/utils'
import type { AgentEvent } from '@/types/events'

// ── Sparkline ─────────────────────────────────────────────────────────────────

function Sparkline({ points, color = '#00ff88' }: {
  points: number[]
  color?: string
}) {
  const w = 80, h = 32
  const min = Math.min(...points), max = Math.max(...points)
  const range = max - min || 1
  const xs = points.map((_, i) => (i / (points.length - 1)) * w)
  const ys = points.map(p => h - ((p - min) / range) * (h - 4) - 2)
  const d = xs.map((x, i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${ys[i].toFixed(1)}`).join(' ')
  return (
    <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`} className="shrink-0">
      <polyline points={xs.map((x, i) => `${x.toFixed(1)},${ys[i].toFixed(1)}`).join(' ')}
        fill="none" stroke={color} strokeWidth="1.5" strokeLinejoin="round" strokeLinecap="round" />
      <defs>
        <linearGradient id={`g${color.replace('#', '')}`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity="0.18" />
          <stop offset="100%" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={`${d} L${w},${h} L0,${h} Z`}
        fill={`url(#g${color.replace('#', '')})`} />
    </svg>
  )
}

// ── KPI card ──────────────────────────────────────────────────────────────────

function KpiCard({ label, value, trend, trendUp, subtitle, sparkPoints, sparkColor }: {
  label: string
  value: string
  trend: string
  trendUp: boolean | null  // null = neutral
  subtitle: string
  sparkPoints: number[]
  sparkColor?: string
}) {
  return (
    <div className="rounded-2xl border border-[#1e1e1e] bg-[#111] p-4 flex flex-col gap-1 min-w-0">
      <div className="flex items-start justify-between gap-2">
        <span className="text-[10px] font-mono uppercase tracking-widest text-[#555] leading-tight">{label}</span>
        {trend && (
          <span className={cn(
            'text-[10px] font-mono font-semibold shrink-0 flex items-center gap-0.5',
            trendUp === true  ? 'text-[#00ff88]' :
            trendUp === false ? 'text-amber-400'  : 'text-[#555]'
          )}>
            {trendUp === true ? '▲' : trendUp === false ? '▼' : ''} {trend}
          </span>
        )}
      </div>
      <div className="flex items-end justify-between gap-2 mt-1">
        <div>
          <div className="text-2xl font-bold text-white leading-none">{value}</div>
          <div className="text-[11px] text-[#555] mt-1.5 leading-tight">{subtitle}</div>
        </div>
        <Sparkline points={sparkPoints} color={sparkColor ?? '#00ff88'} />
      </div>
    </div>
  )
}

// ── Phase card ────────────────────────────────────────────────────────────────

const PHASE_STATUS_COLOR: Record<string, string> = {
  RUNNING: 'bg-[#00ff88]',
  IDLE:    'bg-[#00ff88]',
  FAILED:  'bg-red-500',
  PENDING: 'bg-amber-400',
}

function PhaseCard({ phase, name, description, status, metrics }: {
  phase: number
  name: string
  description: string
  status: string
  metrics: Record<string, number | string>
}) {
  const entries = Object.entries(metrics).slice(0, 2)
  const dotColor = PHASE_STATUS_COLOR[status] ?? 'bg-[#2a2a2a]'

  return (
    <div className="flex-1 min-w-0 rounded-xl border border-[#1e1e1e] bg-[#0d0d0d] p-4 flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <span className="text-[10px] font-mono uppercase tracking-widest text-[#555]">Phase {phase}</span>
        <span className={cn('h-2 w-2 rounded-full shrink-0', dotColor)} />
      </div>
      <div>
        <div className="text-base font-bold text-white leading-tight">{name}</div>
        <div className="text-[11px] text-[#666] mt-0.5 leading-snug">{description}</div>
      </div>
      {entries.length > 0 && (
        <div className="flex items-end gap-4 mt-auto">
          {entries.map(([k, v]) => (
            <div key={k}>
              <div className={cn(
                'text-lg font-bold leading-none',
                phase === 3 ? 'text-[#00ff88]' : 'text-white'
              )}>{String(v)}</div>
              <div className="text-[10px] text-[#555] mt-0.5 leading-tight">
                {k.replace(/([A-Z])/g, ' $1').trim().toLowerCase()}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

// ── Event log ─────────────────────────────────────────────────────────────────

const SOURCE_LABEL: Record<string, { label: string; color: string }> = {
  LLM_CALL:          { label: 'gemini',   color: 'text-purple-400' },
  BRIEF_GENERATED:   { label: 'gemini',   color: 'text-purple-400' },
  SCRAPE_SUBMITTED:  { label: 'scraper',  color: 'text-amber-400'  },
  SCRAPE_COMPLETED:  { label: 'scraper',  color: 'text-amber-400'  },
  BUSINESSES_SCORED: { label: 'scoring',  color: 'text-cyan-400'   },
  STEP_COMPLETED:    { label: 'coder',    color: 'text-[#00ff88]'  },
  STEP_STARTED:      { label: 'coder',    color: 'text-[#00ff88]'  },
  TOOL_CALL:         { label: 'tool',     color: 'text-blue-400'   },
  STEP_FAILED:       { label: 'error',    color: 'text-red-400'    },
  ERROR:             { label: 'error',    color: 'text-red-400'    },
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

function TerminalRow({ event }: { event: AgentEvent }) {
  const src = SOURCE_LABEL[event.eventType] ?? { label: event.eventType.toLowerCase(), color: 'text-[#888]' }
  return (
    <div className="flex items-start gap-3 py-1 font-mono text-[11px] border-b border-[#111] last:border-0">
      <span className="text-[#444] shrink-0 w-[62px]">{formatTime(event.createdAt)}</span>
      <span className={cn('shrink-0 w-16', src.color)}>{src.label}</span>
      <span className="text-[#888] truncate">
        {[event.stepName, event.message].filter(Boolean).join(' · ')}
      </span>
    </div>
  )
}

// ── Infra card ────────────────────────────────────────────────────────────────

function InfraCard({ dot, label, value, sub }: {
  dot: 'green' | 'blue' | 'amber' | 'red'
  label: string
  value: string
  sub: string
}) {
  const dotCls = {
    green: 'bg-[#00ff88]',
    blue:  'bg-blue-400',
    amber: 'bg-amber-400',
    red:   'bg-red-400',
  }[dot]

  return (
    <div className="rounded-2xl border border-[#1e1e1e] bg-[#111] p-4 flex flex-col gap-2">
      <div className="flex items-center gap-2">
        <span className={cn('h-2 w-2 rounded-full shrink-0', dotCls)} />
        <span className="text-[10px] font-mono uppercase tracking-widest text-[#555]">{label}</span>
      </div>
      <div className="text-2xl font-bold text-white leading-none">{value}</div>
      <div className="text-[11px] font-mono text-[#555]">{sub}</div>
    </div>
  )
}

// ── Page ──────────────────────────────────────────────────────────────────────

const UP_SPARK   = [12, 15, 13, 18, 16, 20, 22, 19, 24, 26]
const FLAT_SPARK = [18, 17, 19, 18, 20, 19, 18, 20, 19, 18]
const DOWN_SPARK = [26, 24, 22, 23, 20, 19, 18, 16, 15, 14]

export default function MissionControlPage() {
  const { data: summary, isLoading: summaryLoading } = useAgentsSummary()
  const { data: events } = useEventsFeed()
  const { data: pool } = useContainerPool()

  const coder = summary?.agents.find(a => a.phase === 3)
  const liveContainers = Number(coder?.metrics?.liveContainers ?? 0)
  const poolSize = pool?.poolSize ?? 10
  const activeSlots = pool?.activeSlots ?? liveContainers

  const activeRun = summary?.totalRunning ? `run #A-${2291 + summary.totalRunning} active` : null

  return (
    <div className="flex flex-col gap-4">

      {/* ── KPI row ─────────────────────────────────────────────────────── */}
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3">
        <KpiCard label="MRR"              value="₹8.80L"  trend="18%"  trendUp={true}  subtitle="this month"      sparkPoints={UP_SPARK}   />
        <KpiCard label="Active Clients"   value="90"      trend="12"   trendUp={true}  subtitle="+12 vs Apr"      sparkPoints={UP_SPARK}   />
        <KpiCard label="Agent Runs Today" value={String(summary?.agents.reduce((s, a) => s + Number(a.metrics?.totalRuns ?? 0), 0) || 37)}
                                                          trend="6"    trendUp={true}  subtitle="4 in queue"      sparkPoints={FLAT_SPARK} />
        <KpiCard label="Pipeline Health"  value="94%"     trend="2%"   trendUp={true}  subtitle="all stages ok"   sparkPoints={UP_SPARK}   />
        <KpiCard label="Brief Success"    value="96%"     trend="1%"   trendUp={true}  subtitle="Gemini 2.5"      sparkPoints={UP_SPARK}   />
        <KpiCard label="Cost / Site"      value="$1.42"   trend="9%"   trendUp={false} subtitle="caching on"      sparkPoints={DOWN_SPARK} sparkColor="#f59e0b" />
      </div>

      {/* ── Autonomous Agent Pipeline ────────────────────────────────────── */}
      <div className="rounded-2xl border border-[#1e1e1e] bg-[#111] p-5">
        <div className="flex items-center justify-between mb-5">
          <span className="text-[11px] font-mono uppercase tracking-widest text-[#555]">
            // AUTONOMOUS AGENT PIPELINE
          </span>
          {activeRun && (
            <span className="text-[11px] font-mono text-[#00ff88]">{activeRun}</span>
          )}
        </div>

        {summaryLoading ? (
          <LoadingSpinner />
        ) : (
          <div className="flex items-stretch gap-2">
            {(summary?.agents ?? FALLBACK_AGENTS).map((agent, i, arr) => (
              <div key={agent.phase} className="flex items-center gap-2 flex-1 min-w-0">
                <PhaseCard
                  phase={agent.phase}
                  name={agent.name}
                  description={agent.description}
                  status={agent.status}
                  metrics={agent.metrics}
                />
                {i < arr.length - 1 && (
                  <span className="text-[#2a2a2a] text-lg shrink-0 font-bold">›</span>
                )}
              </div>
            ))}
          </div>
        )}
      </div>

      {/* ── Bottom row ───────────────────────────────────────────────────── */}
      <div className="grid grid-cols-1 lg:grid-cols-[1fr_320px] gap-4">

        {/* Terminal event log */}
        <div className="rounded-2xl border border-[#1e1e1e] bg-[#0a0a0a] overflow-hidden flex flex-col">
          {/* Title bar */}
          <div className="flex items-center gap-3 px-4 py-3 border-b border-[#1a1a1a] shrink-0">
            <div className="flex items-center gap-1.5">
              <span className="h-3 w-3 rounded-full bg-[#ff5f56]" />
              <span className="h-3 w-3 rounded-full bg-[#ffbd2e]" />
              <span className="h-3 w-3 rounded-full bg-[#27c93f]" />
            </div>
            <span className="text-[11px] font-mono text-[#555] flex-1">
              agent.event.stream — tail -f
            </span>
            <div className="flex items-center gap-1.5">
              <span className="h-1.5 w-1.5 rounded-full bg-[#00ff88] animate-pulse" />
              <span className="text-[10px] font-mono text-[#00ff88]">live</span>
            </div>
          </div>

          {/* Log rows */}
          <div className="flex-1 overflow-y-auto px-4 py-2" style={{ maxHeight: '340px' }}>
            {!events || events.length === 0 ? (
              <div className="py-8 text-center text-[11px] font-mono text-[#333]">
                waiting for events…
              </div>
            ) : (
              events.map(event => <TerminalRow key={event.id} event={event} />)
            )}
          </div>
        </div>

        {/* Infra grid */}
        <div className="grid grid-cols-2 gap-3 content-start">
          <InfraCard
            dot="green"
            label="PostgreSQL"
            value="18.4 GB"
            sub="RDS · 12ms p95"
          />
          <InfraCard
            dot="blue"
            label="Agent Queue"
            value={`${summary?.totalRunning ?? 4} jobs`}
            sub="LangGraph4j"
          />
          <InfraCard
            dot="green"
            label="Containers"
            value={`${activeSlots} / ${poolSize}`}
            sub="Docker · EC2"
          />
          <InfraCard
            dot="amber"
            label="Error Rate"
            value="1.2%"
            sub="last 24h"
          />
        </div>
      </div>
    </div>
  )
}

// Fallback when API is loading / unavailable
const FALLBACK_AGENTS: { phase: number; name: string; description: string; status: string; metrics: Record<string, string | number> }[] = [
  { phase: 1, name: 'Chat Memory',     description: 'Conversational Intelligence',    status: 'IDLE',    metrics: { sessions: 1284, msgsStored: '42.6k' } },
  { phase: 2, name: 'Architect Agent', description: 'Business Intelligence Engine',   status: 'IDLE',    metrics: { scrapedPerRun: 112, tier1Rate: '20.5%' } },
  { phase: 3, name: 'Coder Agent',     description: 'Automated Website Generation',   status: 'RUNNING', metrics: { liveContainers: 6, prSuccess: '81%' } },
  { phase: 4, name: 'Demo & Deploy',   description: 'Demo & Deployment Agent',        status: 'PENDING', metrics: { demosLive: 9, approval: '42%' } },
  { phase: 5, name: 'Live Analytics',  description: 'Performance Collection',         status: 'IDLE',    metrics: { sitesLive: 18, eventsPerDay: '31.4k' } },
]
