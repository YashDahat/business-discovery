import { useNavigate } from 'react-router-dom'
import type { ColDef } from 'ag-grid-community'
import { useRuns } from '@/hooks/useRuns'
import { DataTable } from '@/components/shared/DataTable'
import { StatusBadge } from '@/components/shared/StatusBadge'
import { LoadingSpinner } from '@/components/shared/LoadingSpinner'
import { EmptyState } from '@/components/shared/EmptyState'
import { ErrorState } from '@/components/shared/ErrorState'
import type { AgentRun } from '@/types/runs'

function formatDate(iso: string) {
  return new Date(iso).toLocaleString('en-GB', {
    day: '2-digit', month: '2-digit',
    hour: '2-digit', minute: '2-digit',
  })
}

const COLUMNS: ColDef<AgentRun>[] = [
  {
    headerName: 'Run ID',
    field: 'id',
    maxWidth: 110,
    cellRenderer: ({ value }: { value: string }) => (
      <span className="font-mono text-xs text-[#00ff88]">{value?.slice(0, 8)}</span>
    ),
  },
  {
    headerName: 'Keyword / Query',
    field: 'keyword',
    flex: 2,
    cellRenderer: ({ value }: { value: string }) => (
      <span className="text-sm text-white truncate">{value}</span>
    ),
  },
  {
    headerName: 'Location',
    field: 'location',
    flex: 1,
    cellRenderer: ({ value }: { value: string | null }) => (
      <span className="text-sm text-[#888]">{value ?? '—'}</span>
    ),
  },
  {
    headerName: 'Scraped',
    field: 'scrapedCount',
    maxWidth: 100,
    cellRenderer: ({ value }: { value: number | null }) => (
      <span className="text-sm tabular-nums">{value ?? '—'}</span>
    ),
  },
  {
    headerName: 'Tier-1',
    field: 'filteredCount',
    maxWidth: 90,
    cellRenderer: ({ value }: { value: number | null }) => (
      <span className="text-sm tabular-nums text-[#00ff88]">{value ?? '—'}</span>
    ),
  },
  {
    headerName: 'Status',
    field: 'status',
    maxWidth: 140,
    cellRenderer: ({ value }: { value: string }) => <StatusBadge status={value} />,
  },
  {
    headerName: 'Started',
    field: 'createdAt',
    maxWidth: 140,
    cellRenderer: ({ value }: { value: string }) => (
      <span className="text-xs text-[#666] font-mono">{formatDate(value)}</span>
    ),
  },
]

export default function AgentRunsPage() {
  const navigate = useNavigate()
  const { data: runs, isLoading, isError } = useRuns()

  if (isLoading) return <LoadingSpinner />
  if (isError)   return <ErrorState />
  if (!runs?.length) return <EmptyState message="No runs yet. Click 'New Discovery Run' to start one." />

  return (
    <div className="rounded-lg border border-[#1e1e1e] overflow-hidden">
      <DataTable<AgentRun>
        rowData={runs}
        columnDefs={COLUMNS}
        onRowClicked={(row) => navigate(`/runs/${row.id}`)}
        rowClickable
        height="600px"
      />
    </div>
  )
}
