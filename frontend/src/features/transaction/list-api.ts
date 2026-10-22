import { queryOptions } from '@tanstack/react-query'
import { apiClient, ApiError } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
import { errorText } from '../../lib/format'
import { formatSort, parseSort } from '../../lib/tableSort'

export type PaymentRow = components['schemas']['CommissionPaymentListResponse']
export type PaymentPage = components['schemas']['PageResponseCommissionPaymentListResponse']
export const PAGE_SIZE = 20
export const sourceOptions = [
  ['INSURER_STATEMENT', '원수사 명세'],
  ['GA_MANUAL_PAYMENT', 'GA 수기지급'],
  ['GA_CONFIRMED_PAYMENT', 'GA 확정지급'],
  ['ADJUSTMENT', '조정'],
  ['ALLOCATION_POOL', '공통비 풀'],
  ['CLAWBACK', '환수'],
  ['RECOVERY', '회수'],
] as const
export const statusOptions = [
  ['DRAFT', '작성중'],
  ['CONFIRMED', '확정'],
  ['CANCELLED', '취소'],
] as const
export const filterKeys = [
  'settlementMonth',
  'paymentStage',
  'insurerId',
  'contractNo',
  'sourceType',
  'agentId',
  'commissionItemId',
  'status',
] as const
export type TextFilter = (typeof filterKeys)[number]
/** 서버(CommissionPaymentQueryRepository.SORT_KEYS)와 같은 정렬 key. 지급단계·원천유형·흐름·상태는 순서 기준이 없어 제외한다. */
export const TRANSACTION_SORT_KEYS = ['key', 'month', 'agent', 'item', 'amount', 'attributions'] as const
export type PaymentFilters = Record<TextFilter, string> & {
  sort: string
  noAttributionOnly: boolean
  attributionImbalanceOnly: boolean
  page: number
}
export function readFilters(params: URLSearchParams): PaymentFilters {
  const page = Number(params.get('page'))
  return {
    ...(Object.fromEntries(filterKeys.map((key) => [key, params.get(key) ?? ''])) as Record<TextFilter, string>),
    noAttributionOnly: params.get('noAttributionOnly') === 'true',
    attributionImbalanceOnly: params.get('attributionImbalanceOnly') === 'true',
    sort: formatSort(parseSort(params.get('sort'), TRANSACTION_SORT_KEYS)),
    page: Number.isInteger(page) && page > 0 ? page : 1,
  }
}
export function filterParams(filters: PaymentFilters) {
  const query = new URLSearchParams()
  for (const key of filterKeys) if (filters[key]) query.set(key, filters[key])
  if (filters.noAttributionOnly) query.set('noAttributionOnly', 'true')
  if (filters.attributionImbalanceOnly) query.set('attributionImbalanceOnly', 'true')
  if (filters.sort) query.set('sort', filters.sort)
  query.set('page', String(filters.page))
  query.set('size', String(PAGE_SIZE))
  return query
}
export function transactionListQuery(filters: PaymentFilters) {
  return queryOptions({
    queryKey: ['transactions', 'list', filters],
    queryFn: async ({ signal }) => {
      const response = await apiClient.request<PaymentPage>(`/api/v1/transactions?${filterParams(filters)}`, { signal })
      if (!response.data || !Array.isArray(response.data.content)) throw new ApiError(null, response.requestId, 500)
      return response.data
    },
    meta: { errorToast: false },
  })
}
export function listError(error: Error | null) {
  if (error instanceof ApiError && (error.status >= 500 || error.code === 'FGC-COMMON-500'))
    return `요청 ID: ${error.requestId ?? '-'}`
  return errorText(error, '수수료 지급 건을 불러오지 못했습니다. 잠시 후 다시 시도하세요.')
}
