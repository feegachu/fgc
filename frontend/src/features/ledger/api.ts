import { keepPreviousData, queryOptions } from '@tanstack/react-query'
import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
import { safeRedirect } from '../../lib/api/redirect'
import { JOURNAL_STATUS, JOURNAL_TYPE } from './labels'
import { formatSort, parseSort } from '../../lib/tableSort'

type Schemas = components['schemas']
export type Journal = Schemas['JournalItemResponse']
export type JournalDetail = Schemas['JournalDetailResponse']
export type JournalLine = Schemas['JournalDetailLineResponse']
export type JournalSearch = Schemas['JournalSearchResponse']
export const PAGE_SIZE = 20
/** 서버(JournalQueryRepository.SORT_KEYS)와 같은 정렬 key. 유형·원천·상태·역분개 표시는 순서 기준이 없어 제외한다. */
export const LEDGER_SORT_KEYS = ['journalNo', 'journalDate', 'contractNo', 'debitTotal', 'creditTotal'] as const
export const FILTER_KEYS = {
  from: '',
  to: '',
  type: '',
  account: '',
  contractNo: '',
  status: '',
  sort: '',
  searched: '',
  selected: '',
  page: '',
}
export function positiveId(value: string | null) {
  return value && /^[1-9]\d*$/.test(value) && Number.isSafeInteger(Number(value)) ? value : ''
}
function validDate(value: string | null) {
  return value &&
    /^\d{4}-\d{2}-\d{2}$/.test(value) &&
    !Number.isNaN(Date.parse(value)) &&
    new Date(value).toISOString().slice(0, 10) === value
    ? value
    : ''
}
export function criteriaFromParams(params: URLSearchParams) {
  const type = params.get('type') ?? ''
  const status = params.get('status') ?? ''
  return {
    from: validDate(params.get('from')),
    to: validDate(params.get('to')),
    type: Object.hasOwn(JOURNAL_TYPE, type) ? type : '',
    account: params.get('account')?.trim() ?? '',
    contractNo: params.get('contractNo')?.trim() ?? '',
    status: Object.hasOwn(JOURNAL_STATUS, status) ? status : '',
    sort: formatSort(parseSort(params.get('sort'), LEDGER_SORT_KEYS)),
    page: Number(positiveId(params.get('page')) || 1),
  }
}
export type JournalCriteria = ReturnType<typeof criteriaFromParams>
/** 기준 정산월(yyyy-MM)의 첫날·말일. 기준월이 없으면 빈 범위다. */
export function monthRange(month: string | null) {
  if (!month) return { from: '', to: '' }
  const lastDay = new Date(Date.UTC(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 0)).getUTCDate()
  return { from: `${month}-01`, to: `${month}-${String(lastDay).padStart(2, '0')}` }
}
/**
 * 조회 버튼을 누르기 전(searched 없음)에 분개일 조건이 비어 있으면 기준 정산월 범위로 목록을 바로 조회한다.
 * 조회 버튼으로 직접 비운 날짜(searched=true)는 기간 제한 없음으로 존중한다.
 */
export function effectiveCriteria(criteria: JournalCriteria, searched: boolean, month: string | null): JournalCriteria {
  if (searched || criteria.from || criteria.to) return criteria
  return { ...criteria, ...monthRange(month) }
}
export async function get<T>(path: string, signal?: AbortSignal) {
  const { data } = await apiClient.request<T>(path, { signal })
  if (data == null) throw new Error('응답 데이터를 확인할 수 없습니다.')
  return data
}
export const journalSearchQuery = (criteria: JournalCriteria, enabled: boolean) =>
  queryOptions({
    queryKey: ['journals', 'search', criteria],
    queryFn: ({ signal }) => {
      const query = new URLSearchParams({ page: String(criteria.page), size: String(PAGE_SIZE) })
      Object.entries(criteria).forEach(([key, value]) => {
        if (key !== 'page' && value) query.set(key, String(value))
      })
      return get<JournalSearch>(`/api/v1/journals?${query}`, signal)
    },
    enabled,
    placeholderData: keepPreviousData,
    meta: { errorToast: false },
  })
export const imbalanceQuery = (id?: number) =>
  queryOptions({
    queryKey: ['journals', 'imbalances', id],
    queryFn: ({ signal }) =>
      get<Schemas['JournalImbalanceSearchResponse']>(`/api/v1/journals/imbalances?validationRunId=${id}`, signal),
    enabled: id != null,
    meta: { errorToast: false },
  })
export async function reverseJournal(id: number, body: Schemas['ReverseJournalRequest']) {
  const { data } = await apiClient.request<Schemas['ReverseJournalResponse']>(`/api/v1/journals/${id}/reverse`, {
    method: 'POST',
    body,
  })
  if (!data) throw new Error('역분개 결과를 확인할 수 없습니다.')
  return data
}
export async function requestCorrection(id: number, body: Schemas['JournalCorrectionExceptionRequest']) {
  const { data } = await apiClient.request<Schemas['JournalCorrectionExceptionResponse']>(
    `/api/v1/journals/${id}/correction-exceptions`,
    { method: 'POST', body },
  )
  if (!data) throw new Error('정정 요청 결과를 확인할 수 없습니다.')
  return data
}
/** #414 응답의 selected 쿼리를 그대로 유지하며, 외부 주소·잘못된 경로로 이동하지 않는다. */
export function correctionTarget(value?: string) {
  const target = safeRedirect(value)
  const url = new URL(target, 'https://redirect.local')
  return url.pathname === '/exceptions' ? target : null
}
