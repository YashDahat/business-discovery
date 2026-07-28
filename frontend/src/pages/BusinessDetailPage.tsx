import { useState, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  Star, ExternalLink,
  FileText, Layers, Palette, Search, ChevronDown, ChevronUp,
  Code2, CheckCircle2, Circle, Square, RefreshCw, RotateCcw,
  Terminal, FolderOpen,
} from 'lucide-react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useBusinessDetail } from '@/hooks/useBusinessDetail'
import { LoadingSpinner } from '@/components/shared/LoadingSpinner'
import { ErrorState } from '@/components/shared/ErrorState'
import { DemoButton } from '@/components/shared/DemoButton'
import {
  RequestChangesPanel,
  REQUEST_CHANGES_PANEL_WIDTH,
  REQUEST_CHANGES_PANEL_COLLAPSED_WIDTH,
} from '@/components/shared/RequestChangesPanel'
import { Section } from '@/components/shared/Section'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { runCoderAgent, generateBrief, getBriefStatus } from '@/services/businessService'
import { stopTask, retryTask, respawnForBrief, getTaskLogs, getTaskFiles } from '@/services/containerService'
import type { ArchitectBrief, BusinessEntity, ContainerTaskSummary, WebsiteType } from '@/types/businessDetail'
import type { GeneratedFile } from '@/types/containers'

// ── Constants ────────────────────────────────────────────────────────────────

const TIER_STYLE: Record<string, string> = {
  TIER_1:      'border-emerald-600 text-emerald-400',
  TIER_2:      'border-amber-600 text-amber-400',
  HAS_WEBSITE: 'border-blue-600 text-blue-400',
  EXCLUDED:    'border-[#2a2a2a] text-[#555]',
}

const WEBSITE_TYPE_STYLE: Record<WebsiteType, { bg: string; text: string; label: string }> = {
  INFORMATIONAL: { bg: 'bg-blue-950',   text: 'text-blue-400',   label: 'Informational' },
  BOOKING:       { bg: 'bg-purple-950', text: 'text-purple-400', label: 'Booking'       },
  ECOMMERCE:     { bg: 'bg-amber-950',  text: 'text-amber-400',  label: 'E-Commerce'    },
  FULL_PLATFORM: { bg: 'bg-red-950',    text: 'text-red-400',    label: 'Full Platform'  },
}

const ACTIVE_STATUSES = new Set(['PENDING', 'RUNNING', 'RETRYING'])

const FILE_TYPE_COLOR: Record<string, string> = {
  BACKEND:  'text-blue-400',
  FRONTEND: 'text-purple-400',
  INFRA:    'text-amber-400',
  CONFIG:   'text-[#888]',
}

const FILE_STATUS_COLOR: Record<string, string> = {
  VALIDATED:         'text-emerald-400',
  SPEC_COMPLIANT:    'text-emerald-400',
  GENERATED:         'text-[#888]',
  PENDING:           'text-[#555]',
  GENERATION_FAILED: 'text-red-400',
  FAILED:            'text-red-400',
}

const TABS = [
  { id: 'overview'    as const, label: 'Overview'             },
  { id: 'analytics'   as const, label: 'Analytics'            },
  { id: 'automation'  as const, label: 'Customer Automation'  },
  { id: 'billing'     as const, label: 'Billing'              },
  { id: 'website'     as const, label: 'Website'              },
]

// ── Color palette ─────────────────────────────────────────────────────────────

const COLOR_KEYWORDS: [string, string][] = [
  ['white', '#F8F8F6'], ['cream', '#FDF6E3'], ['ivory', '#FFFFF0'],
  ['beige', '#F5F0E8'], ['black', '#1A1A1A'], ['charcoal', '#36454F'],
  ['grey', '#9E9E9E'], ['gray', '#9E9E9E'], ['silver', '#C0C0C0'],
  ['red', '#C0392B'], ['crimson', '#DC143C'], ['maroon', '#800000'],
  ['coral', '#FF6B6B'], ['salmon', '#FA8072'], ['orange', '#E67E22'],
  ['amber', '#F59B00'], ['gold', '#D4AC0D'], ['golden', '#D4AC0D'],
  ['saffron', '#FF9933'], ['yellow', '#F1C40F'],
  ['brown', '#7D4F2A'], ['tan', '#D2B48C'], ['earth', '#8B6914'],
  ['terracotta', '#C1440E'], ['rust', '#B7410E'], ['sienna', '#A0522D'],
  ['copper', '#B87333'], ['bronze', '#CD7F32'],
  ['green', '#27AE60'], ['mint', '#98D8BE'], ['emerald', '#2ECC71'],
  ['olive', '#808000'], ['teal', '#148F77'], ['turquoise', '#1ABC9C'],
  ['cyan', '#00BCD4'], ['blue', '#2980B9'], ['navy', '#1B3A6B'],
  ['cobalt', '#0047AB'], ['indigo', '#4B0082'], ['violet', '#7F00FF'],
  ['purple', '#6C3483'], ['lavender', '#967BB6'], ['pink', '#E91E8C'],
  ['rose', '#FF007F'], ['magenta', '#FF00FF'],
  ['warm', '#E8A87C'], ['cool', '#6DB3D4'], ['dark', '#2C2C2C'],
  ['deep', '#2C2C54'], ['light', '#F0F0F0'], ['muted', '#A0A0A0'],
  ['vibrant', '#FF4500'], ['rich', '#6B3E26'], ['pastel', '#FFD1DC'],
  ['bold', '#CC0000'], ['natural', '#8FBC8F'], ['fresh', '#4DB84B'],
  ['classic', '#2F4F4F'], ['elegant', '#C4A882'], ['modern', '#37474F'],
  ['luxurious', '#B8860B'], ['minimalist', '#E8E8E8'], ['traditional', '#8B4513'],
]

