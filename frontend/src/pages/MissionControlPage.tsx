import { useAgentsSummary } from '@/hooks/useAgentsSummary'
import { useEventsFeed } from '@/hooks/useEventsFeed'
import { useContainerPool } from '@/hooks/useContainerPool'
import { MetricCard } from '@/components/shared/MetricCard'
import { StatusBadge } from '@/components/shared/StatusBadge'
import { EventLogRow } from '@/components/shared/EventLogRow'
import { LoadingSpinner } from '@/components/shared/LoadingSpinner'

export default function MissionControlPage() {
  const { data: summary, isLoading: summaryLoading } = useAgentsSummary()
  const { data: events } = useEventsFeed()
  const { data: pool } = useContainerPool()

  const architect = summary?.agents.find(a => a.phase === 2)
  const coder = summary?.agents.find(a => a.phase === 3)

  const totalRuns      = Number(architect?.metrics?.totalRuns ?? 0)
  const briefsGen      = Number(architect?.metrics?.briefsGenerated ?? 0)
  const liveContainers = Number(coder?.metrics?.liveContainers ?? 0)
  const completedSites = Number(coder?.metrics?.completedSites ?? 0)

  return (
    <div className="flex flex-col gap-6">
      {/* Header metrics */}
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-4">
        <div className="rounded-lg border border-[#1e1e1e] bg-[#111] p-4">
          <MetricCard label="Total Runs" value={totalRuns} />
        </div>
        <div className="rounded-lg border border-[#1e1e1e] bg-[#111] p-4">
          <MetricCard label="Briefs Generated" value={briefsGen} />
        </div>
        <div className="rounded-lg border border-[#1e1e1e] bg-[#111] p-4">
          <MetricCard label="Active Containers" value={liveContainers} />
        </div>
        <div className="rounded-lg border border-[#1e1e1e] bg-[#111] p-4">
          <MetricCard label="Sites Built" value={completedSites} />
        </div>
        <div className="rounded-lg border border-[#1e1e1e] bg-[#111] p-4">
          <MetricCard
            label="Pool Usage"
            value={pool ? `${pool.activeSlots}/${pool.poolSize}` : '—'}
            accent={false}
          />
        </div>
      </div>

      {/* Agent mini-cards */}
      {summaryLoading ? (
        <LoadingSpinner />
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-3">
          {summary?.agents.map(agent => (
            <div key={agent.phase} className="rounded-lg border border-[#1e1e1e] bg-[#111] p-4 flex flex-col gap-2">
              <div className="flex items-center justify-between">
                <span className="text-xs text-[#666]">Phase {agent.phase}</span>
                <StatusBadge status={agent.status} />
              </div>
              <span className="text-sm font-medium text-white">{agent.name}</span>
              <span className="text-xs text-[#555]">{agent.description}</span>
              {Object.entries(agent.metrics).slice(0, 2).map(([k, v]) => (
                <div key={k} className="flex items-baseline justify-between">
                  <span className="text-xs text-[#666]">{k.replace(/([A-Z])/g, ' $1').trim()}</span>
                  <span className="text-sm font-semibold text-[#00ff88]">{String(v)}</span>
                </div>
              ))}
            </div>
          ))}
        </div>
      )}

      {/* Live event log */}
      <div className="rounded-lg border border-[#1e1e1e] bg-[#111] flex flex-col">
        <div className="px-4 py-3 border-b border-[#1e1e1e] flex items-center gap-2">
          <span className="h-2 w-2 rounded-full bg-[#00ff88] animate-pulse" />
          <span className="text-sm font-medium text-white">Live Event Feed</span>
          <span className="ml-auto text-xs text-[#555]">polling every 5s</span>
        </div>
        <div className="px-4 overflow-y-auto" style={{ maxHeight: '360px' }}>
          {!events || events.length === 0 ? (
            <div className="py-8 text-center text-sm text-[#555]">No events yet</div>
          ) : (
            events.map(event => <EventLogRow key={event.id} event={event} />)
          )}
        </div>
      </div>
    </div>
  )
}
