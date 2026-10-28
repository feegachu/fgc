import { queryOptions } from '@tanstack/react-query'
import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
import type { AgentContract } from './model'

type Schema = components['schemas']
const PAGE = 100
const MAX_PAGES = 20

/** 서버가 로그인한 설계사의 자료만 돌려준다(FUN-085). 화면은 설계사 ID를 보내지 않는다. */
async function allPages<T>(path: string, signal?: AbortSignal) {
  const rows: T[] = []
  for (let page = 1; page <= MAX_PAGES; page += 1) {
    const { data } = await apiClient.request<{ content?: T[]; totalPages?: number }>(
      `${path}${path.includes('?') ? '&' : '?'}page=${page}&size=${PAGE}`,
      { signal },
    )
    if (!data || !Array.isArray(data.content)) throw new Error('응답 데이터를 확인할 수 없습니다.')
    rows.push(...data.content)
    if (page >= (data.totalPages ?? 1)) break
  }
  return rows
}

export const agentContractsQuery = () =>
  queryOptions({
    queryKey: ['agent-home', 'contracts'],
    queryFn: ({ signal }) => allPages<AgentContract>('/api/v1/contracts?sort=contractDate,desc', signal),
    meta: { errorToast: false },
  })

/** 보험사 구분(생명/손해)은 계약 응답에 없어 기준정보에서 가져온다. */
export const agentInsurersQuery = () =>
  queryOptions({
    queryKey: ['agent-home', 'insurers'],
    queryFn: ({ signal }) => allPages<Schema['InsurerResponse']>('/api/v1/base/insurers', signal),
    staleTime: 5 * 60_000,
    meta: { errorToast: false },
  })

/** 보장 유형(상품군)도 계약 응답에 없어 보험사별 상품 기준정보(이력 포함)와 상품명으로 맞춘다. */
export const agentProductsQuery = (insurerIds: number[], asOf: string) =>
  queryOptions({
    queryKey: ['agent-home', 'products', insurerIds, asOf],
    queryFn: async ({ signal }) =>
      (
        await Promise.all(
          insurerIds.map((id) =>
            allPages<Schema['ProductResponse']>(
              `/api/v1/base/products?insurerId=${id}&asOf=${asOf}&includeHistorical=true`,
              signal,
            ),
          ),
        )
      ).flat(),
    enabled: insurerIds.length > 0,
    staleTime: 5 * 60_000,
    meta: { errorToast: false },
  })

/** 해당 월 본인 수수료 지급 건 합계(취소 제외). */
export const agentCommissionQuery = (month: string) =>
  queryOptions({
    queryKey: ['agent-home', 'commission', month],
    queryFn: async ({ signal }) => {
      const rows = await allPages<Schema['CommissionPaymentListResponse']>(
        `/api/v1/transactions?settlementMonth=${encodeURIComponent(month)}`,
        signal,
      )
      return rows.filter((row) => row.status !== 'CANCELLED').reduce((sum, row) => sum + (row.amount ?? 0), 0)
    },
    meta: { errorToast: false },
  })