function extractPaletteColors(scheme: string): { name: string; hex: string }[] {
  const structuredMatch = /(\w+)=#([0-9A-Fa-f]{6})/g
  const structured: { name: string; hex: string }[] = []
  let m
  while ((m = structuredMatch.exec(scheme)) !== null)
    structured.push({ name: m[1], hex: `#${m[2]}` })
  if (structured.length > 0) return structured

  const lower = scheme.toLowerCase()
  const seen = new Set<string>()
  const result: { name: string; hex: string }[] = []
  for (const [name, hex] of COLOR_KEYWORDS) {
    if (result.length >= 5) break
    if (lower.includes(name) && !seen.has(hex)) { seen.add(hex); result.push({ name, hex }) }
  }
  return result
}

function ColorPaletteHint({ scheme }: { scheme: string }) {
  const colors = extractPaletteColors(scheme)
  const structured = /\w+=#[0-9A-Fa-f]{6}/.test(scheme)
  return (
    <div className="rounded-md border border-[#1e1e1e] bg-[#0d0d0d] p-4">
      <div className="flex items-center gap-2 mb-3">
        <Palette className="h-4 w-4 text-[#555]" />
        <span className="text-sm font-medium text-[#ccc]">Color Palette</span>
      </div>
      {colors.length > 0 ? (
        <div className="flex items-end gap-4 flex-wrap">
          {colors.map(({ name, hex }) => (
            <div key={name} className="flex flex-col items-center gap-1">
              <div title={`${name}: ${hex}`} className="h-10 w-10 rounded-md shadow"
                style={{ backgroundColor: hex, border: '2px solid rgba(255,255,255,0.06)' }} />
              <span className="text-[10px] text-[#888] capitalize">{name}</span>
              <span className="text-[10px] font-mono text-[#555]">{hex}</span>
            </div>
          ))}
        </div>
      ) : (
        <span className="text-sm text-[#888]">{scheme}</span>
      )}
      {!structured && colors.length > 0 && (
        <p className="text-xs text-[#444] italic mt-3">"{scheme}"</p>
      )}
    </div>
  )
}

// ── Helper components ─────────────────────────────────────────────────────────

