import { queryOptions } from '@tanstack/react-query'
import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'

export type DashboardSummary = components['schemas']['DashboardSummaryResponse']
export type RecentException = components['schemas']['RecentExceptionResponse']
export type RecentRun = components['schemas']['RecentValidationRunResponse']

export const dashboardSummaryQueryOptions = (month: string) =>
  queryOptions({
    queryKey: ['dashboard', 'summary', month],
    queryFn: async ({ signal }): Promise<DashboardSummary> => {
      const { data } = await apiClient.request<DashboardSummary>(
        `/api/v1/dashboard/summary?month=${encodeURIComponent(month)}`,
        { signal },
      )
      if (!data) throw new Error('대시보드 요약을 확인할 수 없습니다.')
      return data
    },
  })
