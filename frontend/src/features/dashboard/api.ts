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

// 차트 API(/dashboard/insights). OpenAPI 스키마 재생성(generate:api) 전까지 응답 모양을 직접 선언한다.
export type InsurerCapBreakdown = { insurerName: string; capViolation: number; capWarning: number }
export type MonthlyTrendPoint = { month: string; capViolation: number; capWarning: number; reconMismatch: number }
export type DashboardInsights = {
  month: string
  insurerBreakdown: InsurerCapBreakdown[]
  monthlyTrend: MonthlyTrendPoint[]
}

export const dashboardInsightsQueryOptions = (month: string) =>
  queryOptions({
    queryKey: ['dashboard', 'insights', month],
    queryFn: async ({ signal }): Promise<DashboardInsights> => {
      const { data } = await apiClient.request<DashboardInsights>(
        `/api/v1/dashboard/insights?month=${encodeURIComponent(month)}`,
        { signal },
      )
      if (!data) throw new Error('대시보드 차트를 확인할 수 없습니다.')
      return data
    },
  })
