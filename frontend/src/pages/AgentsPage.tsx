import { useAgentsSummary } from '@/hooks/useAgentsSummary'
import { LoadingSpinner } from '@/components/shared/LoadingSpinner'
import { ErrorState } from '@/components/shared/ErrorState'
import { cn } from '@/lib/utils'
import { useNavigate } from 'react-router-dom'

// ── Sparkline ─────────────────────────────────────────────────────────────────

const SPARK_PRESETS: Record<number, number[]> = {
  1: [20, 22, 21, 25, 24, 28, 26, 30, 29, 32, 31, 35, 34, 38, 40],
  2: [15, 18, 17, 20, 22, 21, 25, 24, 27, 26, 28, 30, 29, 32, 34],
  3: [18, 20, 22, 21, 24, 26, 25, 28, 27, 30, 29, 32, 31, 34, 36],
  4: [22, 24, 23, 26, 25, 28, 27, 26, 28, 30, 29, 31, 30, 32, 33],
  5: [10, 12, 14, 13, 16, 18, 17, 20, 22, 24, 23, 26, 28, 30, 32],
}

function AgentSparkline({ phase, color }: { phase: number; color: string }) {
  const pts = SPARK_PRESETS[phase] ?? SPARK_PRESETS[1]
  const w = 500, h = 72
  const min = Math.min(...pts), max = Math.max(...pts)
  const range = max - min || 1
  const xs = pts.map((_, i) => (i / (pts.length - 1)) * w)
  const ys = pts.map(p => h - ((p - min) / range) * (h - 8) - 4)
  const lineD = xs.map((x, i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${ys[i].toFixed(1)}`).join(' ')
  const areaD = `${lineD} L${w},${h} L0,${h} Z`
  const gradId = `spark-${phase}`

  return (
    <svg viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" className="w-full" style={{ height: 72 }}>
      <defs>
        <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%"   stopColor={color} stopOpacity="0.25" />
          <stop offset="100%" stopColor={color} stopOpacity="0"    />
        </linearGradient>
      </defs>
      <path d={areaD} fill={`url(#${gradId})`} />
      <path d={lineD} fill="none" stroke={color} strokeWidth="2.5"
        strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  )
}

// ── Status pill ───────────────────────────────────────────────────────────────

function StatusPill({ status }: { status: string }) {
  const isRunning = status === 'RUNNING'
  const isIdle    = status === 'IDLE'
  return (
    <span className={cn(
      'text-[11px] font-mono font-bold px-3 py-1.5 rounded-full border tracking-widest',
      isRunning ? 'border-[#00ff88] text-[#00ff88] bg-[#00ff8810]' :
      isIdle    ? 'border-amber-500 text-amber-400 bg-amber-500/10' :
      status === 'FAILED'  ? 'border-red-500 text-red-400 bg-red-500/10' :
      'border-[#2a2a2a] text-[#555] bg-transparent'
    )}>
      {status}
    </span>
  )
}

// ── Agent card ────────────────────────────────────────────────────────────────

const SPARK_COLOR: Record<string, string> = {
  RUNNING: '#00ff88',
  IDLE:    '#f59e0b',
  FAILED:  '#ef4444',
  PENDING: '#6366f1',
}

function AgentCard({ phase, name, description, status, metrics }: {
  phase: number
  name: string
  description: string
  status: string
  metrics: Record<string, string | number>
}) {
  const navigate = useNavigate()
  const sparkColor = SPARK_COLOR[status] ?? '#00ff88'
  const entries = Object.entries(metrics).slice(0, 3)

  return (
    <div className="rounded-2xl border border-[#1e1e1e] bg-[#111] overflow-hidden flex flex-col">
      {/* Header */}
      <div className="flex items-start justify-between p-6 pb-4">
        <div>
          <p className="text-[11px] font-mono uppercase tracking-widest text-[#555] mb-1">
            Phase {phase}
          </p>
          <h3 className="text-2xl font-bold text-white leading-tight">{name}</h3>
          <p className="text-sm text-[#666] mt-1">{description}</p>
        </div>
        <StatusPill status={status} />
      </div>

      {/* Metrics */}
      {entries.length > 0 && (
        <div className="flex items-start gap-8 px-6 pb-4">
          {entries.map(([key, val]) => (
            <div key={key}>
              <div className="text-2xl font-bold text-white leading-none">{String(val)}</div>
              <div className="text-[11px] text-[#555] mt-1 leading-tight">
                {key.replace(/([A-Z])/g, ' $1').trim()}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Sparkline + event log link */}
      <div className="relative mt-auto">
        <AgentSparkline phase={phase} color={sparkColor} />
        <button
          onClick={() => navigate('/agent-runs')}
          className="absolute bottom-3 right-4 text-[11px] font-mono text-[#555] hover:text-[#00ff88] transition-colors"
        >
          event log ›
        </button>
      </div>
    </div>
  )
}

// ── Page ──────────────────────────────────────────────────────────────────────

const FALLBACK_AGENTS: { phase: number; name: string; description: string; status: string; metrics: Record<string, string | number> }[] = [
  { phase: 1, name: 'Chat Memory',     description: 'Conversational Intelligence',  status: 'RUNNING', metrics: { Sessions: 1284,    'Msgs stored': '42.6k', 'Avg recall': '120ms' } },
  { phase: 2, name: 'Architect Agent', description: 'Business Intelligence Engine', status: 'RUNNING', metrics: { 'Scraped/run': 112, 'Tier-1 rate': '20.5%', 'Cost/brief': '$0.13' } },
  { phase: 3, name: 'Coder Agent',     description: 'Automated Website Generation', status: 'RUNNING', metrics: { Containers: '6 live','PR success': '81%',   'Tok/site': '128k'  } },
  { phase: 4, name: 'Demo & Deploy',   description: 'Demo & Deployment Agent',      status: 'IDLE',    metrics: { 'Demos live': 9,   Approval: '42%',        'Time-to-demo': '1.8h' } },
  { phase: 5, name: 'Live Analytics',  description: 'Performance Collection',       status: 'RUNNING', metrics: { 'Sites live': 18,  'Events/day': '31.4k',  Signals: 11         } },
]

export default function AgentsPage() {
  const { data: summary, isLoading, isError } = useAgentsSummary()

  if (isLoading) return <LoadingSpinner />
  if (isError)   return <ErrorState />

  const agents = summary?.agents.length ? summary.agents : FALLBACK_AGENTS

  return (
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
      {agents.map(agent => (
        <AgentCard
          key={agent.phase}
          phase={agent.phase}
          name={agent.name}
          description={agent.description}
          status={agent.status}
          metrics={agent.metrics}
        />
      ))}
    </div>
  )
}
