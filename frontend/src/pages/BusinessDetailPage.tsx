import { useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  ArrowLeft, Phone, Globe, MapPin, Clock, Star, ExternalLink,
  Utensils, ShoppingBag, Calendar, Mail, Image,
  FileText, Layers, Palette, Search, ChevronDown, ChevronUp,
  Code2, CheckCircle2, Circle,
} from 'lucide-react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useBusinessDetail } from '@/hooks/useBusinessDetail'
import { LoadingSpinner } from '@/components/shared/LoadingSpinner'
import { ErrorState } from '@/components/shared/ErrorState'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { runCoderAgent, generateBrief } from '@/services/businessService'
import type { OpsStatus } from '@/types/businesses'
import type { ArchitectBrief, WebsiteType } from '@/types/businessDetail'

const TIER_STYLE: Record<string, string> = {
  TIER_1:      'bg-emerald-950 text-emerald-400 border-emerald-800',
  TIER_2:      'bg-amber-950 text-amber-400 border-amber-800',
  HAS_WEBSITE: 'bg-blue-950 text-blue-400 border-blue-800',
  EXCLUDED:    'bg-[#1e1e1e] text-gray-500 border-[#2a2a2a]',
}

const OPS_STATUS_STYLE: Record<OpsStatus, string> = {
  LIVE:       'bg-emerald-950 text-emerald-400',
  DEMO:       'bg-blue-950 text-blue-400',
  GENERATING: 'bg-amber-950 text-amber-400',
  BRIEF:      'bg-purple-950 text-purple-400',
  SCRAPE:     'bg-[#1e1e1e] text-gray-400',
}

const WEBSITE_TYPE_STYLE: Record<WebsiteType, { bg: string; text: string; label: string }> = {
  INFORMATIONAL: { bg: 'bg-blue-950',   text: 'text-blue-400',   label: 'Informational' },
  BOOKING:       { bg: 'bg-purple-950', text: 'text-purple-400', label: 'Booking'       },
  ECOMMERCE:     { bg: 'bg-amber-950',  text: 'text-amber-400',  label: 'E-Commerce'    },
  FULL_PLATFORM: { bg: 'bg-red-950',    text: 'text-red-400',    label: 'Full Platform'  },
}

