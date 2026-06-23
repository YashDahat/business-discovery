import { useRuns } from '@/hooks/useRuns'
import { useAgentsSummary } from '@/hooks/useAgentsSummary'
import { LoadingSpinner } from '@/components/shared/LoadingSpinner'

interface FunnelStep {
  label: string
  count: number
  target: number
  phase: number
  comingSoon?: boolean
}

function FunnelBar({ step, maxCount }: { step: FunnelStep; maxCount: number }) {
  const pct = maxCount > 0 ? Math.min(100, (step.count / maxCount) * 100) : 0
  const targetPct = maxCount > 0 ? Math.min(100, (step.target / maxCount) * 100) : 0
  const conversionRate = step.count > 0 && step.target > 0
    ? ((step.count / step.target) * 100).toFixed(0)
    : null

  return (
    <div className={`flex items-center gap-4 py-3 border-b border-[#1a1a1a] last:border-0 ${step.comingSoon ? 'opacity-30' : ''}`}>
      <div className="w-32 shrink-0">
        <div className="text-sm font-medium text-white">{step.label}</div>
        {step.comingSoon && <div className="text-xs text-[#555]">Phase {step.phase}+</div>}
      </div>
      <div className="flex-1 relative h-7 rounded-sm bg-[#0a0a0a] overflow-hidden">
        {/* Target line */}
        <div
          className="absolute top-0 h-full border-r-2 border-dashed border-[#333] z-10"
          style={{ left: `${targetPct}%` }}
        />
        {/* Actual bar */}
        <div
          className="h-full rounded-sm bg-[#00ff88]/20 border border-[#00ff88]/30 transition-all duration-500"
          style={{ width: `${pct}%` }}
        />
      </div>
      <div className="w-20 shrink-0 text-right">
        <div className="text-sm font-semibold tabular-nums text-white">
          {step.comingSoon ? '—' : step.count.toLocaleString()}
        </div>
        {conversionRate && !step.comingSoon && (
          <div className="text-xs text-[#666]">{conversionRate}% of target</div>
        )}
      </div>
    </div>
  )
}

export default function PipelineFunnelPage() {
  const { data: runs, isLoading } = useRuns()
  const { data: summary } = useAgentsSummary()

  if (isLoading) return <LoadingSpinner />

  const totalScraped   = runs?.reduce((s, r) => s + (r.scrapedCount ?? 0), 0) ?? 0
  const totalTier1     = runs?.reduce((s, r) => s + (r.filteredCount ?? 0), 0) ?? 0
  const briefsGen      = Number(summary?.agents.find(a => a.phase === 2)?.metrics?.briefsGenerated ?? 0)
  const completedSites = Number(summary?.agents.find(a => a.phase === 3)?.metrics?.completedSites ?? 0)
  const totalRuns      = runs?.length ?? 0

  const steps: FunnelStep[] = [
    { label: 'Keywords',     count: totalRuns,      target: totalRuns,   phase: 2 },
    { label: 'Scraped',      count: totalScraped,   target: totalRuns * 50, phase: 2 },
    { label: 'Tier-1',       count: totalTier1,     target: totalScraped, phase: 2 },
    { label: 'Brief',        count: briefsGen,      target: totalTier1,  phase: 2 },
    { label: 'Code Gen',     count: completedSites, target: briefsGen,   phase: 3 },
    { label: 'Pull Request', count: completedSites, target: completedSites, phase: 3 },
    { label: 'Demo',         count: 0,              target: completedSites, phase: 4, comingSoon: true },
    { label: 'Production',   count: 0,              target: completedSites, phase: 4, comingSoon: true },
  ]

  const maxCount = Math.max(...steps.map(s => s.count), 1)

  return (
    <div className="flex flex-col gap-6">
      <div className="grid grid-cols-3 gap-4">
        {[
          { label: 'Total Scraped',  value: totalScraped  },
          { label: 'Tier-1 Targets', value: totalTier1    },
          { label: 'Sites Built',    value: completedSites },
        ].map(({ label, value }) => (
          <div key={label} className="rounded-lg border border-[#1e1e1e] bg-[#111] p-4">
            <div className="text-2xl font-bold text-[#00ff88] tabular-nums">{value.toLocaleString()}</div>
            <div className="text-xs text-[#666] mt-0.5">{label}</div>
          </div>
        ))}
      </div>

      <div className="rounded-lg border border-[#1e1e1e] bg-[#111] p-5">
        <h2 className="text-sm font-semibold text-white mb-4">Conversion Funnel</h2>
        {steps.map(step => (
          <FunnelBar key={step.label} step={step} maxCount={maxCount} />
        ))}
      </div>

      <div className="rounded-lg border border-[#1e1e1e] bg-[#111] px-4 py-3 text-xs text-[#555]">
        Dashed line = target. Phase 4+ steps (Demo, Production) become active after client approval deployment is built.
      </div>
    </div>
  )
}
