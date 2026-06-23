import { useState, useEffect, useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import type { ColDef } from 'ag-grid-community'
import { Search } from 'lucide-react'
import { useBusinesses } from '@/hooks/useBusinesses'
import { DataTable } from '@/components/shared/DataTable'
import { LoadingSpinner } from '@/components/shared/LoadingSpinner'
import { EmptyState } from '@/components/shared/EmptyState'
import { ErrorState } from '@/components/shared/ErrorState'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import type { BusinessSummary, OpsStatus } from '@/types/businesses'

const OPS_STATUS_STYLE: Record<OpsStatus, string> = {
  LIVE:       'bg-emerald-950 text-emerald-400',
  DEMO:       'bg-blue-950 text-blue-400',
  GENERATING: 'bg-amber-950 text-amber-400',
  BRIEF:      'bg-purple-950 text-purple-400',
  SCRAPE:     'bg-[#1e1e1e] text-gray-400',
}

const TIER_STYLE: Record<string, string> = {
  TIER_1:     'bg-emerald-950 text-emerald-400',
  TIER_2:     'bg-amber-950 text-amber-400',
  HAS_WEBSITE:'bg-blue-950 text-blue-400',
  EXCLUDED:   'bg-[#1e1e1e] text-gray-500',
}

const COLUMNS: ColDef<BusinessSummary>[] = [
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
    headerName: 'Scope',
    field: 'scopeProgress',
    maxWidth: 80,
    cellRenderer: ({ value }: { value: string }) => (
      <span className="text-sm tabular-nums text-[#666]">{value}</span>
    ),
  },
  {
    headerName: 'Status',
    field: 'opsStatus',
    maxWidth: 120,
    cellRenderer: ({ value }: { value: OpsStatus }) => {
      const cls = OPS_STATUS_STYLE[value] ?? 'bg-[#1e1e1e] text-gray-400'
      return (
        <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${cls} ${value === 'GENERATING' ? 'animate-pulse' : ''}`}>
          {value}
        </span>
      )
    },
  },
]

export default function BusinessIntelPage() {
  const navigate = useNavigate()
  const [searchInput, setSearchInput] = useState('')
  const [search, setSearch] = useState('')
  const [category, setCategory] = useState('ALL')
  const [tier, setTier] = useState('ALL')

  useEffect(() => {
    const t = setTimeout(() => setSearch(searchInput), 300)
    return () => clearTimeout(t)
  }, [searchInput])

  const filters = useMemo(() => ({
    search:   search   || undefined,
    category: (category !== 'ALL' ? category : undefined),
    tier:     (tier     !== 'ALL' ? tier     : undefined),
  }), [search, category, tier])

  const { data: businesses, isLoading, isError } = useBusinesses(filters)

  const categories = useMemo(() => {
    if (!businesses) return []
    return [...new Set(businesses.map(b => b.category).filter(Boolean))] as string[]
  }, [businesses])

  if (isLoading) return <LoadingSpinner />
  if (isError)   return <ErrorState />

  return (
    <div className="flex flex-col gap-4">
      {/* Filters */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="relative flex-1 min-w-[200px] max-w-xs">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-[#555]" />
          <Input
            placeholder="Search businesses…"
            value={searchInput}
            onChange={e => setSearchInput(e.target.value)}
            className="pl-9 bg-[#111] border-[#1e1e1e] text-white placeholder:text-[#555] h-9"
          />
        </div>

        <Select value={category} onValueChange={setCategory}>
          <SelectTrigger className="w-44 h-9 bg-[#111] border-[#1e1e1e] text-white text-sm">
            <SelectValue placeholder="All Categories" />
          </SelectTrigger>
          <SelectContent className="bg-[#111] border-[#1e1e1e] text-white">
            <SelectItem value="ALL">All Categories</SelectItem>
            {categories.map(c => (
              <SelectItem key={c} value={c}>{c}</SelectItem>
            ))}
          </SelectContent>
        </Select>

        <Select value={tier} onValueChange={setTier}>
          <SelectTrigger className="w-36 h-9 bg-[#111] border-[#1e1e1e] text-white text-sm">
            <SelectValue placeholder="All Tiers" />
          </SelectTrigger>
          <SelectContent className="bg-[#111] border-[#1e1e1e] text-white">
            <SelectItem value="ALL">All Tiers</SelectItem>
            <SelectItem value="TIER_1">Tier 1</SelectItem>
            <SelectItem value="TIER_2">Tier 2</SelectItem>
            <SelectItem value="HAS_WEBSITE">Has Website</SelectItem>
            <SelectItem value="EXCLUDED">Excluded</SelectItem>
          </SelectContent>
        </Select>

        <span className="text-xs text-[#555] ml-auto">
          {businesses?.length ?? 0} businesses
        </span>
      </div>

      {/* Table */}
      {!businesses?.length ? (
        <EmptyState message="No businesses found. Run a scrape first." />
      ) : (
        <div className="rounded-lg border border-[#1e1e1e] overflow-hidden">
          <DataTable<BusinessSummary>
            rowData={businesses}
            columnDefs={COLUMNS}
            onRowClicked={(row) => navigate(`/businesses/${row.id}`)}
            rowClickable
            height="600px"
          />
        </div>
      )}
    </div>
  )
}
