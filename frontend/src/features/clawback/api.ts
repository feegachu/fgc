import { queryOptions } from '@tanstack/react-query'
import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
import { formatSort, parseSort } from '../../lib/tableSort'
type Schemas = components['schemas']
export type CandidateRow = Schemas['ClawbackCandidateResponse']
export type CandidateDetail = Schemas['ClawbackCandidateDetailResponse']
export type CandidatePage = Schemas['PageResponseClawbackCandidateResponse']
/** 서버(ClawbackCandidateQueryRepository.SORTABLE)와 같은 정렬 key. */
export const CLAWBACK_SORT_KEYS = [
  'candidateId',
  'contractNo',
  'eventDate',
  'contractDate',
  'paidAmount',
  'unpaidAmount',
] as const

export interface Criteria {
  dateBasis: 'EVENT_DATE' | 'CONTRACT_DATE'
  dateFrom: string
  dateTo: string
  contractNo: string
  insurerId: string
  productOfferingId: string
  agentId: string
  triggerType: string
  calculationStatus: string
  /** 비어 있으면 서버 기본(후보 ID 역순) */
  sort: string
  page: number
}
/** 기간 기본값은 작업월의 첫날~마지막날(양 끝 포함)이다. */
export function monthRange(month: string) {
  if (!/^\d{4}-\d{2}$/.test(month)) return { dateFrom: '', dateTo: '' }
  const [year, monthNo] = month.split('-').map(Number)
  const last = new Date(Date.UTC(year, monthNo, 0)).getUTCDate()
  return { dateFrom: `${month}-01`, dateTo: `${month}-${String(last).padStart(2, '0')}` }
}
export function defaultCriteria(month: string): Criteria {
  return {
    dateBasis: 'EVENT_DATE',
    ...monthRange(month),
    contractNo: '',
    insurerId: '',
    productOfferingId: '',
    agentId: '',
    triggerType: '',
    calculationStatus: '',
    sort: '',
    page: 1,
  }
}
export function criteriaFromParams(params: URLSearchParams, month: string): Criteria {
  const base = defaultCriteria(month)
  const raw = Number(params.get('page') ?? 1)
  const text = (key: keyof Criteria) => params.get(key)?.trim() ?? (base[key] as string)
  return {
    dateBasis: params.get('dateBasis') === 'CONTRACT_DATE' ? 'CONTRACT_DATE' : 'EVENT_DATE',
    dateFrom: text('dateFrom'),
    dateTo: text('dateTo'),
    contractNo: text('contractNo'),
    insurerId: text('insurerId'),
    productOfferingId: text('productOfferingId'),
    agentId: text('agentId'),
    triggerType: text('triggerType'),
    calculationStatus: text('calculationStatus'),
    sort: formatSort(parseSort(params.get('sort'), CLAWBACK_SORT_KEYS)),
    page: Number.isSafeInteger(raw) && raw > 0 ? raw : 1,
  }
}
async function search(criteria: Criteria, signal?: AbortSignal) {
  const query = new URLSearchParams()
  Object.entries(criteria).forEach(([key, value]) => {
    if (value !== '') query.set(key, String(value))
  })
  query.set('size', '20')
  return (await apiClient.request<CandidatePage>(`/api/v1/clawbacks/candidates?${query}`, { signal })).data!
}
export const candidateListQuery = (criteria: Criteria) =>
  queryOptions({
    queryKey: ['clawback', 'candidates', criteria],
    queryFn: ({ signal }) => search(criteria, signal),
    meta: { errorToast: false },
  })
export const candidateDetailQuery = (candidateId?: number) =>
  queryOptions({
    queryKey: ['clawback', 'candidate', candidateId],
    enabled: candidateId != null,
    queryFn: async ({ signal }) =>
      (await apiClient.request<CandidateDetail>(`/api/v1/clawbacks/candidates/${candidateId}`, { signal })).data!,
    meta: { errorToast: false },
  })
