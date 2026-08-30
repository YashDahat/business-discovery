import { useRef } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getClineChat,
  getClineSteps,
  sendClineChatMessage,
  startNewClineSession,
  type ChatMessageView,
  type ClineStep,
} from '@/services/businessService'

/**
 * Read-only project Q&A for one brief, backed by Cline (via /api/v4/cline).
 *
 * - Optimistic + no refetch: the user's message is appended immediately (onMutate), the reply on success.
 * - `stop()` aborts the in-flight request (rolls the optimistic message back).
 * - `newSession` clears the chat by starting a fresh session (old one kept server-side).
 */
export const useClineChat = (briefId: string) => {
  const queryClient = useQueryClient()
  const key = ['cline-chat', briefId]
  const abortRef = useRef<AbortController | null>(null)

  const chatQuery = useQuery({
    queryKey: key,
    queryFn: () => getClineChat(briefId),
    enabled: !!briefId,
  })

  const sendMutation = useMutation({
    mutationFn: (message: string) => {
      const controller = new AbortController()
      abortRef.current = controller
      return sendClineChatMessage(briefId, message, controller.signal)
    },
    onMutate: async (message: string) => {
      await queryClient.cancelQueries({ queryKey: key })
      const previous = queryClient.getQueryData<ChatMessageView[]>(key) ?? []
      queryClient.setQueryData<ChatMessageView[]>(key, [
        ...previous,
        { role: 'user', content: message },
      ])
      return { previous }
    },
    onSuccess: (data) => {
      queryClient.setQueryData<ChatMessageView[]>(key, (cur = []) => [
        ...cur,
        { role: 'ai', content: data.reply },
      ])
    },
    onError: (_err, _msg, ctx) => {
      // Roll back the optimistic user message (covers both real failures and manual stop).
      if (ctx?.previous) queryClient.setQueryData(key, ctx.previous)
    },
    onSettled: () => {
      abortRef.current = null
    },
  })

  const stop = () => abortRef.current?.abort()

  // Live stepper: poll the tool-operation feed while a turn is in flight. Spring records each MCP tool
  // call in real time (each is a separate request during the blocked /chat call), so ~1s polling fills
  // the stepper in step-by-step. Stops polling as soon as the turn settles (keeps the last snapshot).
  const stepsQuery = useQuery({
    queryKey: ['cline-steps', briefId],
    queryFn: () => getClineSteps(briefId),
    enabled: !!briefId && sendMutation.isPending,
    refetchInterval: sendMutation.isPending ? 900 : false,
    // A turn is short-lived; don't serve a stale snapshot from a previous turn.
    gcTime: 0,
  })

  const steps: ClineStep[] = sendMutation.isPending ? (stepsQuery.data?.steps ?? []) : []

  const newSession = useMutation({
    mutationFn: () => startNewClineSession(briefId),
    onSuccess: () => queryClient.setQueryData<ChatMessageView[]>(key, []),
  })

  return { chatQuery, sendMutation, stop, newSession, steps }
}
