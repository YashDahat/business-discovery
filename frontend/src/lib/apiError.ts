import { isAxiosError } from 'axios'

/**
 * Pulls a human message out of an error. Spring's error body carries the
 * ResponseStatusException reason in `message` (server.error.include-message=always),
 * so lockout/validation messages surface to the UI.
 */
export function apiErrorMessage(error: unknown, fallback = 'Something went wrong'): string {
  if (isAxiosError(error)) {
    const data = error.response?.data as { message?: string; error?: string } | undefined
    return data?.message || data?.error || error.message || fallback
  }
  if (error instanceof Error) return error.message
  return fallback
}
