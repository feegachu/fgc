import { queryOptions } from '@tanstack/react-query'
import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
import { formatSort, parseSort } from '../../lib/tableSort'
type Schemas = components['schemas']
export type ArbitrageRow = Schemas['ArbitrageCheckView']
export type ArbitrageResult = Schemas['ArbitrageSearchResponse']
export type RecheckRequest = Schemas['ReArbitrageCheckRequest']
/** 서버(ArbitrageCheckQueriesImpl.SORT_KEYS)와 같은 정렬 key. 지급단계·환급금 출처·가산 여부·판정은 순서 기준이 없어 제외한다. */
export const ARBITRAGE_SORT_KEYS = [
  'contractNo',
  'asOfDate',
  'contractMonthNo',
  'cumulativePaidPremium',
  'paidCommissionAmount',
  'plannedCommissionAmount',
  'includedSurrenderValueAmount',
  'netDifferenceAmount',
] as const

export interface Criteria {
  month: string
  status: string
  stage: string
  insurerId: string
  contractNo: string
  /** 비어 있으면 서버 기본(최신 판정 순) */
  sort: string
  page: number
}
export function criteriaFromParams(params: URLSearchParams, month: string): Criteria {
  const raw = Number(params.get('page') ?? 1)
  return {
    month: params.get('month') ?? month,
    status: params.get('status') ?? '',
    stage: params.get('stage') ?? 'GA_TO_FC',
    insurerId: params.get('insurerId') ?? '',
    contractNo: params.get('contractNo')?.trim() ?? '',
    sort: formatSort(parseSort(params.get('sort'), ARBITRAGE_SORT_KEYS)),
    page: Number.isSafeInteger(raw) && raw > 0 ? raw : 1,
  }
}
async function search(criteria: Partial<Criteria>, size: number, signal?: AbortSignal) {
  const query = new URLSearchParams()
  Object.entries(criteria).forEach(([key, value]) => {
    if (value !== '') query.set(key, String(value))
  })
  query.set('size', String(size))
  return (await apiClient.request<ArbitrageResult>(`/api/v1/arbitrage-checks?${query}`, { signal })).data!
}
export const arbitrageListQuery = (criteria: Criteria) =>
  queryOptions({
    queryKey: ['arbitrage', 'list', criteria],
    queryFn: ({ signal }) => search(criteria, 20, signal),
    meta: { errorToast: false },
  })
export const arbitrageTimelineQuery = (row?: ArbitrageRow) =>
  queryOptions({
    queryKey: ['arbitrage', 'timeline', row?.contractId, row?.contractNo, row?.paymentStage],
    enabled: Boolean(row?.contractNo && row.paymentStage),
    queryFn: async ({ signal }) => {
      const rows: ArbitrageRow[] = []
      let page = 1
      let last: number
      do {
        signal.throwIfAborted()
        const result = await search({ contractNo: row!.contractNo!, stage: row!.paymentStage!, page }, 100, signal)
        rows.push(...(result.items?.content ?? []))
        last = result.items?.totalPages ?? 1
        page++
      } while (page <= last)
      // contractNo is a substring filter; exclude results for similarly numbered contracts.
      return rows
        .filter((item) => item.contractId === row!.contractId && item.paymentStage === row!.paymentStage)
        .sort(
          (a, b) =>
            (a.asOfDate ?? '').localeCompare(b.asOfDate ?? '') || (a.arbitrageCheckId ?? 0) - (b.arbitrageCheckId ?? 0),
        )
    },
    meta: { errorToast: false },
  })
export async function recheckContract(contractId: number, request: RecheckRequest) {
  return (
    await apiClient.request<Schemas['ReArbitrageCheckResponse']>(`/api/v1/contracts/${contractId}/arbitrage-check`, {
      method: 'POST',
      body: request,
    })
  ).data
}