function InsightBlock({ title, icon: Icon, content }: { title: string; icon: React.ElementType; content: string | null }) {
  const [expanded, setExpanded] = useState(false)
  if (!content) return null
  const preview = content.slice(0, 200)
  const isLong = content.length > 200
  return (
    <div className="rounded-md border border-[#1e1e1e] bg-[#0d0d0d] p-4">
      <button
        onClick={() => setExpanded(v => !v)}
        className="flex items-center gap-2 w-full text-left"
      >
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
      {/* Type + meta row */}
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
        {/* Recommended pages */}
        {brief.recommendedPages?.length ? (
          <div className="rounded-md border border-[#1e1e1e] bg-[#0d0d0d] p-4">
            <div className="flex items-center gap-2 mb-3">
              <Layers className="h-4 w-4 text-[#555]" />
              <span className="text-sm font-medium text-[#ccc]">Recommended Pages</span>
              <span className="ml-auto text-xs text-[#555]">{brief.recommendedPages.length}</span>
            </div>
            <div className="flex flex-wrap gap-1.5">
              {brief.recommendedPages.map(page => (
                <span key={page} className="text-xs px-2 py-0.5 rounded bg-[#1a1a1a] text-[#aaa] border border-[#2a2a2a]">
                  {page}
                </span>
              ))}
            </div>
          </div>
        ) : null}

        {/* Tech stack */}
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

        {/* Must-have features */}
        {brief.mustHaveFeatures?.length ? (
          <div className="rounded-md border border-[#1e1e1e] bg-[#0d0d0d] p-4">
            <div className="flex items-center gap-2 mb-3">
              <CheckCircle2 className="h-4 w-4 text-emerald-500" />
              <span className="text-sm font-medium text-[#ccc]">Must-Have Features</span>
            </div>
            <ul className="flex flex-col gap-1.5">
              {brief.mustHaveFeatures.map(f => (
                <li key={f} className="flex items-start gap-2 text-sm text-[#aaa]">
                  <span className="mt-1.5 h-1.5 w-1.5 rounded-full bg-emerald-500 shrink-0" />
                  {f}
                </li>
              ))}
            </ul>
          </div>
        ) : null}

        {/* Nice-to-have features */}
        {brief.niceToHaveFeatures?.length ? (
          <div className="rounded-md border border-[#1e1e1e] bg-[#0d0d0d] p-4">
            <div className="flex items-center gap-2 mb-3">
              <Circle className="h-4 w-4 text-[#555]" />
              <span className="text-sm font-medium text-[#ccc]">Nice-to-Have Features</span>
            </div>
            <ul className="flex flex-col gap-1.5">
              {brief.niceToHaveFeatures.map(f => (
                <li key={f} className="flex items-start gap-2 text-sm text-[#666]">
                  <span className="mt-1.5 h-1.5 w-1.5 rounded-full bg-[#444] shrink-0" />
                  {f}
                </li>
              ))}
            </ul>
          </div>
        ) : null}
      </div>

      {/* SEO keywords */}
      {brief.seoKeywords?.length ? (
        <div className="rounded-md border border-[#1e1e1e] bg-[#0d0d0d] p-4">
          <div className="flex items-center gap-2 mb-3">
            <Search className="h-4 w-4 text-[#555]" />
            <span className="text-sm font-medium text-[#ccc]">SEO Keywords</span>
          </div>
          <div className="flex flex-wrap gap-1.5">
            {brief.seoKeywords.map(kw => (
              <span key={kw} className="text-xs px-2 py-0.5 rounded-full bg-purple-950 text-purple-400 border border-purple-900">
                {kw}
              </span>
            ))}
          </div>
        </div>
      ) : null}

      {/* Design direction */}
      {brief.designDirection && (
        <div className="rounded-md border border-[#1e1e1e] bg-[#0d0d0d] p-4">
          <div className="flex items-center gap-2 mb-2">
            <Palette className="h-4 w-4 text-[#555]" />
            <span className="text-sm font-medium text-[#ccc]">Design Direction</span>
          </div>
          <p className="text-sm text-[#888] leading-relaxed">{brief.designDirection}</p>
        </div>
      )}

      {/* Research insights — collapsible */}
      <div className="flex flex-col gap-2">
        <InsightBlock title="Industry Insights"    icon={FileText} content={brief.industryInsights}    />
        <InsightBlock title="Competitor Insights"  icon={FileText} content={brief.competitorInsights}  />
        <InsightBlock title="Architectural Notes"  icon={FileText} content={brief.architecturalNotes}  />
      </div>

      {/* Market stats */}
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

const ACTIVE_STATUSES = new Set(['PENDING', 'RUNNING', 'RETRYING'])

function AiPipelineCard({ business, brief, latestTask, opsStatus, onBriefTriggered }: {
  business: import('@/types/businessDetail').BusinessEntity
  brief: ArchitectBrief | null
  latestTask: import('@/types/businessDetail').ContainerTaskSummary | null
  opsStatus: string
  onBriefTriggered: () => void
}) {
  const queryClient = useQueryClient()
  const [runError, setRunError] = useState<string | null>(null)
  const [briefError, setBriefError] = useState<string | null>(null)
  const [isAwaitingBrief, setIsAwaitingBrief] = useState(false)

  // Stop the loading state once the brief actually arrives
  if (isAwaitingBrief && brief) {
    setIsAwaitingBrief(false)
  }

  const { mutate: triggerBrief, isPending: isBriefPending } = useMutation({
    mutationFn: () => generateBrief(business.id),
    onSuccess: () => {
      setBriefError(null)
      setIsAwaitingBrief(true)
      onBriefTriggered()
      queryClient.invalidateQueries({ queryKey: ['business', business.id] })
    },
    onError: (err: unknown) => {
      setBriefError(err instanceof Error ? err.message : 'Failed to generate brief')
    },
  })

  const briefLoading = isBriefPending || isAwaitingBrief

  const { mutate: triggerCoder, isPending } = useMutation({
    mutationFn: () => runCoderAgent(brief!.id, brief!.runId, business.id),
    onSuccess: () => {
      setRunError(null)
      queryClient.invalidateQueries({ queryKey: ['business', business.id] })
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : 'Failed to trigger coder agent'
      setRunError(msg)
    },
  })

  const taskStatus = latestTask?.status ?? null
  const isActive = taskStatus !== null && ACTIVE_STATUSES.has(taskStatus)
  const canRun = !!brief && !isActive && !isPending

  const stages = [
    { label: 'Scraped',        done: true },
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
              {isGeneratingThisStep ? (
                <span className="h-2 w-2 rounded-full shrink-0 bg-purple-500 animate-pulse" />
              ) : (
                <span className={cn('h-2 w-2 rounded-full shrink-0', done ? 'bg-[#00ff88]' : 'bg-[#2a2a2a]')} />
              )}
              <span className={cn('text-sm', done ? 'text-white' : isGeneratingThisStep ? 'text-purple-400' : 'text-[#555]')}>
                {label}
              </span>
              {done && <span className="ml-auto text-xs text-[#00ff88]">✓</span>}
              {isGeneratingThisStep && (
                <span className="ml-auto text-xs text-purple-400 animate-pulse">generating…</span>
              )}
            </div>
          )
        })}
      </div>

      {/* Task status chip */}
      {taskStatus && (
        <div className="mt-4 flex items-center gap-2">
          <span className="text-xs text-[#555]">Latest task:</span>
          <span className={cn(
            'text-xs font-medium px-2 py-0.5 rounded-full',
            taskStatus === 'COMPLETED' ? 'bg-emerald-950 text-emerald-400' :
            taskStatus === 'RUNNING'   ? 'bg-amber-950 text-amber-400 animate-pulse' :
            taskStatus === 'FAILED'    ? 'bg-red-950 text-red-400' :
            taskStatus === 'RETRYING'  ? 'bg-orange-950 text-orange-400 animate-pulse' :
            'bg-[#1e1e1e] text-[#888]'
          )}>
            {taskStatus}
          </span>
          {latestTask?.attemptCount != null && latestTask.attemptCount > 1 && (
            <span className="text-xs text-[#555]">attempt {latestTask.attemptCount}</span>
          )}
        </div>
      )}

      {/* Generation cost */}
      {latestTask?.generationCostUsd != null && (
        <div className="mt-4 pt-4 border-t border-[#1e1e1e]">
          <div className="flex items-center justify-between">
            <span className="text-xs text-[#555]">Generation cost</span>
            <span className="text-sm font-mono font-medium text-[#00ff88]">
              ${latestTask.generationCostUsd.toFixed(4)}
            </span>
          </div>
          {latestTask.llmInputTokens != null && latestTask.llmOutputTokens != null && (
            <div className="flex items-center justify-between mt-1">
              <span className="text-xs text-[#444]">
                {(latestTask.llmInputTokens / 1000).toFixed(0)}k in · {(latestTask.llmOutputTokens / 1000).toFixed(0)}k out tokens
              </span>
              <span className="text-xs text-[#555]">
                ≈ ₹{(latestTask.generationCostUsd * 83.5).toFixed(2)}
              </span>
            </div>
          )}
        </div>
      )}

      {/* Generate Brief button — shown when no brief exists yet */}
      {!brief && (
        <div className="mt-4 pt-4 border-t border-[#1e1e1e] flex flex-col gap-2">
          <Button
            onClick={() => { setBriefError(null); triggerBrief() }}
            disabled={briefLoading}
            className={cn(
              'w-full h-9 text-sm font-medium transition-all',
              !briefLoading
                ? 'bg-purple-600 text-white hover:bg-purple-500'
                : 'bg-[#1a1a1a] text-[#555] cursor-not-allowed'
            )}
          >
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

      {/* Run Coder Agent button */}
      {brief && (
        <div className="mt-4 pt-4 border-t border-[#1e1e1e] flex flex-col gap-2">
          <Button
            onClick={() => { setRunError(null); triggerCoder() }}
            disabled={!canRun}
            className={cn(
              'w-full h-9 text-sm font-medium transition-all',
              canRun
                ? 'bg-[#00ff88] text-black hover:bg-[#00e67a]'
                : 'bg-[#1a1a1a] text-[#555] cursor-not-allowed'
            )}
          >
            {isPending ? 'Starting…' :
             isActive  ? `${taskStatus}…` :
             latestTask ? 'Re-run Coder Agent' : 'Run Coder Agent'}
          </Button>
          {runError && (
            <p className="text-xs text-red-400">{runError}</p>
          )}
          {isActive && (
            <p className="text-xs text-[#555] text-center">
              Agent is running — page will refresh automatically
            </p>
          )}
        </div>
      )}

      {latestTask?.githubPrUrl && (
        <a
          href={latestTask.githubPrUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="mt-3 flex items-center gap-2 text-sm text-[#00ff88] hover:underline"
        >
          <ExternalLink className="h-4 w-4" />
          View GitHub PR
        </a>
      )}
    </Section>
  )
}

function Section({ title, children, className }: { title: string; children: React.ReactNode; className?: string }) {
  return (
    <div className={cn('rounded-lg border border-[#1e1e1e] bg-[#111] p-5', className)}>
      <h3 className="text-xs font-semibold uppercase tracking-widest text-[#555] mb-4">{title}</h3>
      {children}
    </div>
  )
}

function InfoRow({ icon: Icon, label, value, href }: {
  icon: React.ElementType; label: string; value: string | null | undefined; href?: string
}) {
  if (!value) return null
  return (
    <div className="flex items-start gap-3 py-2 border-b border-[#1a1a1a] last:border-0">
      <Icon className="h-4 w-4 text-[#555] mt-0.5 shrink-0" />
      <span className="text-xs text-[#666] w-24 shrink-0 pt-px">{label}</span>
      {href ? (
        <a href={href} target="_blank" rel="noopener noreferrer"
          className="text-sm text-[#00ff88] hover:underline flex items-center gap-1 break-all">
          {value} <ExternalLink className="h-3 w-3 shrink-0" />
        </a>
      ) : (
        <span className="text-sm text-white break-words">{value}</span>
      )}
    </div>
  )
}

export default function BusinessDetailPage() {
  const { businessId } = useParams<{ businessId: string }>()
  const navigate = useNavigate()
  const [pollForBrief, setPollForBrief] = useState(false)
  const { data, isLoading, isError } = useBusinessDetail(businessId ?? '', pollForBrief)

  if (isLoading) return <LoadingSpinner />
  if (isError || !data) return <ErrorState />

  const { business: b, brief, latestTask, opsStatus, scopeProgress } = data
  const tierCls = TIER_STYLE[b.businessTier ?? ''] ?? TIER_STYLE.EXCLUDED
  const opsCls  = OPS_STATUS_STYLE[opsStatus as OpsStatus] ?? OPS_STATUS_STYLE.SCRAPE

  const thumbnail = b.thumbnail ?? b.thumbnailUrl ?? null
  const extraImages = b.images?.filter(img => img !== thumbnail).slice(0, 5) ?? []

  const mapsHref = b.latitude && b.longitude
    ? `https://www.google.com/maps?q=${b.latitude},${b.longitude}`
    : b.mapsLink ?? undefined

  return (
    <div className="flex flex-col gap-5 max-w-5xl">
      {/* Back nav */}
      <button
        onClick={() => navigate(-1)}
        className="flex items-center gap-1.5 text-sm text-[#666] hover:text-white transition-colors w-fit"
      >
        <ArrowLeft className="h-4 w-4" />
        Back to Business Intel
      </button>

      {/* Header */}
      <div className="rounded-lg border border-[#1e1e1e] bg-[#111] p-5">
        <div className="flex items-start gap-4">
          {thumbnail && (
            <img
              src={thumbnail}
              alt={b.title}
              className="h-20 w-20 rounded-lg object-cover shrink-0 border border-[#1e1e1e]"
              onError={e => { (e.target as HTMLImageElement).style.display = 'none' }}
            />
          )}
          <div className="flex-1 min-w-0">
            <div className="flex flex-wrap items-center gap-2 mb-2">
              {b.businessTier && (
                <span className={cn('text-xs font-semibold px-2 py-0.5 rounded-full border', tierCls)}>
                  {b.businessTier.replace('_', ' ')}
                </span>
              )}
              <span className={cn('text-xs font-semibold px-2 py-0.5 rounded-full', opsCls)}>
                {opsStatus}
              </span>
              <span className="text-xs text-[#555] font-mono">{scopeProgress} stages complete</span>
            </div>
            <h1 className="text-xl font-bold text-white leading-tight">{b.title}</h1>
            {b.category && <p className="text-sm text-[#888] mt-0.5">{b.category}</p>}
            {b.description && <p className="text-sm text-[#666] mt-2 leading-relaxed">{b.description}</p>}
          </div>
        </div>

        {/* Key metrics row */}
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 mt-5 pt-5 border-t border-[#1e1e1e]">
          {b.rating != null && (
            <div>
              <div className="flex items-center gap-1.5">
                <Star className="h-4 w-4 text-amber-400 fill-amber-400" />
                <span className="text-xl font-bold text-amber-400">{b.rating.toFixed(1)}</span>
              </div>
              <div className="text-xs text-[#666] mt-0.5">Rating</div>
            </div>
          )}
          {b.reviewCount != null && (
            <div>
              <div className="text-xl font-bold text-[#00ff88]">{b.reviewCount.toLocaleString()}</div>
              <div className="text-xs text-[#666] mt-0.5">Reviews</div>
            </div>
          )}
          {b.revenueEstimate && (
            <div>
              <div className="text-base font-bold text-[#00ff88] leading-tight">{b.revenueEstimate}</div>
              <div className="text-xs text-[#666] mt-0.5">Revenue Est.</div>
            </div>
          )}
          {b.priceRange && (
            <div>
              <div className="text-xl font-bold text-white">{b.priceRange}</div>
              <div className="text-xs text-[#666] mt-0.5">Price Range</div>
            </div>
          )}
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
        {/* Contact & Location */}
        <Section title="Contact & Location">
          <InfoRow icon={Phone}  label="Phone"    value={b.phone}   href={b.phone ? `tel:${b.phone}` : undefined} />
          <InfoRow icon={MapPin} label="Address"  value={b.address} href={mapsHref} />
          <InfoRow icon={Globe}  label="Website"  value={b.website} href={b.website ?? undefined} />
          {b.emails?.length ? (
            <InfoRow icon={Mail} label="Email" value={b.emails.join(', ')} />
          ) : null}
          {b.latitude && b.longitude && (
            <InfoRow
              icon={MapPin}
              label="Coordinates"
              value={`${b.latitude.toFixed(5)}, ${b.longitude.toFixed(5)}`}
              href={`https://www.google.com/maps?q=${b.latitude},${b.longitude}`}
            />
          )}
          {b.timezone && <InfoRow icon={Clock} label="Timezone" value={b.timezone} />}
        </Section>

        {/* Opening Hours */}
        {b.openHours && Object.keys(b.openHours).length > 0 && (
          <Section title="Opening Hours">
            {Object.entries(b.openHours).map(([day, hours]) => (
              <div key={day} className="flex justify-between py-1.5 border-b border-[#1a1a1a] last:border-0">
                <span className="text-sm text-[#888] w-24 shrink-0">{day}</span>
                <span className="text-sm text-white text-right">{hours}</span>
              </div>
            ))}
          </Section>
        )}

        {/* Online Links */}
        {(b.reservationLink || b.orderOnlineLink || b.menuLink || b.reviewsLink) && (
          <Section title="Online Links">
            <InfoRow icon={Calendar}   label="Reserve"      value={b.reservationLink ? 'Book a table' : null}  href={b.reservationLink ?? undefined} />
            <InfoRow icon={ShoppingBag} label="Order Online" value={b.orderOnlineLink ? 'Order now' : null}    href={b.orderOnlineLink ?? undefined} />
            <InfoRow icon={Utensils}   label="Menu"         value={b.menuLink ? 'View menu' : null}            href={b.menuLink ?? undefined} />
            <InfoRow icon={Star}       label="Reviews"      value={b.reviewsLink ? 'View on Google' : null}    href={b.reviewsLink ?? undefined} />
          </Section>
        )}

        {/* AI Pipeline Status */}
        <AiPipelineCard
          business={b}
          brief={brief}
          latestTask={latestTask}
          opsStatus={opsStatus}
          onBriefTriggered={() => setPollForBrief(true)}
        />
      </div>

      {/* Architect Brief */}
      {brief ? (
        <Section title="Architect Brief">
          <ArchitectBriefPanel brief={brief} />
        </Section>
      ) : pollForBrief ? (
        <div className="rounded-lg border border-purple-900/40 bg-[#111] p-5">
          <div className="flex items-center gap-3 mb-5">
            <span className="text-xs font-semibold uppercase tracking-widest text-purple-500">Architect Brief</span>
            <span className="flex items-center gap-1 ml-auto">
              <span className="h-1.5 w-1.5 rounded-full bg-purple-500 animate-bounce [animation-delay:-0.3s]" />
              <span className="h-1.5 w-1.5 rounded-full bg-purple-500 animate-bounce [animation-delay:-0.15s]" />
              <span className="h-1.5 w-1.5 rounded-full bg-purple-500 animate-bounce" />
              <span className="text-xs text-purple-400 ml-1">Generating…</span>
            </span>
          </div>
          <div className="flex flex-col gap-3">
            {[80, 60, 90, 50, 70].map((w, i) => (
              <div key={i} className={`h-3 rounded bg-[#1e1e1e] animate-pulse`} style={{ width: `${w}%` }} />
            ))}
            <div className="grid grid-cols-2 gap-3 mt-2">
              {[100, 100].map((_, i) => (
                <div key={i} className="h-24 rounded bg-[#1e1e1e] animate-pulse" />
              ))}
            </div>
          </div>
          <p className="text-xs text-[#555] mt-4 text-center">
            Running Tavily research + Gemini synthesis — this page will update automatically
          </p>
        </div>
      ) : null}

      {/* About section */}
      {b.about && Object.keys(b.about).length > 0 && (
        <Section title="About">
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-8 gap-y-2">
            {Object.entries(b.about).map(([key, val]) => (
              <div key={key} className="flex items-start gap-2 py-1">
                <span className="text-sm text-[#666] shrink-0 w-36">{key}</span>
                <span className="text-sm text-white">{String(val)}</span>
              </div>
            ))}
          </div>
        </Section>
      )}

      {/* Image gallery */}
      {extraImages.length > 0 && (
        <Section title="Photos">
          <div className="flex items-center gap-1 mb-2 text-xs text-[#555]">
            <Image className="h-3.5 w-3.5" />
            {extraImages.length} photo{extraImages.length !== 1 ? 's' : ''}
          </div>
          <div className="grid grid-cols-3 sm:grid-cols-5 gap-2">
            {extraImages.map((img, i) => (
              <img
                key={i}
                src={img}
                alt={`${b.title} photo ${i + 1}`}
                className="h-24 w-full rounded-md object-cover border border-[#1e1e1e]"
                onError={e => { (e.target as HTMLImageElement).style.display = 'none' }}
              />
            ))}
          </div>
        </Section>
      )}

      {/* User reviews */}
      {b.userReviews && b.userReviews.length > 0 && (
        <Section title={`Recent Reviews (${b.userReviews.length})`}>
          <div className="flex flex-col gap-4">
            {b.userReviews.slice(0, 5).map((review, i) => (
              <div key={i} className="border-b border-[#1a1a1a] pb-4 last:border-0 last:pb-0">
                <div className="flex items-center justify-between mb-1">
                  <span className="text-sm font-medium text-white">
                    {String(review.author_name ?? review.name ?? 'Anonymous')}
                  </span>
                  {review.rating != null && (
                    <span className="text-xs text-amber-400 flex items-center gap-1">
                      <Star className="h-3 w-3 fill-amber-400" />
                      {String(review.rating)}
                    </span>
                  )}
                </div>
                {review['text'] != null && (
                  <p className="text-sm text-[#888] leading-relaxed">{String(review['text'])}</p>
                )}
                {review['relative_time_description'] != null && (
                  <p className="text-xs text-[#555] mt-1">{String(review['relative_time_description'])}</p>
                )}
              </div>
            ))}
          </div>
        </Section>
      )}
    </div>
  )
}
