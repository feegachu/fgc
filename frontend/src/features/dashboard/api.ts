import { queryOptions } from '@tanstack/react-query'
import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
import { OPEN_STATUS } from '../exception/api'

export type DashboardSummary = components['schemas']['DashboardSummaryResponse']
export type DashboardCommission = components['schemas']['DashboardCommissionResponse']
export type MonthlyCommission = components['schemas']['MonthlyCommission']
export type RecentException = components['schemas']['RecentExceptionResponse']
export type RecentRun = components['schemas']['RecentValidationRunResponse']
export type HomeSummary = components['schemas']['HomeSummaryResponse']
export type HomeBranch = components['schemas']['HomeBranch']
export type BranchDashboard = components['schemas']['BranchDashboardResponse']
export type BranchMetrics = components['schemas']['BranchMetrics']
export type BranchDetailRow = components['schemas']['BranchDetailRow']
export type BranchDetailPage = components['schemas']['PageResponseBranchDetailRow']
export type BranchMetric = 'COMMISSIONS' | 'AGENTS' | 'CONTRACTS' | 'EXCEPTIONS'
export type HomeAttentionAgent = components['schemas']['HomeAttentionAgent']
export type ExceptionCounts = components['schemas']['ExceptionCountsResponse']

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

export const monthlyCommissionsQueryOptions = (month: string) =>
  queryOptions({
    queryKey: ['dashboard', 'monthly-commissions', month],
    queryFn: async ({ signal }) => {
      const params = new URLSearchParams({ month })
      const { data } = await apiClient.request<DashboardCommission>(`/api/v1/dashboard/monthly-commissions?${params}`, { signal })
      if (!data?.months) throw new Error('월별 수수료를 확인할 수 없습니다.')
      return data
    },
    meta: { errorToast: false },
  })

export const branchDashboardQuery = (month: string) => queryOptions({
  queryKey: ['dashboard', 'branches', month],
  queryFn: async ({ signal }) => {
    const { data } = await apiClient.request<BranchDashboard>(`/api/v1/dashboard/branches?${new URLSearchParams({ month })}`, { signal })
    if (!data?.branches) throw new Error('지점별 현황을 불러오지 못했습니다.')
    return data
  },
  meta: { errorToast: false },
})

export const branchDetailsQuery = (month: string, organizationId: number, metric: BranchMetric, page: number) => queryOptions({
  queryKey: ['dashboard', 'branch-details', month, organizationId, metric, page],
  queryFn: async ({ signal }) => {
    const params = new URLSearchParams({ month, organizationId: String(organizationId), metric, page: String(page) })
    const { data } = await apiClient.request<BranchDetailPage>(`/api/v1/dashboard/branch-details?${params}`, { signal })
    if (!data?.content) throw new Error('지점 상세 내역을 불러오지 못했습니다.')
    return data
  },
  meta: { errorToast: false },
})

/** IF-API-68. 현황 기준일은 정산월과 따로 움직인다. */
export const homeSummaryQueryOptions = (asOf: string) =>
  queryOptions({
    queryKey: ['home', 'summary', asOf],
    queryFn: async ({ signal }): Promise<HomeSummary> => {
      const { data } = await apiClient.request<HomeSummary>(
        `/api/v1/home/summary?asOf=${encodeURIComponent(asOf)}`,
        { signal },
      )
      if (!data) throw new Error('설계사 현황을 확인할 수 없습니다.')
      return data
    },
    meta: { errorToast: false },
  })

/** IF-API-69. 미처리 KPI 를 누르면 여는 예외함(status=OPEN)과 같은 조건이다. */
export const openExceptionCountsQueryOptions = () =>
  queryOptions({
    queryKey: ['exceptions', 'counts', OPEN_STATUS],
    queryFn: async ({ signal }): Promise<ExceptionCounts> => {
      const { data } = await apiClient.request<ExceptionCounts>(`/api/v1/exceptions/counts?status=${OPEN_STATUS}`, {
        signal,
      })
      if (!data) throw new Error('미처리 예외 건수를 확인할 수 없습니다.')
      return data
    },
    meta: { errorToast: false },
  })
