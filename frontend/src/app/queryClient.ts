import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query'
import { ApiError } from '../lib/api/errors'
import { notifyApiError } from '../lib/api/errorNotifications'

export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiError && error.status >= 400 && error.status < 500) return false
  if (error && typeof error === 'object' && 'name' in error && error.name === 'AbortError') return false
  return failureCount < 2
}
export const queryClient = new QueryClient({
  queryCache: new QueryCache({
    onError: (error, query) => { if (query.meta?.errorToast !== false) notifyApiError(error) },
  }),
  mutationCache: new MutationCache({
    onError: (error, _variables, _context, mutation) => {
      if (mutation.meta?.errorToast !== false) notifyApiError(error)
    },
  }),
  defaultOptions: {
    queries: { retry: shouldRetry },
    // Mutations can change state; automatic retries could duplicate a write.
    mutations: { retry: false },
  },
})