function InsightBlock({ title, icon: Icon, content }: {
  title: string; icon: React.ElementType; content: string | null
}) {
  const [expanded, setExpanded] = useState(false)
  if (!content) return null
  const preview = content.slice(0, 200)
  const isLong = content.length > 200
  return (
    <div className="rounded-md border border-[#1e1e1e] bg-[#0d0d0d] p-4">
      <button onClick={() => setExpanded(v => !v)} className="flex items-center gap-2 w-full text-left">
        <Icon className="h-4 w-4 text-[#555] shrink-0" />
        <span className="text-sm font-medium text-[#ccc]">{title}</span>
        {isLong && (
          <span className="ml-auto text-[#555]">
            {expanded ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
          </span>
        )}
      </button>
      <p className="mt-2 text-sm text-[#888] leading-relaxed whitespace-pre-line">
        {expanded || !isLong ? content : `${preview}…`}
      </p>
    </div>
  )
}

function ArchitectBriefPanel({ brief }: { brief: ArchitectBrief }) {
  const typeStyle = brief.websiteType ? WEBSITE_TYPE_STYLE[brief.websiteType] : null
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        {typeStyle && (
          <span className={cn('px-3 py-1 rounded-full text-xs font-semibold border', typeStyle.bg, typeStyle.text, 'border-current/20')}>
            {typeStyle.label}
          </span>
        )}
        {brief.tone && (
          <span className="px-3 py-1 rounded-full text-xs font-medium bg-[#1a1a1a] text-[#888] border border-[#2a2a2a]">
            {brief.tone}
          </span>
        )}
        {brief.colorScheme && (
          <span className="px-3 py-1 rounded-full text-xs font-medium bg-[#1a1a1a] text-[#888] border border-[#2a2a2a]">
            {brief.colorScheme}
          </span>
        )}
        <span className="ml-auto text-xs text-[#555] font-mono">
          {new Date(brief.createdAt).toLocaleString('en-GB', { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' })}
        </span>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {brief.recommendedPages?.length ? (
          <div className="rounded-md border border-[#1e1e1e] bg-[#0d0d0d] p-4">
            <div className="flex items-center gap-2 mb-3">
              <Layers className="h-4 w-4 text-[#555]" />
              <span className="text-sm font-medium text-[#ccc]">Recommended Pages</span>
              <span className="ml-auto text-xs text-[#555]">{brief.recommendedPages.length}</span>
            </div>
            <div className="flex flex-wrap gap-1.5">
              {brief.recommendedPages.map(page => (
                <span key={page} className="text-xs px-2 py-0.5 rounded bg-[#1a1a1a] text-[#aaa] border border-[#2a2a2a]">{page}</span>
              ))}
            </div>
          </div>
        ) : null}

        {brief.recommendedTechStack && Object.keys(brief.recommendedTechStack).length > 0 ? (
          <div className="rounded-md border border-[#1e1e1e] bg-[#0d0d0d] p-4">
            <div className="flex items-center gap-2 mb-3">
              <Code2 className="h-4 w-4 text-[#555]" />
              <span className="text-sm font-medium text-[#ccc]">Tech Stack</span>
            </div>
            <div className="flex flex-col gap-1.5">
              {Object.entries(brief.recommendedTechStack).map(([layer, tech]) => (
                <div key={layer} className="flex items-center justify-between">
                  <span className="text-xs text-[#666]">{layer}</span>
                  <span className="text-xs font-medium text-[#00ff88]">{tech}</span>
                </div>
              ))}
            </div>
          </div>
        ) : null}

        {brief.mustHaveFeatures?.length ? (
          <div className="rounded-md border border-[#1e1e1e] bg-[#0d0d0d] p-4">
            <div className="flex items-center gap-2 mb-3">
              <CheckCircle2 className="h-4 w-4 text-emerald-500" />
              <span className="text-sm font-medium text-[#ccc]">Must-Have Features</span>
            </div>
            <ul className="flex flex-col gap-1.5">
              {brief.mustHaveFeatures.map(f => (
                <li key={f} className="flex items-start gap-2 text-sm text-[#aaa]">
                  <span className="mt-1.5 h-1.5 w-1.5 rounded-full bg-emerald-500 shrink-0" />{f}
                </li>
              ))}
            </ul>
          </div>
        ) : null}

        {brief.niceToHaveFeatures?.length ? (
          <div className="rounded-md border border-[#1e1e1e] bg-[#0d0d0d] p-4">
            <div className="flex items-center gap-2 mb-3">
              <Circle className="h-4 w-4 text-[#555]" />
              <span className="text-sm font-medium text-[#ccc]">Nice-to-Have Features</span>
            </div>
            <ul className="flex flex-col gap-1.5">
              {brief.niceToHaveFeatures.map(f => (
                <li key={f} className="flex items-start gap-2 text-sm text-[#666]">
                  <span className="mt-1.5 h-1.5 w-1.5 rounded-full bg-[#444] shrink-0" />{f}
                </li>
              ))}
            </ul>
          </div>
        ) : null}
      </div>

      {brief.seoKeywords?.length ? (
        <div className="rounded-md border border-[#1e1e1e] bg-[#0d0d0d] p-4">
          <div className="flex items-center gap-2 mb-3">
            <Search className="h-4 w-4 text-[#555]" />
            <span className="text-sm font-medium text-[#ccc]">SEO Keywords</span>
          </div>
          <div className="flex flex-wrap gap-1.5">
            {brief.seoKeywords.map(kw => (
              <span key={kw} className="text-xs px-2 py-0.5 rounded-full bg-purple-950 text-purple-400 border border-purple-900">{kw}</span>
            ))}
          </div>
        </div>
      ) : null}

      {brief.designDirection && (
        <div className="rounded-md border border-[#1e1e1e] bg-[#0d0d0d] p-4">
          <div className="flex items-center gap-2 mb-2">
            <Palette className="h-4 w-4 text-[#555]" />
            <span className="text-sm font-medium text-[#ccc]">Design Direction</span>
          </div>
          <p className="text-sm text-[#888] leading-relaxed">{brief.designDirection}</p>
        </div>
      )}

      {brief.colorScheme && <ColorPaletteHint scheme={brief.colorScheme} />}

      <div className="flex flex-col gap-2">
        <InsightBlock title="Industry Insights"   icon={FileText} content={brief.industryInsights}   />
        <InsightBlock title="Competitor Insights" icon={FileText} content={brief.competitorInsights} />
        <InsightBlock title="Architectural Notes" icon={FileText} content={brief.architecturalNotes} />
      </div>

      {(brief.tier1Count != null || brief.tier2Count != null || brief.websiteAdoptionRate != null) && (
        <div className="grid grid-cols-3 gap-3">
          {brief.tier1Count != null && (
            <div className="rounded-md border border-[#1e1e1e] bg-[#0d0d0d] p-3 text-center">
              <div className="text-lg font-bold text-[#00ff88]">{brief.tier1Count}</div>
              <div className="text-xs text-[#555] mt-0.5">Tier-1 Targets</div>
            </div>
          )}
          {brief.tier2Count != null && (
            <div className="rounded-md border border-[#1e1e1e] bg-[#0d0d0d] p-3 text-center">
              <div className="text-lg font-bold text-amber-400">{brief.tier2Count}</div>
              <div className="text-xs text-[#555] mt-0.5">Tier-2 Targets</div>
            </div>
          )}
          {brief.websiteAdoptionRate != null && (
            <div className="rounded-md border border-[#1e1e1e] bg-[#0d0d0d] p-3 text-center">
              <div className="text-lg font-bold text-white">{(brief.websiteAdoptionRate * 100).toFixed(0)}%</div>
              <div className="text-xs text-[#555] mt-0.5">Have Website</div>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function AiPipelineCard({ business, brief, latestTask, opsStatus, onBriefTriggered, logsOpen, onLogsToggle }: {
  business: BusinessEntity
  brief: ArchitectBrief | null
  latestTask: ContainerTaskSummary | null
  opsStatus: string
  onBriefTriggered: () => void
  logsOpen: boolean
  onLogsToggle: () => void
}) {
  const queryClient = useQueryClient()
  const [runError, setRunError] = useState<string | null>(null)
  const [briefError, setBriefError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [isAwaitingBrief, setIsAwaitingBrief] = useState(false)
  const [filesOpen, setFilesOpen] = useState(false)

  const { data: briefStatus } = useQuery({
    queryKey: ['brief-status', business.id],
    queryFn: () => getBriefStatus(business.id),
    enabled: isAwaitingBrief && !brief,
    refetchInterval: isAwaitingBrief && !brief ? 4_000 : false,
  })

  useEffect(() => {
    if (!isAwaitingBrief || !briefStatus) return
    if (briefStatus.status === 'FAILED') {
      setIsAwaitingBrief(false)
      setBriefError(briefStatus.error ?? 'Brief generation failed — try again')
    } else if (briefStatus.status === 'COMPLETED' || brief) {
      setIsAwaitingBrief(false)
      queryClient.invalidateQueries({ queryKey: ['business', business.id] })
    }
  }, [briefStatus, isAwaitingBrief, brief])

  const { mutate: triggerBrief, isPending: isBriefPending } = useMutation({
    mutationFn: () => generateBrief(business.id),
    onSuccess: () => { setBriefError(null); setIsAwaitingBrief(true); onBriefTriggered() },
    onError: (err: unknown) => setBriefError(err instanceof Error ? err.message : 'Failed to start brief generation'),
  })

  const briefLoading = isBriefPending || isAwaitingBrief

  const { mutate: triggerCoder, isPending: isCoderPending } = useMutation({
    mutationFn: () => runCoderAgent(brief!.id, brief!.runId, business.id),
    onSuccess: () => { setRunError(null); queryClient.invalidateQueries({ queryKey: ['business', business.id] }) },
    onError: (err: unknown) => setRunError(err instanceof Error ? err.message : 'Failed to trigger coder agent'),
  })

  const { mutate: doStop, isPending: isStopping } = useMutation({
    mutationFn: () => stopTask(latestTask!.id),
    onSuccess: () => { setActionError(null); queryClient.invalidateQueries({ queryKey: ['business', business.id] }) },
    onError: (err: unknown) => setActionError(err instanceof Error ? err.message : 'Stop failed'),
  })

  const { mutate: doRetry, isPending: isRetrying } = useMutation({
    mutationFn: () => retryTask(latestTask!.id),
    onSuccess: () => { setActionError(null); queryClient.invalidateQueries({ queryKey: ['business', business.id] }) },
    onError: (err: unknown) => setActionError(err instanceof Error ? err.message : 'Retry failed'),
  })

  const { mutate: doRespawn, isPending: isRespawning } = useMutation({
    mutationFn: () => respawnForBrief(brief!.id),
    onSuccess: () => { setActionError(null); queryClient.invalidateQueries({ queryKey: ['business', business.id] }) },
    onError: (err: unknown) => setActionError(err instanceof Error ? err.message : 'Respawn failed'),
  })

  const { data: files, isFetching: filesLoading } = useQuery({
    queryKey: ['task-files', latestTask?.id],
    queryFn: () => getTaskFiles(latestTask!.id),
    enabled: filesOpen && !!latestTask?.id,
    staleTime: 30_000,
  })

  const taskStatus = latestTask?.status ?? null
  const isActive = taskStatus !== null && ACTIVE_STATUSES.has(taskStatus)
  const canRun = !!brief && !isActive && !isCoderPending
  const canRetry = taskStatus === 'FAILED' && (latestTask?.attemptCount ?? 0) < (latestTask?.maxAttempts ?? 3)
  const canRespawn = !!brief && (taskStatus === 'FAILED' || taskStatus === 'COMPLETED')

  const stages = [
    { label: 'Scraped',         done: true },
    { label: 'Scored / Tiered', done: !!business.businessTier },
    { label: 'Architect Brief', done: !!brief },
    { label: 'Code Generated',  done: !!latestTask },
    { label: 'PR Created',      done: !!latestTask?.githubPrUrl },
    { label: 'Live',            done: opsStatus === 'LIVE' },
  ]

  return (
    <Section title="AI Pipeline Status">
      <div className="flex flex-col gap-3">
        {stages.map(({ label, done }) => {
          const isGeneratingThisStep = label === 'Architect Brief' && briefLoading && !done
          return (
            <div key={label} className="flex items-center gap-3">
              {isGeneratingThisStep
                ? <span className="h-2 w-2 rounded-full shrink-0 bg-purple-500 animate-pulse" />
                : <span className={cn('h-2 w-2 rounded-full shrink-0', done ? 'bg-[#00ff88]' : 'bg-[#2a2a2a]')} />}
              <span className={cn('text-sm', done ? 'text-white' : isGeneratingThisStep ? 'text-purple-400' : 'text-[#555]')}>
                {label}
              </span>
              {done && <span className="ml-auto text-xs text-[#00ff88]">✓</span>}
              {isGeneratingThisStep && <span className="ml-auto text-xs text-purple-400 animate-pulse">generating…</span>}
            </div>
          )
        })}
      </div>

      {taskStatus && (
        <div className="mt-4 flex flex-wrap items-center gap-2">
          <span className="text-xs text-[#555]">Latest task:</span>
          <span className={cn('text-xs font-medium px-2 py-0.5 rounded-full',
            taskStatus === 'COMPLETED' ? 'bg-emerald-950 text-emerald-400' :
            taskStatus === 'RUNNING'   ? 'bg-amber-950 text-amber-400 animate-pulse' :
            taskStatus === 'FAILED'    ? 'bg-red-950 text-red-400' :
            taskStatus === 'RETRYING'  ? 'bg-orange-950 text-orange-400 animate-pulse' :
            'bg-[#1e1e1e] text-[#888]'
          )}>
            {taskStatus}
          </span>
          {latestTask?.attemptCount != null && latestTask.attemptCount > 0 && (
            <span className="text-xs text-[#555]">attempt {latestTask.attemptCount}/{latestTask.maxAttempts ?? 3}</span>
          )}
          <div className="ml-auto flex items-center gap-1.5">
            <button onClick={onLogsToggle}
              className="flex items-center gap-1 text-xs text-[#555] hover:text-white transition-colors px-1.5 py-0.5 rounded border border-[#2a2a2a] hover:border-[#444]">
              <Terminal className="h-3 w-3" />{logsOpen ? 'Hide logs' : 'Logs'}
            </button>
            <button onClick={() => setFilesOpen(v => !v)}
              className="flex items-center gap-1 text-xs text-[#555] hover:text-white transition-colors px-1.5 py-0.5 rounded border border-[#2a2a2a] hover:border-[#444]">
              <FolderOpen className="h-3 w-3" />Files
            </button>
          </div>
        </div>
      )}

      {taskStatus === 'FAILED' && latestTask?.errorMessage && (
        <div className="mt-2 rounded border border-red-900 bg-red-950/30 p-2.5">
          {latestTask.failureType && (
            <span className="text-xs font-semibold text-red-400 uppercase tracking-wide">{latestTask.failureType} · </span>
          )}
          <span className="text-xs text-red-300">{latestTask.errorMessage}</span>
        </div>
      )}

      {filesOpen && (
        <div className="mt-2 rounded border border-[#2a2a2a] bg-[#0a0a0a]">
          <div className="flex items-center justify-between px-3 py-1.5 border-b border-[#1e1e1e]">
            <span className="text-xs text-[#555]">{files?.length ?? '…'} files</span>
            <button onClick={() => setFilesOpen(false)} className="text-xs text-[#444] hover:text-white">✕</button>
          </div>
          {filesLoading ? (
            <div className="p-3 text-xs text-[#555]">Loading…</div>
          ) : files && files.length > 0 ? (
            <div className="max-h-52 overflow-y-auto divide-y divide-[#1a1a1a]">
              {files.map((f: GeneratedFile) => (
                <div key={f.id} className="flex items-center gap-2 px-3 py-1.5">
                  <span className={cn('text-xs font-mono w-16 shrink-0', FILE_TYPE_COLOR[f.fileType] ?? 'text-[#888]')}>{f.fileType}</span>
                  <span className="text-xs text-[#aaa] font-mono truncate flex-1">{f.filePath}</span>
                  <span className={cn('text-xs shrink-0', FILE_STATUS_COLOR[f.status] ?? 'text-[#555]')}>{f.status}</span>
                </div>
              ))}
            </div>
          ) : (
            <div className="p-3 text-xs text-[#555]">No files generated yet</div>
          )}
        </div>
      )}

      {latestTask?.generationCostUsd != null && (
        <div className="mt-4 pt-4 border-t border-[#1e1e1e]">
          <div className="flex items-center justify-between">
            <span className="text-xs text-[#555]">Generation cost</span>
            <span className="text-sm font-mono font-medium text-[#00ff88]">${latestTask.generationCostUsd.toFixed(4)}</span>
          </div>
          {latestTask.llmInputTokens != null && latestTask.llmOutputTokens != null && (
            <div className="flex items-center justify-between mt-1">
              <span className="text-xs text-[#444]">
                {(latestTask.llmInputTokens / 1000).toFixed(0)}k in · {(latestTask.llmOutputTokens / 1000).toFixed(0)}k out tokens
              </span>
              <span className="text-xs text-[#555]">≈ ₹{(latestTask.generationCostUsd * 83.5).toFixed(2)}</span>
            </div>
          )}
        </div>
      )}

      {!brief && (
        <div className="mt-4 pt-4 border-t border-[#1e1e1e] flex flex-col gap-2">
          <Button onClick={() => { setBriefError(null); triggerBrief() }} disabled={briefLoading}
            className={cn('w-full h-9 text-sm font-medium transition-all',
              !briefLoading ? 'bg-purple-600 text-white hover:bg-purple-500' : 'bg-[#1a1a1a] text-[#555] cursor-not-allowed')}>
            {briefLoading ? 'Generating Brief…' : 'Generate Architect Brief'}
          </Button>
          {briefLoading && (
            <div className="flex items-center gap-2 justify-center">
              <span className="h-1 w-1 rounded-full bg-purple-500 animate-bounce [animation-delay:-0.3s]" />
              <span className="h-1 w-1 rounded-full bg-purple-500 animate-bounce [animation-delay:-0.15s]" />
              <span className="h-1 w-1 rounded-full bg-purple-500 animate-bounce" />
              <span className="text-xs text-[#555]">Tavily research + Gemini synthesis — 30–60s</span>
            </div>
          )}
          {briefError && <p className="text-xs text-red-400">{briefError}</p>}
        </div>
      )}

      {brief && (
        <div className="mt-4 pt-4 border-t border-[#1e1e1e] flex flex-col gap-2">
          <Button onClick={() => { setRunError(null); triggerCoder() }} disabled={!canRun}
            className={cn('w-full h-9 text-sm font-medium transition-all',
              canRun ? 'bg-[#00ff88] text-black hover:bg-[#00e67a]' : 'bg-[#1a1a1a] text-[#555] cursor-not-allowed')}>
            {isCoderPending ? 'Starting…' : isActive ? `${taskStatus}…` : latestTask ? 'Re-run Coder Agent' : 'Run Coder Agent'}
          </Button>

          {(taskStatus === 'RUNNING' || canRetry || canRespawn) && (
            <div className="flex gap-2">
              {taskStatus === 'RUNNING' && (
                <Button onClick={() => doStop()} disabled={isStopping}
                  className="flex-1 h-8 text-xs bg-red-950 text-red-400 hover:bg-red-900 border border-red-900">
                  <Square className="h-3 w-3 mr-1" />{isStopping ? 'Stopping…' : 'Stop'}
                </Button>
              )}
              {canRetry && (
                <Button onClick={() => doRetry()} disabled={isRetrying}
                  className="flex-1 h-8 text-xs bg-[#1a1a1a] text-amber-400 hover:bg-[#222] border border-[#2a2a2a]">
                  <RefreshCw className="h-3 w-3 mr-1" />{isRetrying ? 'Retrying…' : 'Retry'}
                </Button>
              )}
              {canRespawn && (
                <Button onClick={() => doRespawn()} disabled={isRespawning}
                  className="flex-1 h-8 text-xs bg-[#1a1a1a] text-[#888] hover:bg-[#222] border border-[#2a2a2a]">
                  <RotateCcw className="h-3 w-3 mr-1" />{isRespawning ? 'Queuing…' : 'Respawn'}
                </Button>
              )}
            </div>
          )}

          {runError && <p className="text-xs text-red-400">{runError}</p>}
          {actionError && <p className="text-xs text-red-400">{actionError}</p>}
          {isActive && <p className="text-xs text-[#555] text-center">Agent is running — page will refresh automatically</p>}
        </div>
      )}

      {latestTask?.githubPrUrl && (
        <a href={latestTask.githubPrUrl} target="_blank" rel="noopener noreferrer"
          className="mt-3 flex items-center gap-2 text-sm text-[#00ff88] hover:underline">
          <ExternalLink className="h-4 w-4" />View GitHub PR
        </a>
      )}
      <DemoButton briefId={brief?.id ?? null} publishedImage={latestTask?.publishedImage ?? null} />
      {latestTask?.githubRepoUrl && (
        <a href={latestTask.githubRepoUrl} target="_blank" rel="noopener noreferrer"
          className="mt-1 flex items-center gap-2 text-sm text-[#555] hover:text-white hover:underline">
          <ExternalLink className="h-3.5 w-3.5" />View GitHub Repo
        </a>
      )}
    </Section>
  )
}

// ── Data-grid helpers ─────────────────────────────────────────────────────────

function getInitials(name: string): string {
  return name.split(/[\s&,]+/).filter(Boolean).slice(0, 2).map(w => w[0].toUpperCase()).join('')
}

function deriveSubscriptionPlan(tier: string | null): { name: string; price: string; status: string } {
  if (tier === 'TIER_1')      return { name: 'Standard', price: '7,000',  status: 'active' }
  if (tier === 'TIER_2')      return { name: 'Basic',    price: '3,500',  status: 'active' }
  if (tier === 'HAS_WEBSITE') return { name: 'Premium',  price: '13,000', status: 'active' }
  return { name: '', price: '', status: '' }
}

function summarizeOpenHours(hours: Record<string, string> | null): string | null {
  if (!hours) return null
  const entries = Object.entries(hours)
  if (entries.length === 0) return null
  const uniqueTimes = new Set(entries.map(([, v]) => v))
  if (uniqueTimes.size === 1) {
    const days = entries.map(([d]) => d)
    return `${days[0].slice(0, 3)}–${days[days.length - 1].slice(0, 3)} · ${entries[0][1]}`
  }
  return `${entries[0][0].slice(0, 3)} ${entries[0][1]}${entries.length > 1 ? ` +${entries.length - 1}` : ''}`
}

function summarizePopularTimes(times: Record<string, unknown> | null): string | null {
  if (!times) return null
  let maxVal = 0
  const peaks: string[] = []
  for (const hourData of Object.values(times)) {
    if (typeof hourData !== 'object' || hourData === null) continue
    for (const [h, v] of Object.entries(hourData as Record<string, number>)) {
      const val = Number(v)
      if (val > maxVal) { maxVal = val; peaks.length = 0; peaks.push(`${h}:00`) }
      else if (val === maxVal && peaks.length < 3) peaks.push(`${h}:00`)
    }
  }
  return maxVal > 0 ? `Peak ${peaks.slice(0, 2).join(' & ')}` : null
}

function extractOrderPlatforms(link: string | null): string | null {
  if (!link) return null
  const lower = link.toLowerCase()
  const platforms: string[] = []
  if (lower.includes('zomato'))   platforms.push('Zomato')
  if (lower.includes('swiggy'))   platforms.push('Swiggy')
  if (lower.includes('magicpin')) platforms.push('Magicpin')
  if (lower.includes('dineout'))  platforms.push('Dineout')
  return platforms.length > 0 ? platforms.join(' · ') : 'available'
}

function countDataPoints(b: BusinessEntity): number {
  return Object.values(b as unknown as Record<string, unknown>).filter(
    v => v != null && v !== '' && !(Array.isArray(v) && v.length === 0)
  ).length
}

function DataField({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <span className="text-[10px] font-mono uppercase tracking-widest text-[#555]">{label}</span>
      {value != null && value !== '' ? (
        <span className="text-sm text-white font-medium leading-snug">{value}</span>
      ) : (
        <span className="text-sm text-[#2a2a2a]">—</span>
      )}
    </div>
  )
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function BusinessDetailPage() {
  const { businessId } = useParams<{ businessId: string }>()
  const navigate = useNavigate()
  const [activeTab, setActiveTab] = useState<typeof TABS[number]['id']>('overview')
  const [pollForBrief, setPollForBrief] = useState(false)
  const [logsOpen, setLogsOpen] = useState(false)
  const [chatPanelOpen, setChatPanelOpen] = useState(false)

  const { data, isLoading, isError } = useBusinessDetail(businessId ?? '', pollForBrief)

  const latestTaskId = data?.latestTask?.id
  const latestTaskStatus = data?.latestTask?.status
  const isActiveContainer = latestTaskStatus === 'RUNNING' || latestTaskStatus === 'RETRYING'

  const { data: logsData, isFetching: logsLoading } = useQuery({
    queryKey: ['task-logs', latestTaskId, data?.latestTask?.dockerContainerId],
    queryFn: () => getTaskLogs(latestTaskId!),
    enabled: logsOpen && !!latestTaskId,
    refetchInterval: logsOpen && isActiveContainer ? 5_000 : false,
    staleTime: 0,
  })

  if (isLoading) return <LoadingSpinner />
  if (isError || !data) return <ErrorState />

  const { business: b, brief, latestTask, opsStatus, scopeProgress } = data
  const initials = getInitials(b.title)
  const plan = deriveSubscriptionPlan(b.businessTier)
  const tierCls = TIER_STYLE[b.businessTier ?? ''] ?? TIER_STYLE.EXCLUDED
  const canRespawn = !!brief && (latestTask?.status === 'FAILED' || latestTask?.status === 'COMPLETED')
  const showChatPanel = !!(brief && latestTask)
  const chatGutter = !showChatPanel ? 0
    : chatPanelOpen ? REQUEST_CHANGES_PANEL_WIDTH
    : REQUEST_CHANGES_PANEL_COLLAPSED_WIDTH

  return (
    <div
      className="flex flex-col gap-4 max-w-5xl transition-[margin-right] duration-200"
      style={chatGutter ? { marginRight: chatGutter } : undefined}
    >
      {/* Back */}
      <button
        onClick={() => navigate(-1)}
        className="text-sm font-mono text-[#555] hover:text-white transition-colors w-fit tracking-wide"
      >
        ‹ back to business intelligence
      </button>

      {/* ── Header card ──────────────────────────────────────────────────── */}
      <div className="rounded-2xl border border-[#1e1e1e] bg-[#111] p-6">

        {/* Top row */}
        <div className="flex items-start justify-between gap-6">
          <div className="flex items-start gap-4">
            {/* Initials avatar */}
            <div className="h-14 w-14 rounded-xl bg-[#0f2a1a] border border-[#1a3a22] flex items-center justify-center text-lg font-bold text-[#00ff88] shrink-0 select-none">
              {initials}
            </div>
            <div>
              <h1 className="text-2xl font-bold text-white leading-tight">{b.title}</h1>
              <p className="text-sm text-[#777] mt-0.5">
                {[b.category, b.address].filter(Boolean).join(' · ')}
              </p>
              <div className="flex flex-wrap items-center gap-2 mt-3">
                {b.businessTier && (
                  <span className={cn('text-xs font-mono font-semibold px-2.5 py-1 rounded-full border', tierCls)}>
                    {b.businessTier.replace('_', ' ')}
                  </span>
                )}
                <span className={cn(
                  'text-xs font-mono font-semibold px-2.5 py-1 rounded-full',
                  opsStatus === 'LIVE'       ? 'bg-[#00ff88] text-black' :
                  opsStatus === 'GENERATING' ? 'bg-amber-500 text-black animate-pulse' :
                  opsStatus === 'BRIEF'      ? 'border border-purple-700 text-purple-400' :
                  opsStatus === 'DEMO'       ? 'border border-blue-700 text-blue-400' :
                  'border border-[#2a2a2a] text-[#666]'
                )}>
                  {opsStatus}
                </span>
                {b.rating != null && (
                  <span className="text-sm text-[#ccc] font-mono">
                    ★ {b.rating.toFixed(1)} · {b.reviewCount?.toLocaleString()} reviews
                  </span>
                )}
              </div>
            </div>
          </div>

          {/* Subscription */}
          {plan.name && (
            <div className="text-right shrink-0">
              <div className="text-[10px] font-mono uppercase tracking-widest text-[#555]">SUBSCRIPTION</div>
              <div className="text-2xl font-bold text-[#00ff88] mt-1">{plan.name}</div>
              <div className="text-xs text-[#666] mt-0.5 font-mono">₹{plan.price}/mo · {plan.status}</div>
            </div>
          )}
        </div>

        {/* Pipeline actions */}
        <div className="mt-6 pt-5 border-t border-[#1e1e1e] flex items-center gap-3 flex-wrap">
          <span className="text-[10px] font-mono uppercase tracking-widest text-[#444]">
            // PIPELINE ACTIONS
          </span>

          {showChatPanel && (
            <button
              onClick={() => setChatPanelOpen(v => !v)}
              className="px-5 py-2 rounded-full bg-[#00ff88] text-black text-sm font-semibold hover:bg-[#00e67a] transition-colors"
            >
              Request Changes
            </button>
          )}

          {latestTask?.githubPrUrl && (
            <a href={latestTask.githubPrUrl} target="_blank" rel="noopener noreferrer"
              className="px-5 py-2 rounded-full border border-[#2a2a2a] text-white text-sm font-semibold hover:border-[#555] transition-colors">
              View Live Site
            </a>
          )}

          {canRespawn && (
            <button
              className="px-5 py-2 rounded-full border border-[#2a2a2a] text-white text-sm font-semibold hover:border-[#555] transition-colors"
            >
              Redeploy
            </button>
          )}

          {latestTask?.githubRepoUrl && (
            <a href={latestTask.githubRepoUrl} target="_blank" rel="noopener noreferrer"
              className="px-5 py-2 rounded-full border border-[#2a2a2a] text-[#888] text-sm font-semibold hover:border-[#555] hover:text-white transition-colors">
              View Repo
            </a>
          )}
        </div>

        {/* Tabs */}
        <div className="mt-5 flex items-center gap-1 flex-wrap">
          {TABS.map(tab => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={cn(
                'px-5 py-2 rounded-full text-sm font-semibold transition-colors',
                activeTab === tab.id
                  ? 'bg-[#00ff88] text-black'
                  : 'text-[#666] hover:text-white'
              )}
            >
              {tab.label}
            </button>
          ))}
        </div>
      </div>

      {/* ── Overview tab ─────────────────────────────────────────────────── */}
      {activeTab === 'overview' && (
        <>
          {/* Scraped Business Profile */}
          <div className="rounded-2xl border border-[#1e1e1e] bg-[#111] p-6">
            <p className="text-[10px] font-mono uppercase tracking-widest text-[#555] mb-6">
              // SCRAPED BUSINESS PROFILE · gosom · Google Maps · {countDataPoints(b)} data points
            </p>

            <div className="grid grid-cols-2 sm:grid-cols-4 gap-x-8 gap-y-6">
              <DataField label="BUSINESS NAME"    value={b.title} />
              <DataField label="CATEGORY"         value={b.category} />
              <DataField label="ADDRESS"          value={b.address} />
              <DataField label="COORDINATES"      value={
                b.latitude != null && b.longitude != null
                  ? `${b.latitude.toFixed(2)}, ${b.longitude.toFixed(3)}`
                  : null
              } />

              <DataField label="GOOGLE RATING"    value={b.rating != null ? `★ ${b.rating.toFixed(1)} / 5` : null} />
              <DataField label="REVIEW COUNT"     value={b.reviewCount != null ? b.reviewCount.toLocaleString() : null} />
              <DataField label="PRICE RANGE"      value={b.priceRange} />
              <DataField label="PHONE"            value={b.phone} />

              <DataField label="WEBSITE"          value={b.website} />
              <DataField label="EMAILS"           value={
                b.emails?.length
                  ? b.emails[0].split('@')[0].slice(0, 8) + '…' + (b.emails.length > 1 ? ` · +${b.emails.length - 1}` : '')
                  : null
              } />
              <DataField label="OPEN HOURS"       value={summarizeOpenHours(b.openHours)} />
              <DataField label="POPULAR TIMES"    value={summarizePopularTimes(b.popularTimes)} />

              <DataField label="PHOTOS SCRAPED"   value={b.images?.length ? `${b.images.length} images` : null} />
              <DataField label="RESERVATION LINK" value={b.reservationLink ? 'available' : null} />
              <DataField label="ORDER ONLINE"     value={extractOrderPlatforms(b.orderOnlineLink)} />
              <DataField label="MENU LINK"        value={b.menuLink ? 'menu.pdf' : null} />

              <DataField label="BUSINESS TIER"    value={b.businessTier} />
              <DataField label="WEBSITE SCOPE"    value={scopeProgress} />
              <DataField label="REVENUE ESTIMATE" value={b.revenueEstimate} />
              <DataField label="IS TARGETED"      value={b.isTargeted != null ? (b.isTargeted ? 'Yes' : 'No') : null} />
            </div>

            {/* Description */}
            {b.description && (
              <div className="mt-8 pt-6 border-t border-[#1e1e1e]">
                <p className="text-[10px] font-mono uppercase tracking-widest text-[#555] mb-2">DESCRIPTION</p>
                <p className="text-sm text-[#aaa] leading-relaxed">{b.description}</p>
              </div>
            )}

            {/* User reviews */}
            {b.userReviews && b.userReviews.length > 0 && (
              <div className="mt-8 pt-6 border-t border-[#1e1e1e]">
                <p className="text-[10px] font-mono uppercase tracking-widest text-[#555] mb-4">
                  USER REVIEWS · {b.userReviews.length} total
                </p>
                <div className="flex flex-col gap-4">
                  {b.userReviews.map((review, i) => (
                    <div key={i} className="border-b border-[#1a1a1a] pb-4 last:border-0 last:pb-0">
                      <div className="flex items-center justify-between mb-1">
                        <span className="text-sm font-medium text-white">
                          {String(review.author_name ?? review.name ?? 'Anonymous')}
                        </span>
                        {review.rating != null && (
                          <span className="text-xs text-amber-400 flex items-center gap-1">
                            <Star className="h-3 w-3 fill-amber-400" />{String(review.rating)}
                          </span>
                        )}
                      </div>
                      {review['text'] != null && (
                        <p className="text-sm text-[#777] leading-relaxed">{String(review['text'])}</p>
                      )}
                      {review['relative_time_description'] != null && (
                        <p className="text-xs text-[#444] mt-1">{String(review['relative_time_description'])}</p>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>

          {/* AI Pipeline */}
          <AiPipelineCard
            business={b}
            brief={brief}
            latestTask={latestTask}
            opsStatus={opsStatus}
            onBriefTriggered={() => setPollForBrief(true)}
            logsOpen={logsOpen}
            onLogsToggle={() => setLogsOpen(v => !v)}
          />

          {/* Logs */}
          {logsOpen && latestTask && (
            <div className="rounded-2xl border border-[#1e1e1e] bg-[#0a0a0a] overflow-hidden">
              <div className="flex items-center justify-between px-4 py-2.5 border-b border-[#1e1e1e]">
                <div className="flex items-center gap-2">
                  {isActiveContainer && (
                    <span className={cn('h-2 w-2 rounded-full shrink-0 animate-pulse',
                      latestTask.status === 'RETRYING' ? 'bg-orange-400' : 'bg-amber-400')} />
                  )}
                  <span className="text-xs font-mono text-[#555]">
                    {logsData?.source === 'live' ? 'live · ' : logsData?.source === 'stored' ? 'stored · ' : ''}
                    {latestTask.dockerContainerId ? latestTask.dockerContainerId.slice(0, 12) : `task ${latestTask.id.slice(0, 8)}`}
                  </span>
                  {isActiveContainer && <span className="text-xs text-[#444]">auto-refreshes every 5s</span>}
                </div>
                <button onClick={() => setLogsOpen(false)} className="text-xs text-[#444] hover:text-white transition-colors px-1.5">
                  ✕ close
                </button>
              </div>
              {logsLoading && !logsData ? (
                <div className="p-4 text-xs text-[#555] font-mono">Fetching logs…</div>
              ) : (
                <pre className="p-4 text-xs font-mono text-[#ccc] whitespace-pre-wrap break-all leading-relaxed overflow-y-auto"
                  style={{ maxHeight: '60vh', minHeight: '200px' }}>
                  {logsData?.logs || 'No logs available yet'}
                </pre>
              )}
            </div>
          )}

          {/* Architect Brief */}
          {brief && (
            <div className="rounded-2xl border border-[#1e1e1e] bg-[#111] p-6">
              <p className="text-[10px] font-mono uppercase tracking-widest text-[#555] mb-6">
                // ARCHITECT BRIEF · {new Date(brief.createdAt).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' })}
              </p>
              <ArchitectBriefPanel brief={brief} />
            </div>
          )}

          {/* About */}
          {b.about && Object.keys(b.about).length > 0 && (
            <div className="rounded-2xl border border-[#1e1e1e] bg-[#111] p-6">
              <p className="text-[10px] font-mono uppercase tracking-widest text-[#555] mb-4">// ABOUT</p>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-8 gap-y-2">
                {Object.entries(b.about).map(([key, val]) => (
                  <div key={key} className="flex items-start gap-2 py-1.5 border-b border-[#1a1a1a] last:border-0">
                    <span className="text-[10px] font-mono uppercase tracking-widest text-[#555] shrink-0 w-36 pt-0.5">{key}</span>
                    <span className="text-sm text-white">{String(val)}</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Photos */}
          {b.images && b.images.length > 0 && (
            <div className="rounded-2xl border border-[#1e1e1e] bg-[#111] p-6">
              <p className="text-[10px] font-mono uppercase tracking-widest text-[#555] mb-4">
                // PHOTOS · {b.images.length} scraped
              </p>
              <div className="grid grid-cols-3 sm:grid-cols-5 gap-2">
                {b.images.map((img, i) => (
                  <img key={i} src={img} alt={`${b.title} ${i + 1}`}
                    className="h-24 w-full rounded-md object-cover border border-[#1e1e1e]"
                    onError={e => { (e.target as HTMLImageElement).style.display = 'none' }} />
                ))}
              </div>
            </div>
          )}
        </>
      )}

      {/* ── Placeholder tabs ─────────────────────────────────────────────── */}
      {activeTab !== 'overview' && (
        <div className="rounded-2xl border border-[#1e1e1e] bg-[#111] p-10 text-center">
          <p className="text-[10px] font-mono uppercase tracking-widest text-[#444]">
            // {activeTab.toUpperCase()} · COMING SOON
          </p>
          <p className="text-sm text-[#333] mt-3">This section is under development.</p>
        </div>
      )}

      {/* Request Changes panel */}
      {showChatPanel && (
        <RequestChangesPanel
          briefId={brief!.id}
          businessId={b.id}
          isTaskActive={ACTIVE_STATUSES.has(latestTask?.status ?? '')}
          isOpen={chatPanelOpen}
          onToggle={() => setChatPanelOpen(v => !v)}
        />
      )}
    </div>
  )
}
