import { useState, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Play, Square, Database, AlertCircle, CheckCircle2, Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { cn } from '@/lib/utils'
import {
  getScraperContainerStatus,
  getScraperContainerLogs,
  startScraperContainer,
  stopScraperContainer,
  submitScrapeJob,
  getScrapeJobStatus,
  persistScrapeResults,
  type ScraperContainerState,
  type JobStatus,
} from '@/services/scraperService'

// ─── Container status badge ───────────────────────────────────────────────────

const STATE_STYLE: Record<ScraperContainerState, { dot: string; label: string; text: string }> = {
  RUNNING:   { dot: 'bg-[#00ff88] animate-pulse', label: 'RUNNING',   text: 'text-[#00ff88]' },
  STOPPED:   { dot: 'bg-[#555]',                  label: 'STOPPED',   text: 'text-[#555]'    },
  NOT_FOUND: { dot: 'bg-[#333]',                  label: 'NOT FOUND', text: 'text-[#444]'    },
  ERROR:     { dot: 'bg-red-500',                 label: 'ERROR',     text: 'text-red-400'   },
}

const JOB_STATE_STYLE: Record<string, { text: string; label: string }> = {
  pending:     { text: 'text-[#888]',    label: 'PENDING'     },
  running:     { text: 'text-amber-400', label: 'RUNNING'     },
  in_progress: { text: 'text-amber-400', label: 'IN PROGRESS' },
  ok:          { text: 'text-[#00ff88]', label: 'COMPLETED'   },
  failed:      { text: 'text-red-400',   label: 'FAILED'      },
  error:       { text: 'text-red-400',   label: 'ERROR'       },
}

function isJobDone(s: JobStatus) { return s === 'ok' || s === 'failed' || s === 'error' }
function isJobActive(s: JobStatus) { return s === 'running' || s === 'in_progress' || s === 'pending' }

// ─── Main page ────────────────────────────────────────────────────────────────

export default function ScraperPage() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()

  // Active job state (kept in component — one job at a time)
  const [activeJobId, setActiveJobId]   = useState<string | null>(null)
  const [persistResult, setPersistResult] = useState<{ count: number; message: string } | null>(null)
  const [actionError, setActionError]   = useState<string | null>(null)

  // Form state
  const [keyword,  setKeyword]  = useState('')
  const [depth,    setDepth]    = useState(5)
  const [radius,   setRadius]   = useState(10000)
  const [fastMode, setFastMode] = useState(false)
  const [email,    setEmail]    = useState(false)
  const [lat,      setLat]      = useState('')
  const [lon,      setLon]      = useState('')

  // Container status — polls every 3s while starting/stopping
  const { data: containerStatus, isFetching: containerFetching } = useQuery({
    queryKey: ['scraper-container'],
    queryFn: getScraperContainerStatus,
    refetchInterval: 3_000,
  })

  const containerState: ScraperContainerState = containerStatus?.state ?? 'NOT_FOUND'
  const stateStyle = STATE_STYLE[containerState] ?? STATE_STYLE.NOT_FOUND

  // Logs — poll every 3s while container is running
  const logsEndRef = useRef<HTMLDivElement>(null)
  const { data: logsData } = useQuery({
    queryKey: ['scraper-logs'],
    queryFn: getScraperContainerLogs,
    enabled: containerState === 'RUNNING',
    refetchInterval: containerState === 'RUNNING' ? 3_000 : false,
  })
  const logs = logsData?.logs ?? ''

  // Auto-scroll logs terminal to bottom on new output
  useEffect(() => {
    logsEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [logs])

  // Job status polling — every 5s while active
  const { data: jobStatus } = useQuery({
    queryKey: ['scraper-job', activeJobId],
    queryFn: () => getScrapeJobStatus(activeJobId!),
    enabled: !!activeJobId,
    refetchInterval: (q) => {
      const s = q.state.data?.status
      return s && isJobActive(s) ? 5_000 : false
    },
  })

  // Container controls
  const { mutate: doStart, isPending: isStarting } = useMutation({
    mutationFn: startScraperContainer,
    onSuccess: () => {
      setActionError(null)
      queryClient.invalidateQueries({ queryKey: ['scraper-container'] })
    },
    onError: (e: unknown) => setActionError(e instanceof Error ? e.message : 'Failed to start'),
  })

  const { mutate: doStop, isPending: isStopping } = useMutation({
    mutationFn: stopScraperContainer,
    onSuccess: () => {
      setActionError(null)
      setActiveJobId(null)
      setPersistResult(null)
      queryClient.invalidateQueries({ queryKey: ['scraper-container'] })
    },
    onError: (e: unknown) => setActionError(e instanceof Error ? e.message : 'Failed to stop'),
  })

  // Submit job
  const { mutate: doSubmit, isPending: isSubmitting } = useMutation({
    mutationFn: () => submitScrapeJob({
      keyword, depth, radius, fastMode, email,
      lat: lat || undefined, lon: lon || undefined,
    }),
    onSuccess: (data) => {
      setActionError(null)
      setPersistResult(null)
      setActiveJobId(data.jobId)
    },
    onError: (e: unknown) => setActionError(e instanceof Error ? e.message : 'Failed to submit job'),
  })

  // Persist results
  const { mutate: doPersist, isPending: isPersisting } = useMutation({
    mutationFn: () => persistScrapeResults(activeJobId!),
    onSuccess: (data) => {
      setActionError(null)
      setPersistResult(data)
      queryClient.invalidateQueries({ queryKey: ['businesses'] })
    },
    onError: (e: unknown) => setActionError(e instanceof Error ? e.message : 'Failed to persist'),
  })

  const canSubmit = containerState === 'RUNNING' && keyword.trim().length >= 3 && !isSubmitting && !activeJobId
  const currentJobStatus = jobStatus?.status
  const jobDone = currentJobStatus ? isJobDone(currentJobStatus) : false
  const canPersist = jobDone && currentJobStatus === 'ok' && !persistResult && !isPersisting

  return (
    <div className="flex flex-col gap-5 max-w-3xl">

      {/* Header */}
      <div>
        <h1 className="text-lg font-semibold text-white">Google Maps Scraper</h1>
        <p className="text-sm text-[#555] mt-0.5">gosom/google-maps-scraper · managed Docker container</p>
      </div>

      {/* ── Container control ── */}
      <div className="rounded-lg border border-[#1e1e1e] bg-[#111] p-5">
        <h3 className="text-xs font-semibold uppercase tracking-widest text-[#555] mb-4">Container</h3>

        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <span className={cn('h-2.5 w-2.5 rounded-full shrink-0', stateStyle.dot)} />
            <div>
              <span className={cn('text-sm font-medium', stateStyle.text)}>{stateStyle.label}</span>
              {containerStatus?.containerId && (
                <span className="ml-2 text-xs font-mono text-[#444]">
                  {containerStatus.containerId.slice(0, 12)}
                </span>
              )}
            </div>
            {containerFetching && (
              <Loader2 className="h-3 w-3 text-[#444] animate-spin" />
            )}
          </div>

          <div className="flex items-center gap-2">
            {containerState !== 'RUNNING' && (
              <Button
                onClick={() => doStart()}
                disabled={isStarting}
                className="h-8 text-xs bg-[#00ff88] text-black hover:bg-[#00e67a] font-medium"
              >
                <Play className="h-3 w-3 mr-1.5" />
                {isStarting ? 'Starting…' : 'Start'}
              </Button>
            )}
            {containerState === 'RUNNING' && (
              <Button
                onClick={() => doStop()}
                disabled={isStopping}
                className="h-8 text-xs bg-red-950 text-red-400 hover:bg-red-900 border border-red-900"
              >
                <Square className="h-3 w-3 mr-1.5" />
                {isStopping ? 'Stopping…' : 'Stop'}
              </Button>
            )}
          </div>
        </div>

        {containerState === 'NOT_FOUND' && (
          <p className="mt-3 text-xs text-[#555]">
            Container not running. Click <span className="text-white">Start</span> to pull the image and launch.
            First start may take ~30s to pull the image.
          </p>
        )}

        {actionError && (
          <div className="mt-3 flex items-start gap-2 rounded border border-red-900 bg-red-950/30 p-2.5">
            <AlertCircle className="h-3.5 w-3.5 text-red-400 mt-0.5 shrink-0" />
            <span className="text-xs text-red-300">{actionError}</span>
          </div>
        )}
      </div>

      {/* ── Scrape job form ── */}
      <div className={cn(
        'rounded-lg border bg-[#111] p-5 transition-opacity',
        containerState !== 'RUNNING' ? 'border-[#1a1a1a] opacity-40 pointer-events-none' : 'border-[#1e1e1e]'
      )}>
        <h3 className="text-xs font-semibold uppercase tracking-widest text-[#555] mb-4">New Scrape Job</h3>

        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-1.5">
            <Label className="text-xs text-[#888]">Keyword *</Label>
            <Input
              value={keyword}
              onChange={e => setKeyword(e.target.value)}
              placeholder="e.g. restaurants in Koregaon Park Pune"
              className="bg-[#0a0a0a] border-[#2a2a2a] text-white placeholder:text-[#333] h-9"
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="flex flex-col gap-1.5">
              <Label className="text-xs text-[#888]">Depth</Label>
              <Input
                type="number" min={1} max={20}
                value={depth}
                onChange={e => setDepth(Number(e.target.value))}
                className="bg-[#0a0a0a] border-[#2a2a2a] text-white h-9"
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label className="text-xs text-[#888]">Radius (m)</Label>
              <Input
                type="number" min={500} max={50000} step={500}
                value={radius}
                onChange={e => setRadius(Number(e.target.value))}
                className="bg-[#0a0a0a] border-[#2a2a2a] text-white h-9"
              />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="flex flex-col gap-1.5">
              <Label className="text-xs text-[#888]">Latitude (optional)</Label>
              <Input
                value={lat}
                onChange={e => setLat(e.target.value)}
                placeholder="18.5308"
                className="bg-[#0a0a0a] border-[#2a2a2a] text-white placeholder:text-[#333] h-9"
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label className="text-xs text-[#888]">Longitude (optional)</Label>
              <Input
                value={lon}
                onChange={e => setLon(e.target.value)}
                placeholder="73.8474"
                className="bg-[#0a0a0a] border-[#2a2a2a] text-white placeholder:text-[#333] h-9"
              />
            </div>
          </div>

          <div className="flex items-center gap-6">
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                checked={fastMode}
                onChange={e => setFastMode(e.target.checked)}
                className="h-3.5 w-3.5 accent-[#00ff88]"
              />
              <span className="text-xs text-[#888]">Fast mode</span>
            </label>
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                checked={email}
                onChange={e => setEmail(e.target.checked)}
                className="h-3.5 w-3.5 accent-[#00ff88]"
              />
              <span className="text-xs text-[#888]">Extract emails</span>
            </label>
          </div>

          <Button
            onClick={() => doSubmit()}
            disabled={!canSubmit}
            className={cn(
              'h-9 text-sm font-medium transition-all',
              canSubmit
                ? 'bg-[#00ff88] text-black hover:bg-[#00e67a]'
                : 'bg-[#1a1a1a] text-[#555] cursor-not-allowed'
            )}
          >
            {isSubmitting ? 'Submitting…' : activeJobId ? 'Job in progress…' : 'Start Scraping →'}
          </Button>
        </div>
      </div>

      {/* ── Active job ── */}
      {activeJobId && (
        <div className="rounded-lg border border-[#1e1e1e] bg-[#111] p-5">
          <h3 className="text-xs font-semibold uppercase tracking-widest text-[#555] mb-4">Active Job</h3>

          <div className="flex items-center justify-between mb-4">
            <div>
              <span className="text-xs text-[#555]">Job ID</span>
              <p className="font-mono text-sm text-white mt-0.5">{activeJobId}</p>
            </div>
            {currentJobStatus && (
              <span className={cn(
                'text-xs font-semibold px-2 py-0.5 rounded-full border',
                currentJobStatus === 'ok'
                  ? 'bg-emerald-950 text-emerald-400 border-emerald-800'
                  : isJobActive(currentJobStatus)
                  ? 'bg-amber-950 text-amber-400 border-amber-800 animate-pulse'
                  : 'bg-red-950 text-red-400 border-red-900',
              )}>
                {JOB_STATE_STYLE[currentJobStatus]?.label ?? currentJobStatus.toUpperCase()}
              </span>
            )}
          </div>

          {/* Progress indicator while running */}
          {currentJobStatus && isJobActive(currentJobStatus) && (
            <div className="flex items-center gap-2 mb-4">
              <Loader2 className="h-3.5 w-3.5 text-amber-400 animate-spin" />
              <span className="text-xs text-[#555]">Scraping Google Maps — polling every 5s</span>
            </div>
          )}

          {/* Persist button */}
          {canPersist && (
            <div className="pt-4 border-t border-[#1e1e1e]">
              <p className="text-xs text-[#555] mb-2">
                Scraping complete. Save results to the business database.
              </p>
              <Button
                onClick={() => doPersist()}
                disabled={isPersisting}
                className="h-9 text-sm bg-[#00ff88] text-black hover:bg-[#00e67a] font-medium"
              >
                <Database className="h-4 w-4 mr-1.5" />
                {isPersisting ? 'Persisting…' : 'Persist Results →'}
              </Button>
            </div>
          )}

          {/* Persist success */}
          {persistResult && (
            <div className="pt-4 border-t border-[#1e1e1e]">
              <div className="flex items-center gap-2 mb-3">
                <CheckCircle2 className="h-4 w-4 text-[#00ff88]" />
                <span className="text-sm text-white font-medium">
                  {persistResult.count} businesses saved
                </span>
              </div>
              <div className="flex gap-2">
                <Button
                  onClick={() => navigate('/businesses')}
                  className="h-8 text-xs bg-[#1a1a1a] text-[#00ff88] border border-[#2a2a2a] hover:bg-[#222]"
                >
                  View in Business Intel →
                </Button>
                <Button
                  onClick={() => { setActiveJobId(null); setPersistResult(null) }}
                  className="h-8 text-xs bg-[#1a1a1a] text-[#888] border border-[#2a2a2a] hover:bg-[#222]"
                >
                  New job
                </Button>
              </div>
            </div>
          )}

          {/* Job failed */}
          {jobDone && currentJobStatus !== 'ok' && (
            <div className="pt-4 border-t border-[#1e1e1e] flex items-start gap-2">
              <AlertCircle className="h-3.5 w-3.5 text-red-400 mt-0.5 shrink-0" />
              <div>
                <p className="text-xs text-red-300">Scrape job failed. Check the gosom container logs.</p>
                <button
                  onClick={() => { setActiveJobId(null); setPersistResult(null) }}
                  className="mt-1 text-xs text-[#555] hover:text-white underline"
                >
                  Dismiss
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* ── Container logs terminal ── */}
      {containerState === 'RUNNING' && (
        <div className="rounded-lg border border-[#1e1e1e] bg-[#0a0a0a] overflow-hidden">
          <div className="flex items-center justify-between px-4 py-2.5 border-b border-[#1e1e1e]">
            <div className="flex items-center gap-2">
              <span className="h-2 w-2 rounded-full bg-[#00ff88] animate-pulse" />
              <span className="text-xs font-mono text-[#555]">scraper · live logs</span>
            </div>
            <span className="text-xs text-[#333]">polls every 3s</span>
          </div>
          <div className="h-64 overflow-y-auto p-3 font-mono text-xs text-[#aaa] leading-relaxed whitespace-pre-wrap break-all">
            {logs
              ? logs
              : <span className="text-[#333]">Waiting for output…</span>
            }
            <div ref={logsEndRef} />
          </div>
        </div>
      )}

      {/* Stop container hint when done */}
      {persistResult && containerState === 'RUNNING' && (
        <div className="flex items-center justify-between rounded-lg border border-[#1e1e1e] bg-[#0d0d0d] px-4 py-3">
          <span className="text-xs text-[#555]">Scraping finished. You can stop the container to free resources.</span>
          <Button
            onClick={() => doStop()}
            disabled={isStopping}
            className="h-7 text-xs bg-[#1a1a1a] text-red-400 border border-red-900 hover:bg-red-950"
          >
            <Square className="h-3 w-3 mr-1" />
            {isStopping ? 'Stopping…' : 'Stop container'}
          </Button>
        </div>
      )}

    </div>
  )
}
