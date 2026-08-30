import { useEffect, useRef, useState } from 'react'
import {
  Check, Copy, FilePen, FileText, FolderGit2, GitBranch, GitPullRequest, Globe, Loader2,
  PanelRightClose, PanelRightOpen, PlayCircle, Plus, Search, Send, Sparkles, Square, X,
} from 'lucide-react'
import { cn } from '@/lib/utils'
import { useClineChat } from '@/hooks/useClineChat'
import type { ChatMessageView, ClineStep } from '@/services/businessService'
import {
  Dialog, DialogContent, DialogHeader, DialogFooter, DialogTitle, DialogDescription,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'

// Axios marks aborted requests with code ERR_CANCELED — a manual Stop, not an error to surface.
function isCanceled(err: unknown): boolean {
  const e = err as { code?: string; name?: string } | null
  return e?.code === 'ERR_CANCELED' || e?.name === 'CanceledError'
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)
  return (
    <button
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(text)
          setCopied(true)
          setTimeout(() => setCopied(false), 1200)
        } catch { /* clipboard unavailable */ }
      }}
      title={copied ? 'Copied' : 'Copy message'}
      className="shrink-0 self-end mb-1 p-1 rounded text-[#555] hover:text-white hover:bg-[#1a1a1a] opacity-0 group-hover:opacity-100 focus:opacity-100 transition-opacity"
    >
      {copied ? <Check className="h-3.5 w-3.5 text-[#4aa8ff]" /> : <Copy className="h-3.5 w-3.5" />}
    </button>
  )
}

function ChatBubble({ message }: { message: ChatMessageView }) {
  if (message.role === 'system') {
    return (
      <div className="flex justify-center animate-in fade-in duration-300">
        <span className="text-xs text-[#888] bg-[#161616] border border-[#2a2a2a] rounded-full px-3 py-1 text-center">
          {message.content}
        </span>
      </div>
    )
  }

  const isUser = message.role === 'user'
  return (
    <div
      className={cn(
        // New bubbles fade + slide in on mount (stable keys → existing bubbles don't re-animate).
        'group flex items-center gap-1 animate-in fade-in duration-300 ease-out',
        isUser ? 'justify-end slide-in-from-right-4' : 'justify-start slide-in-from-bottom-2'
      )}
    >
      {isUser && <CopyButton text={message.content} />}
      <div
        className={cn(
          'max-w-[82%] rounded-lg px-3 py-2 text-sm leading-relaxed whitespace-pre-wrap shadow-sm',
          isUser
            ? 'bg-[#4aa8ff] text-black rounded-br-sm'
            : 'bg-[#1a1a1a] text-[#ccc] border border-[#2a2a2a] rounded-bl-sm'
        )}
      >
        {message.content}
      </div>
      {!isUser && <CopyButton text={message.content} />}
    </div>
  )
}

// Animated three-dot "thinking" indicator, styled as an assistant bubble.
function TypingIndicator() {
  return (
    <div className="flex justify-start animate-in fade-in slide-in-from-bottom-2 duration-300">
      <div className="flex items-center gap-2 rounded-lg rounded-bl-sm px-3 py-2.5 bg-[#1a1a1a] border border-[#2a2a2a]">
        <span className="flex items-center gap-1">
          {[0, 150, 300].map(delay => (
            <span
              key={delay}
              className="h-1.5 w-1.5 rounded-full bg-[#4aa8ff] animate-typing-dot"
              style={{ animationDelay: `${delay}ms` }}
            />
          ))}
        </span>
        <span className="text-xs text-[#666]">Cline is thinking</span>
      </div>
    </div>
  )
}

// Per-tool leading icon, so a git/code step reads at a glance.
function stepIcon(tool: string) {
  switch (tool) {
    case 'create_repo': return FolderGit2
    case 'write_repo_file': return FilePen
    case 'read_repo_file': return FileText
    case 'list_repo_files': return FolderGit2
    case 'open_pull_request': return GitPullRequest
    case 'repo_status': return GitBranch
    case 'run_demo': return PlayCircle
    case 'web_search': case 'web_extract': case 'web_crawl': case 'web_map': return Globe
    default: return Search
  }
}

