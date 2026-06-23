import { useRuns } from '@/hooks/useRuns'
import { useAgentsSummary } from '@/hooks/useAgentsSummary'
import { MetricCard } from '@/components/shared/MetricCard'
import { LoadingSpinner } from '@/components/shared/LoadingSpinner'

export default function KpiFrameworkPage() {
  const { data: runs, isLoading: runsLoading } = useRuns()
  const { data: summary } = useAgentsSummary()

  if (runsLoading) return <LoadingSpinner />

  const completedRuns = runs?.filter(r => r.status === 'COMPLETED') ?? []
  const totalScraped  = runs?.reduce((s, r) => s + (r.scrapedCount ?? 0), 0) ?? 0
  const totalTier1    = runs?.reduce((s, r) => s + (r.filteredCount ?? 0), 0) ?? 0
  const avgScraped    = completedRuns.length > 0 ? Math.round(totalScraped / completedRuns.length) : 0
  const tier1Rate     = totalScraped > 0 ? ((totalTier1 / totalScraped) * 100).toFixed(1) : '0'
  const briefsGen     = Number(summary?.agents.find(a => a.phase === 2)?.metrics?.briefsGenerated ?? 0)
  const completedSites = Number(summary?.agents.find(a => a.phase === 3)?.metrics?.completedSites ?? 0)
  const totalTasks    = Number(summary?.agents.find(a => a.phase === 3)?.metrics?.totalTasks ?? 0)
  const successRate   = totalTasks > 0 ? ((completedSites / totalTasks) * 100).toFixed(1) : '0'

  const avgRunMs = completedRuns.reduce((sum, r) => {
    if (!r.completedAt || !r.createdAt) return sum
    return sum + (new Date(r.completedAt).getTime() - new Date(r.createdAt).getTime())
  }, 0)
  const avgRunMin = completedRuns.length > 0 ? Math.round(avgRunMs / completedRuns.length / 60000) : 0

  return (
    <div className="flex flex-col gap-6">
      {/* Agent Performance — live */}
      <div className="rounded-lg border border-[#1e1e1e] bg-[#111] p-5">
        <h2 className="text-sm font-semibold text-white mb-4">Agent Performance</h2>
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-6">
          <MetricCard label="Total Runs"           value={runs?.length ?? 0}   />
          <MetricCard label="Completed Runs"        value={completedRuns.length} />
          <MetricCard label="Avg Scraped / Run"     value={avgScraped}           />
          <MetricCard label="Avg Tier-1 Rate"       value={`${tier1Rate}%`}      />
          <MetricCard label="Briefs Generated"      value={briefsGen}            />
          <MetricCard label="Container Success"     value={`${successRate}%`}    />
        </div>
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-6 mt-6 pt-5 border-t border-[#1e1e1e]">
          <MetricCard label="Sites Built"           value={completedSites}       />
          <MetricCard label="Avg Run Duration"      value={avgRunMin} unit="min" />
          <MetricCard label="Active Containers"     value={summary?.agents.find(a => a.phase === 3)?.metrics?.liveContainers ?? 0} />
        </div>
      </div>

      {/* Business Performance — placeholder */}
      <div className="rounded-lg border border-[#1e1e1e] bg-[#111] p-5 opacity-40">
        <h2 className="text-sm font-semibold text-white mb-1">Business Performance</h2>
        <p className="text-xs text-[#555] mb-4">Available in Phase 4 — Demo & Deployment</p>
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-6">
          {['Revenue / Client (₹/mo)', 'Client Retention Rate', 'Demo → Contract Rate', 'Avg Contract Value', 'MRR', 'Churn Rate'].map(label => (
            <div key={label} className="flex flex-col gap-0.5">
              <span className="text-2xl font-bold tabular-nums text-[#333]">—</span>
              <span className="text-xs text-[#444]">{label}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
