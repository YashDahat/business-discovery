import { useAgentsSummary } from '@/hooks/useAgentsSummary'
import { StatusBadge } from '@/components/shared/StatusBadge'
import { MetricCard } from '@/components/shared/MetricCard'
import { SparklineBar } from '@/components/shared/SparklineBar'
import { LoadingSpinner } from '@/components/shared/LoadingSpinner'
import { ErrorState } from '@/components/shared/ErrorState'

export default function AgentsPage() {
  const { data: summary, isLoading, isError } = useAgentsSummary()

  if (isLoading) return <LoadingSpinner />
  if (isError) return <ErrorState />

  return (
    <div className="flex flex-col gap-4">
      {summary?.agents.map(agent => {
        const isComingSoon = agent.phase >= 4
        return (
          <div
            key={agent.phase}
            className="relative rounded-lg border border-[#1e1e1e] bg-[#111] p-5"
          >
            {isComingSoon && (
              <div className="absolute inset-0 rounded-lg bg-[#0a0a0a]/70 flex items-center justify-center z-10">
                <span className="text-sm text-[#555]">Coming in Phase {agent.phase}</span>
              </div>
            )}

            <div className="flex items-start justify-between mb-4">
              <div>
                <div className="flex items-center gap-2 mb-1">
                  <span className="text-xs font-mono text-[#555]">Phase {agent.phase}</span>
                  <StatusBadge status={agent.status} />
                </div>
                <h3 className="text-base font-semibold text-white">{agent.name}</h3>
                <p className="text-sm text-[#666]">{agent.description}</p>
              </div>
            </div>

            {Object.keys(agent.metrics).length > 0 && (
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 mt-4 pt-4 border-t border-[#1e1e1e]">
                {Object.entries(agent.metrics).map(([key, val]) => {
                  const isRate = key.toLowerCase().includes('pct') || key.toLowerCase().includes('rate')
                  const numVal = Number(val)
                  return isRate ? (
                    <SparklineBar
                      key={key}
                      value={numVal}
                      max={100}
                      label={key.replace(/([A-Z])/g, ' $1').trim()}
                    />
                  ) : (
                    <MetricCard
                      key={key}
                      label={key.replace(/([A-Z])/g, ' $1').trim()}
                      value={typeof val === 'number' ? val.toLocaleString() : String(val)}
                    />
                  )
                })}
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
}