// Live stepper of the git/code/web operations Cline is performing this turn (polled while in flight).
function OperationStepper({ steps }: { steps: ClineStep[] }) {
  return (
    <div className="flex justify-start animate-in fade-in slide-in-from-bottom-2 duration-300">
      <div className="w-full max-w-[92%] rounded-lg rounded-bl-sm border border-[#2a2a2a] bg-[#141414] p-3">
        <div className="flex items-center gap-2 mb-2">
          <Sparkles className="h-3.5 w-3.5 text-[#4aa8ff]" />
          <span className="text-[11px] font-semibold uppercase tracking-widest text-[#666]">
            Working on it
          </span>
        </div>
        <ol className="flex flex-col gap-1.5">
          {steps.map(step => {
            const ToolIcon = stepIcon(step.tool)
            return (
              <li key={step.id} className="flex items-center gap-2 text-sm animate-in fade-in duration-200">
                <span className="shrink-0">
                  {step.status === 'running' && <Loader2 className="h-3.5 w-3.5 text-[#4aa8ff] animate-spin" />}
                  {step.status === 'done' && <Check className="h-3.5 w-3.5 text-emerald-400" />}
                  {step.status === 'error' && <X className="h-3.5 w-3.5 text-red-400" />}
                </span>
                <ToolIcon className="h-3.5 w-3.5 shrink-0 text-[#555]" />
                <span
                  className={cn(
                    'truncate',
                    step.status === 'running' ? 'text-[#ddd]' : 'text-[#999]',
                    step.status === 'error' && 'text-red-300'
                  )}
                  title={step.detail ?? step.label}
                >
                  {step.label}
                </span>
              </li>
            )
          })}
        </ol>
      </div>
    </div>
  )
}

// Read-only project Q&A, docked to the right (to the left of the Request Changes
// panel via `rightOffset` so both can be open side by side). Cline answers with
// user + project context; nothing is edited or regenerated from here.
export const PROJECT_CHAT_PANEL_WIDTH = 560
export const PROJECT_CHAT_PANEL_COLLAPSED_WIDTH = 44

