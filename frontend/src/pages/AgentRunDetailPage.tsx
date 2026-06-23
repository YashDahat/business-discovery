import { useParams, useNavigate } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'
import type { ColDef } from 'ag-grid-community'
import { useRunPipeline } from '@/hooks/useRunPipeline'
import { useRunEvents } from '@/hooks/useRunEvents'
import { useBusinesses } from '@/hooks/useBusinesses'
import { StatusBadge } from '@/components/shared/StatusBadge'
import { PipelineStageBadge } from '@/components/shared/PipelineStageBadge'
import { EventLogRow } from '@/components/shared/EventLogRow'
import { MetricCard } from '@/components/shared/MetricCard'
import { DataTable } from '@/components/shared/DataTable'
import { LoadingSpinner } from '@/components/shared/LoadingSpinner'
import { ErrorState } from '@/components/shared/ErrorState'
import type { PipelineStageStatus } from '@/types/pipeline'
import type { BusinessSummary, OpsStatus } from '@/types/businesses'

const TIER_STYLE: Record<string, string> = {
  TIER_1:      'bg-emerald-950 text-emerald-400',
  TIER_2:      'bg-amber-950 text-amber-400',
  HAS_WEBSITE: 'bg-blue-950 text-blue-400',
  EXCLUDED:    'bg-[#1e1e1e] text-gray-500',
}

const OPS_STATUS_STYLE: Record<OpsStatus, string> = {
  LIVE:       'bg-emerald-950 text-emerald-400',
  DEMO:       'bg-blue-950 text-blue-400',
  GENERATING: 'bg-amber-950 text-amber-400',
  BRIEF:      'bg-purple-950 text-purple-400',
  SCRAPE:     'bg-[#1e1e1e] text-gray-400',
}

const BUSINESS_COLUMNS: ColDef<BusinessSummary>[] = [
  {
    headerName: 'Business',
    field: 'title',
    flex: 2,
    cellRenderer: ({ value }: { value: string }) => (
      <span className="text-sm font-medium text-white">{value}</span>
    ),
  },
  {
    headerName: 'Category',
    field: 'category',
    flex: 1,
    cellRenderer: ({ value }: { value: string | null }) => (
      <span className="text-xs text-[#888]">{value ?? '—'}</span>
    ),
  },
  {
    headerName: 'Rating',
    field: 'rating',
    maxWidth: 90,
    cellRenderer: ({ value }: { value: number | null }) =>
      value != null ? (
        <span className="text-sm text-amber-400">★ {value.toFixed(1)}</span>
      ) : <span className="text-[#555]">—</span>,
  },
  {
    headerName: 'Reviews',
    field: 'reviewCount',
    maxWidth: 100,
    cellRenderer: ({ value }: { value: number | null }) => (
      <span className="text-sm tabular-nums">{value?.toLocaleString() ?? '—'}</span>
    ),
  },
  {
    headerName: 'Revenue Est.',
    field: 'revenueEstimate',
    flex: 1,
    cellRenderer: ({ value }: { value: string | null }) => (
      <span className="text-sm text-[#00ff88]">{value ?? '—'}</span>
    ),
  },
  {
    headerName: 'Tier',
    field: 'businessTier',
    maxWidth: 110,
    cellRenderer: ({ value }: { value: string | null }) => {
      if (!value) return <span className="text-[#555]">—</span>
      const cls = TIER_STYLE[value] ?? 'bg-[#1e1e1e] text-gray-400'
      return (
        <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${cls}`}>
          {value.replace(/_/g, ' ')}
        </span>
      )
    },
  },
  {
    headerName: 'Status',
    field: 'opsStatus',
    maxWidth: 120,
    cellRenderer: ({ value }: { value: OpsStatus }) => {
      const cls = OPS_STATUS_STYLE[value] ?? 'bg-[#1e1e1e] text-gray-400'
      return (
        <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${cls}`}>
          {value}
        </span>
      )
    },
  },
]

