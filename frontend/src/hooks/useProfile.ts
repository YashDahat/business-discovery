import { useQuery } from '@tanstack/react-query'
import { getProfile } from '@/services/accessService'

// Own-account details for the profile screen. retry:false so a 401 (not signed in)
// surfaces immediately instead of retrying.
export const useProfile = () =>
  useQuery({ queryKey: ['profile'], queryFn: getProfile, retry: false })
