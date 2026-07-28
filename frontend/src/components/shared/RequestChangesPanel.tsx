import { useEffect, useRef, useState } from 'react'
import { MessageSquare, PanelRightClose, PanelRightOpen, Send } from 'lucide-react'
import { cn } from '@/lib/utils'
import { useBriefChat } from '@/hooks/useBriefChat'
import type { ChatMessageView } from '@/services/businessService'

function ChatBubble({ message }: { message: ChatMessageView }) {
  if (message.role === 'system') {
    return (
      <div className="flex justify-center">
        <span className="text-xs text-[#888] bg-[#161616] border border-[#2a2a2a] rounded-full px-3 py-1 text-center">
          {message.content}
        </span>
      </div>
    )
  }

  const isUser = message.role === 'user'
  return (
    <div className={cn('flex', isUser ? 'justify-end' : 'justify-start')}>
      <div
        className={cn(
          'max-w-[88%] rounded-lg px-3 py-2 text-sm leading-relaxed whitespace-pre-wrap',
          isUser
            ? 'bg-[#00ff88] text-black'
            : 'bg-[#1a1a1a] text-[#ccc] border border-[#2a2a2a]'
        )}
      >
        {message.content}
      </div>
    </div>
  )
}

// Docked chat window, not a card — pinned to the right edge of the viewport
// (mirroring the Sidebar on the left) so it persists while the business
// detail content scrolls independently underneath it. Collapses to a thin
// tab so it doesn't have to stay in the way.
export const REQUEST_CHANGES_PANEL_WIDTH = 380
export const REQUEST_CHANGES_PANEL_COLLAPSED_WIDTH = 44

export function RequestChangesPanel({ briefId, businessId, isTaskActive, isOpen, onToggle }: {
  briefId: string
  businessId: string
  isTaskActive: boolean
  isOpen: boolean
  onToggle: () => void
}) {
  const [draft, setDraft] = useState('')
  // Stays mounted (and polling) across collapse/expand so the live dot on the
  // collapsed tab and message history stay accurate without a refetch on reopen.
  const { chatQuery, sendMutation } = useBriefChat(briefId, businessId, isTaskActive)
  const scrollRef = useRef<HTMLDivElement>(null)

  const messages = chatQuery.data ?? []

  useEffect(() => {
    if (!isOpen) return
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight })
  }, [messages.length, isOpen])

  const submit = () => {
    const text = draft.trim()
    if (!text || sendMutation.isPending) return
    setDraft('')
    sendMutation.mutate(text)
  }

  if (!isOpen) {
    return (
      <button
        onClick={onToggle}
        style={{ width: REQUEST_CHANGES_PANEL_COLLAPSED_WIDTH }}
        title="Open Request Changes"
        className="fixed top-14 right-0 bottom-0 z-30 flex flex-col items-center gap-2 pt-4 border-l border-[#1e1e1e] bg-[#0a0a0a] hover:bg-[#111] transition-colors"
      >
        <PanelRightOpen className="h-4 w-4 text-[#555]" />
        {isTaskActive && <span className="h-1.5 w-1.5 rounded-full bg-[#00ff88] animate-pulse" />}
      </button>
    )
  }

  return (
    <aside
      style={{ width: REQUEST_CHANGES_PANEL_WIDTH }}
      className="fixed top-14 right-0 bottom-0 z-30 flex flex-col border-l border-[#1e1e1e] bg-[#0a0a0a]"
    >
      <div className="h-12 px-4 flex items-center gap-2 border-b border-[#1e1e1e] shrink-0">
        <MessageSquare className="h-4 w-4 text-[#555]" />
        <span className="text-xs font-semibold uppercase tracking-widest text-[#555]">Request Changes</span>
        {isTaskActive && (
          <span className="ml-2 flex items-center gap-1.5 text-[10px] text-[#00ff88]">
            <span className="h-1.5 w-1.5 rounded-full bg-[#00ff88] animate-pulse" />
            live
          </span>
        )}
        <button
          onClick={onToggle}
          title="Close panel"
          className="ml-auto text-[#555] hover:text-white transition-colors"
        >
          <PanelRightClose className="h-4 w-4" />
        </button>
      </div>

      <div ref={scrollRef} className="flex-1 overflow-y-auto p-4 flex flex-col gap-2.5">
        {messages.length === 0 ? (
          <p className="text-sm text-[#555] text-center py-6">
            Describe what to change — e.g. "add a WhatsApp button" or "use a blue colour scheme"
          </p>
        ) : (
          messages.map((m, i) => <ChatBubble key={i} message={m} />)
        )}
      </div>

      <div className="p-3 border-t border-[#1e1e1e] shrink-0">
        <div className="flex items-end gap-2">
          <textarea
            value={draft}
            onChange={e => setDraft(e.target.value)}
            onKeyDown={e => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault()
                submit()
              }
            }}
            placeholder="Describe what to change…"
            rows={2}
            className="flex-1 rounded border border-[#2a2a2a] bg-[#111] p-2 text-sm text-white placeholder-[#444] resize-none focus:outline-none focus:border-[#444]"
          />
          <button
            onClick={submit}
            disabled={!draft.trim() || sendMutation.isPending}
            className="flex items-center gap-1.5 h-9 px-3 rounded text-sm font-medium bg-[#00ff88] text-black hover:bg-[#00e67a] disabled:opacity-30 disabled:cursor-not-allowed transition-colors shrink-0"
          >
            <Send className="h-3.5 w-3.5" />
            {sendMutation.isPending ? '…' : 'Send'}
          </button>
        </div>
        {sendMutation.isError && (
          <p className="mt-2 text-xs text-red-400">
            {(sendMutation.error as { response?: { data?: { error?: string } } })?.response?.data?.error
              ?? 'Failed to send message'}
          </p>
        )}
      </div>
    </aside>
  )
}
