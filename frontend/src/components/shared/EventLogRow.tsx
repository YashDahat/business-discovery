import type { AgentEvent, EventType } from '@/types/events'
import { cn } from '@/lib/utils'

const EVENT_COLOR: Record<EventType, string> = {
  STEP_STARTED:      'text-gray-400',
  STEP_COMPLETED:    'text-emerald-400',
  STEP_FAILED:       'text-red-400',
  TOOL_CALL:         'text-purple-400',
  LLM_CALL:          'text-blue-400',
  SCRAPE_SUBMITTED:  'text-amber-400',
  SCRAPE_COMPLETED:  'text-emerald-400',
  BUSINESSES_SCORED: 'text-cyan-400',
  BRIEF_GENERATED:   'text-emerald-400',
  ERROR:             'text-red-400',
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

interface EventLogRowProps {
  event: AgentEvent
}

export function EventLogRow({ event }: EventLogRowProps) {
  const color = EVENT_COLOR[event.eventType] ?? 'text-gray-400'
  return (
    <div className="flex items-start gap-3 py-1.5 border-b border-[#1a1a1a] hover:bg-[#141414] transition-colors">
      <span className="shrink-0 font-mono text-[11px] text-[#555] w-20 pt-px">
        {formatTime(event.createdAt)}
      </span>
      <span className={cn('shrink-0 font-mono text-[11px] font-medium w-36 pt-px truncate', color)}>
        {event.eventType}
      </span>
      <span className="font-mono text-[11px] text-[#888] shrink-0 w-40 truncate pt-px">
        {event.stepName ?? '—'}
      </span>
      <span className="font-mono text-[11px] text-white/80 truncate">
        {event.message ?? ''}
      </span>
      {event.durationMs != null && (
        <span className="shrink-0 font-mono text-[10px] text-[#555] ml-auto pt-px">
          {event.durationMs}ms
        </span>
      )}
    </div>
  )
}
