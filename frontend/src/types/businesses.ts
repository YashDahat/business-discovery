export type OpsStatus = 'SCRAPE' | 'BRIEF' | 'GENERATING' | 'DEMO' | 'LIVE'

export interface BusinessSummary {
  id: string
  title: string
  category: string | null
  address: string | null
  phone: string | null
  rating: number | null
  reviewCount: number | null
  revenueEstimate: string | null
  businessTier: string | null
  website: string | null
  opsStatus: OpsStatus
  scopeProgress: string
  createdAt: string
}
