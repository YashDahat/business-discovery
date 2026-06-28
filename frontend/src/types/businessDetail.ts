import type { BusinessSummary } from './businesses'

export type WebsiteType = 'INFORMATIONAL' | 'BOOKING' | 'ECOMMERCE' | 'FULL_PLATFORM'

export interface ArchitectBrief {
  id: string
  runId: string
  businessId: string | null
  businessCategory: string | null
  location: string | null
  businessCount: number | null
  averageRating: number | null
  websiteAdoptionRate: number | null
  websiteType: WebsiteType | null
  recommendedPages: string[] | null
  mustHaveFeatures: string[] | null
  niceToHaveFeatures: string[] | null
  recommendedTechStack: Record<string, string> | null
  seoKeywords: string[] | null
  designDirection: string | null
  colorScheme: string | null
  tone: string | null
  competitorInsights: string | null
  industryInsights: string | null
  architecturalNotes: string | null
  tier1Count: number | null
  tier2Count: number | null
  createdAt: string
}

export interface ContainerTaskSummary {
  id: string
  status: string
  attemptCount: number
  maxAttempts: number
  failureType: 'CODE' | 'INFRA' | 'CONFIG_AUTH' | null
  errorMessage: string | null
  dockerContainerId: string | null
  githubPrUrl: string | null
  githubRepoUrl: string | null
  spawnedAt: string | null
  completedAt: string | null
  llmInputTokens: number | null
  llmOutputTokens: number | null
  generationCostUsd: number | null
}

export interface BusinessEntity {
  id: string
  title: string
  category: string | null
  address: string | null
  phone: string | null
  website: string | null
  latitude: number | null
  longitude: number | null
  rating: number | null
  reviewCount: number | null
  priceRange: string | null
  description: string | null
  mapsLink: string | null
  reviewsLink: string | null
  thumbnailUrl: string | null
  thumbnail: string | null
  images: string[] | null
  reservationLink: string | null
  orderOnlineLink: string | null
  menuLink: string | null
  openHours: Record<string, string> | null
  about: Record<string, unknown> | null
  userReviews: Array<Record<string, unknown>> | null
  emails: string[] | null
  businessTier: string | null
  revenueEstimate: string | null
  timezone: string | null
  createdAt: string
}

export interface BusinessDetailResponse {
  business: BusinessEntity
  brief: ArchitectBrief | null
  latestTask: ContainerTaskSummary | null
  opsStatus: BusinessSummary['opsStatus']
  scopeProgress: string
}
