import { ExternalLink, Loader2, Play, RotateCcw, Square } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useDemo } from '@/hooks/useDemo'
import type { DemoStatus } from '@/types/demos'

const STATUS_TEXT: Record<DemoStatus, string> = {
  PULLING: 'Pulling image from GHCR…',
  STARTING: 'Starting containers…',
  RUNNING: 'Demo running',
  FAILED: 'Demo failed',
  STOPPED: 'Demo stopped',
}

/**
 * Launches a client demo from the smoke-tested GHCR image and flips to a
 * clickable website link once the container reports healthy.
 *
 * Renders nothing until a published image exists for the brief (i.e. an
 * attempt passed the smoke test and the image reached GHCR).
 */
export function DemoButton({ briefId, publishedImage }: {
  briefId: string | null
  publishedImage: string | null
}) {
  const canDemo = !!briefId && !!publishedImage
  const { demoQuery, startMutation, stopMutation } = useDemo(briefId, canDemo)

  if (!canDemo) return null

  const demo = demoQuery.data
  const busy = demo?.status === 'PULLING' || demo?.status === 'STARTING' || startMutation.isPending

  // Live and clickable — the state transition itself is the "it's ready" notification
  if (demo?.status === 'RUNNING' && demo.demoUrl) {
    return (
      <div className="mt-3 flex items-center gap-3">
        <a
          href={demo.demoUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="flex items-center gap-2 rounded bg-[#00ff88] px-3 py-1.5 text-sm font-semibold text-black hover:bg-[#00e07a]"
        >
          <ExternalLink className="h-4 w-4" />
          Open Website
        </a>
        <button
          onClick={() => stopMutation.mutate()}
          disabled={stopMutation.isPending}
          className="flex items-center gap-1 text-xs text-[#555] hover:text-red-400"
        >
          <Square className="h-3 w-3" />
          {stopMutation.isPending ? 'Stopping…' : 'Stop demo'}
        </button>
        {demo.expiresAt && (
          <span className="text-xs text-[#555]">
            auto-stops {new Date(demo.expiresAt).toLocaleString()}
          </span>
        )}
      </div>
    )
  }

  if (busy) {
    return (
      <div className="mt-3 flex items-center gap-2 text-sm text-[#888]">
        <Loader2 className="h-4 w-4 animate-spin" />
        {demo ? STATUS_TEXT[demo.status] : 'Launching demo…'}
      </div>
    )
  }

  if (demo?.status === 'FAILED') {
    return (
      <div className="mt-3 flex items-center gap-3">
        <span className="text-xs text-red-400" title={demo.errorMessage ?? undefined}>
          Demo failed{demo.errorMessage ? `: ${demo.errorMessage.slice(0, 80)}` : ''}
        </span>
        <Button
          onClick={() => startMutation.mutate()}
          className="h-7 text-xs bg-[#1a1a1a] text-white hover:bg-[#222] border border-[#2a2a2a]"
        >
          <RotateCcw className="mr-1 h-3 w-3" />
          Retry
        </Button>
      </div>
    )
  }

  // Idle (no demo yet, or previous one stopped)
  return (
    <div className="mt-3">
      <Button
        onClick={() => startMutation.mutate()}
        className="h-8 text-xs bg-[#1a1a1a] text-[#00ff88] hover:bg-[#222] border border-[#2a2a2a]"
      >
        <Play className="mr-1.5 h-3.5 w-3.5" />
        Launch Demo
      </Button>
      {startMutation.isError && (
        <p className="mt-1 text-xs text-red-400">
          {(startMutation.error as { response?: { data?: { error?: string } } })?.response?.data?.error
            ?? 'Could not start demo'}
        </p>
      )}
    </div>
  )
}