export function ProjectChatPanel({ briefId, isOpen, onToggle, rightOffset = 0 }: {
  briefId: string
  isOpen: boolean
  onToggle: () => void
  rightOffset?: number
}) {
  const [draft, setDraft] = useState('')
  const [confirmOpen, setConfirmOpen] = useState(false)
  const { chatQuery, sendMutation, stop, newSession, steps } = useClineChat(briefId)
  const scrollRef = useRef<HTMLDivElement>(null)

  const messagesCount = (chatQuery.data ?? []).length
  const startNewSession = () => {
    if (sendMutation.isPending) return
    if (messagesCount === 0) { newSession.mutate(); return } // nothing to clear
    setConfirmOpen(true)
  }
  const confirmNewSession = () => {
    newSession.mutate()
    setConfirmOpen(false)
  }

  const messages = chatQuery.data ?? []

  useEffect(() => {
    if (!isOpen) return
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' })
  }, [messages.length, isOpen, sendMutation.isPending, steps.length])

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
        style={{ width: PROJECT_CHAT_PANEL_COLLAPSED_WIDTH, right: rightOffset }}
        title="Ask about this project"
        className="group fixed top-14 bottom-0 z-30 flex flex-col items-center gap-2 pt-4 border-l border-[#1e1e1e] bg-[#0a0a0a] hover:bg-[#111] transition-colors animate-in fade-in duration-200"
      >
        <PanelRightOpen className="h-4 w-4 text-[#555] transition-transform group-hover:-translate-x-0.5" />
        <Sparkles className="h-3.5 w-3.5 text-[#4aa8ff] transition-transform group-hover:scale-110" />
      </button>
    )
  }

  return (
    <aside
      style={{ width: PROJECT_CHAT_PANEL_WIDTH, right: rightOffset }}
      className="fixed top-14 bottom-0 z-30 max-w-[90vw] flex flex-col border-l border-[#1e1e1e] bg-[#0a0a0a] shadow-[-8px_0_24px_rgba(0,0,0,0.5)] animate-in slide-in-from-right-8 fade-in duration-300 ease-out"
    >
      <div className="h-12 px-4 flex items-center gap-2 border-b border-[#1e1e1e] shrink-0">
        <Sparkles className={cn('h-4 w-4 text-[#4aa8ff] transition', sendMutation.isPending && 'animate-pulse')} />
        <span className="text-xs font-semibold uppercase tracking-widest text-[#555]">Ask Cline</span>
        <button
          onClick={startNewSession}
          disabled={sendMutation.isPending || newSession.isPending}
          title="New session (clear chat)"
          className="ml-auto flex items-center gap-1 text-xs text-[#666] hover:text-white disabled:opacity-40 transition-colors"
        >
          <Plus className="h-3.5 w-3.5" /> New session
        </button>
        <button
          onClick={onToggle}
          title="Close panel"
          className="text-[#555] hover:text-white transition-colors"
        >
          <PanelRightClose className="h-4 w-4" />
        </button>
      </div>

      {/* Persistent nudge — encourage a fresh session per feature now that decisions persist to the brief. */}
      <div className="px-4 py-2 text-[11px] text-[#666] bg-[#0d0d0d] border-b border-[#1e1e1e] shrink-0">
        💡 Starting a new feature? Begin a <button onClick={startNewSession} className="text-[#4aa8ff] hover:underline">new session</button> — your applied changes are saved to the project brief.
      </div>

      <div ref={scrollRef} className="flex-1 overflow-y-auto p-4 flex flex-col gap-2.5">
        {messages.length === 0 && !sendMutation.isPending ? (
          <p className="text-sm text-[#555] text-center py-6 animate-in fade-in duration-500">
            Ask about this project — e.g. "what tech stack is used?" or "how does checkout work?".
            Read-only: this won't change or regenerate the site.
          </p>
        ) : (
          messages.map((m, i) => <ChatBubble key={i} message={m} />)
        )}
        {sendMutation.isPending && (
          steps.length > 0 ? <OperationStepper steps={steps} /> : <TypingIndicator />
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
            placeholder="Ask about this project…"
            rows={2}
            className="flex-1 rounded border border-[#2a2a2a] bg-[#111] p-2 text-sm text-white placeholder-[#444] resize-none focus:outline-none focus:border-[#444]"
          />
          {sendMutation.isPending ? (
            <button
              onClick={stop}
              title="Stop"
              className="flex items-center gap-1.5 h-9 px-3 rounded text-sm font-medium bg-[#2a2a2a] text-white hover:bg-[#333] transition-all active:scale-95 shrink-0"
            >
              <Square className="h-3 w-3 fill-current" />
              Stop
            </button>
          ) : (
            <button
              onClick={submit}
              disabled={!draft.trim()}
              className="group/send flex items-center gap-1.5 h-9 px-3 rounded text-sm font-medium bg-[#4aa8ff] text-black hover:bg-[#3a98ef] disabled:opacity-30 disabled:cursor-not-allowed transition-all active:scale-95 shrink-0"
            >
              <Send className="h-3.5 w-3.5 transition-transform group-hover/send:translate-x-0.5" />
              Ask
            </button>
          )}
        </div>
        {sendMutation.isError && !isCanceled(sendMutation.error) && (
          <p className="mt-2 text-xs text-red-400">
            {(sendMutation.error as { response?: { data?: { error?: string } } })?.response?.data?.error
              ?? 'Failed to get an answer'}
          </p>
        )}
      </div>

      <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Start a new session?</DialogTitle>
            <DialogDescription>
              This clears the chat view and begins a fresh session. Your current conversation is kept, and
              any decisions you applied are saved to the project brief.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirmOpen(false)}>Cancel</Button>
            <Button onClick={confirmNewSession}>Start new session</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </aside>
  )
}
