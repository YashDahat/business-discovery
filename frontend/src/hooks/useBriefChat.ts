import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { getBriefChat, sendBriefChatMessage } from '@/services/businessService'

/**
 * Change-request thread for one brief, backed by the same Postgres chat
 * memory used by /api/chat. Polls while a task is active so the
 * ContainerMonitorService's "applied" / "failed" note appears without a
 * manual refresh.
 */
export const useBriefChat = (briefId: string, businessId: string, isTaskActive: boolean) => {
  const queryClient = useQueryClient()

  const chatQuery = useQuery({
    queryKey: ['brief-chat', briefId],
    queryFn: () => getBriefChat(briefId),
    enabled: !!briefId,
    refetchInterval: isTaskActive ? 5_000 : false,
  })

  const sendMutation = useMutation({
    mutationFn: (message: string) => sendBriefChatMessage(briefId, message),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['brief-chat', briefId] })
      queryClient.invalidateQueries({ queryKey: ['business', businessId] })
    },
  })

  return { chatQuery, sendMutation }
}