export default function AgentRunDetailPage() {
  const { runId } = useParams<{ runId: string }>()
  const navigate = useNavigate()

  const { data: pipeline, isLoading: pipelineLoading, isError: pipelineError } = useRunPipeline(runId ?? '')
  const { data: events } = useRunEvents(runId ?? '')
  const { data: businesses = [] } = useBusinesses({ runId })

  if (pipelineLoading) return <LoadingSpinner />
  if (pipelineError || !pipeline) return <ErrorState />

  return (
    <div className="flex flex-col gap-6">
      {/* Back + header */}
      <div className="flex items-center gap-3">
        <button
          onClick={() => navigate(-1)}
          className="flex items-center gap-1.5 text-sm text-[#666] hover:text-white transition-colors"
        >
          <ArrowLeft className="h-4 w-4" />
          Back
        </button>
        <span className="text-[#333]">/</span>
        <span className="font-mono text-sm text-[#00ff88]">{runId?.slice(0, 8)}</span>
        <span className="ml-auto">
          <StatusBadge status={pipeline.status} />
        </span>
      </div>

      {/* Header stats */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
        {[
          { label: 'Scraped',    value: pipeline.scraped    },
          { label: 'Tier-1',     value: pipeline.tier1      },
          { label: 'Briefs',     value: pipeline.briefs     },
          { label: 'Sites Live', value: pipeline.sitesLive  },
        ].map(({ label, value }) => (
          <div key={label} className="rounded-lg border border-[#1e1e1e] bg-[#111] p-4">
            <MetricCard label={label} value={value} />
          </div>
        ))}
      </div>

      {/* Query + location */}
      <div className="rounded-lg border border-[#1e1e1e] bg-[#111] px-4 py-3 flex items-center gap-3">
        <span className="text-xs text-[#555]">Query</span>
        <span className="text-sm text-white font-medium">{pipeline.query}</span>
        {pipeline.location && (
          <>
            <span className="text-[#333]">·</span>
            <span className="text-sm text-[#888]">{pipeline.location}</span>
          </>
        )}
      </div>

      {/* 8-stage pipeline */}
      <div className="rounded-lg border border-[#1e1e1e] bg-[#111] p-5">
        <h2 className="text-sm font-semibold text-white mb-4">Pipeline Stages</h2>
        <div className="flex flex-col gap-3 sm:grid sm:grid-cols-4 sm:gap-3 lg:grid-cols-8">
          {pipeline.stages.map((stage, idx) => (
            <div
              key={idx}
              className="flex flex-row sm:flex-col items-center sm:items-start gap-2 sm:gap-1.5 rounded-md border border-[#1e1e1e] bg-[#0a0a0a] px-3 py-2.5"
            >
              <PipelineStageBadge status={stage.status as PipelineStageStatus} />
              <div className="sm:mt-1">
                <div className="text-xs font-medium text-white">{stage.name}</div>
                <div className="text-[10px] text-[#555] mt-0.5">{stage.description}</div>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Businesses scraped in this run */}
      <div className="rounded-lg border border-[#1e1e1e] bg-[#111]">
        <div className="px-4 py-3 border-b border-[#1e1e1e] flex items-center gap-2">
          <span className="text-sm font-medium text-white">Businesses</span>
          <span className="text-xs text-[#555]">({businesses.length})</span>
        </div>
        <div className="p-4">
          {businesses.length === 0 ? (
            <div className="py-8 text-center text-sm text-[#555]">No businesses found for this run</div>
          ) : (
            <DataTable
              rowData={businesses}
              columnDefs={BUSINESS_COLUMNS}
              height="360px"
              onRowClicked={(row) => navigate(`/businesses/${row.id}`)}
            />
          )}
        </div>
      </div>

      {/* Event log for this run */}
      <div className="rounded-lg border border-[#1e1e1e] bg-[#111]">
        <div className="px-4 py-3 border-b border-[#1e1e1e]">
          <span className="text-sm font-medium text-white">Run Event Log</span>
          <span className="ml-2 text-xs text-[#555]">({events?.length ?? 0} events)</span>
        </div>
        <div className="px-4 overflow-y-auto" style={{ maxHeight: '400px' }}>
          {!events?.length ? (
            <div className="py-8 text-center text-sm text-[#555]">No events for this run</div>
          ) : (
            events.map(e => <EventLogRow key={e.id} event={e} />)
          )}
        </div>
      </div>
    </div>
  )
}
