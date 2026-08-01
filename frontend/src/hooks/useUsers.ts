import { useQuery } from '@tanstack/react-query'
import { getAccessSummary, listUsers } from '@/services/accessService'

export const useUsers = () =>
  useQuery({ queryKey: ['users'], queryFn: listUsers })

export const useAccessSummary = () =>
  useQuery({ queryKey: ['access-summary'], queryFn: getAccessSummary })
