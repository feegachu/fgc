import { keepPreviousData, queryOptions } from '@tanstack/react-query'
import { apiClient } from '../../lib/api/client'
import type { RequestOptions } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
import { capStatusLabels, contractStatusLabels } from './labels'
import { formatSort, parseSort } from '../../lib/tableSort'

type Schema = components['schemas']
export type ContractSummary = Schema['ContractView']
export type ContractPage = Schema['PageResponseContractView']
export type ContractDetail = Schema['ContractDetailResponse']
export type ContractCreateRequest = Schema['ContractCreateRequest']
export type ContractUpdateRequest = Schema['ContractUpdateRequest']
export type ContractSaveRequest = ContractCreateRequest
export type ContractStatusEvent = Schema['ContractStatusEventResponse']
export type ContractSchedule = Schema['ContractScheduleResponse']
export type ContractCapCheck = Schema['CapCheckSaveResult']
export type ContractArbitrageCheck = Schema['ArbitrageCheckView']
export type ContractJournal = Schema['ContractJournalResponse']
export type ContractTransactions = Schema['ContractTransactionTabResponse']
export type ReArbitrageCheckRequest = Schema['ReArbitrageCheckRequest']

/** CONT-W01의 기존 한 페이지 20행을 유지한다. */
export const PAGE_SIZE = 20
export interface ContractFilters {
  contractNo: string
  insurerId: string
  productOfferingId: string
  agentId: string
  orgId: string
  currentStatus: string
  capResultStatus: string
  contractDateFrom: string
  contractDateTo: string
  sort: string
}
export interface ContractCriteria extends ContractFilters {
  page: number
}
/** 서버(ContractQueryRepository.SORT_KEYS)와 같은 정렬 key */
export const CONTRACT_SORT_KEYS = ['contractNo', 'insurerName', 'productName', 'contractDate', 'premium', 'agent'] as const
/** 설계사 홈의 '확인 필요 계약' 카드와 같은 한도 판정 묶음 */
export const NEED_CHECK_STATUSES = 'WARNING,VIOLATION,REVIEW_REQUIRED'
export const FILTER_NAMES = [
  'contractNo',
  'insurerId',
  'productOfferingId',
  'agentId',
  'orgId',
  'currentStatus',
  'capResultStatus',
  'contractDateFrom',
  'contractDateTo',
  'sort',
] as const

export function validDate(value: string | null | undefined): value is string {
  if (!value || !/^(?!0000)\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const date = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value
}
export function positivePage(value: string | null) {
  return value && /^[1-9]\d*$/.test(value) && Number.isSafeInteger(Number(value)) ? Number(value) : 1
}
const referenceId = (value: string | null) =>
  value && /^[1-9]\d*$/.test(value) && Number.isSafeInteger(Number(value)) ? value : ''
const allowedCode = (labels: Record<string, string>, value: string | null) =>
  value && Object.hasOwn(labels, value) ? value : ''

/** 쉼표로 이어 쓴 코드 목록. 하나라도 모르는 코드면 검색 기본값으로 돌린다. */
const allowedCodes = (labels: Record<string, string>, value: string | null) => {
  const codes = value?.split(',') ?? []
  return codes.length > 0 && codes.every((code) => Object.hasOwn(labels, code)) ? codes.join(',') : ''
}

/** 잘못된 URL 코드·날짜·ID는 검색 기본값으로 돌리고 현재 PC 날짜는 검색 조건에 넣지 않는다. */
export function criteriaFromParams(params: URLSearchParams): ContractCriteria {
  const from = params.get('contractDateFrom')
  const to = params.get('contractDateTo')
  return {
    contractNo: params.get('contractNo')?.trim() ?? '',
    insurerId: referenceId(params.get('insurerId')),
    productOfferingId: referenceId(params.get('productOfferingId')),
    agentId: referenceId(params.get('agentId')),
    orgId: referenceId(params.get('orgId')),
    currentStatus: allowedCode(contractStatusLabels, params.get('currentStatus')),
    capResultStatus: allowedCodes(capStatusLabels, params.get('capResultStatus')),
    contractDateFrom: validDate(from) ? from : '',
    contractDateTo: validDate(to) ? to : '',
    sort: formatSort(parseSort(params.get('sort'), CONTRACT_SORT_KEYS)),
    page: positivePage(params.get('page')),
  }
}
export function normalizePage(requested: number, totalPages: number | undefined) {
  const last = Number.isInteger(totalPages) && totalPages! > 0 ? totalPages! : 1
  return Math.min(Math.max(1, requested), last)
}
export function filterQuery(filters: ContractFilters) {
  const query = new URLSearchParams()
  FILTER_NAMES.forEach((key) => {
    if (filters[key]) query.set(key, filters[key])
  })
  return query
}

const contractPath = (id: string | number, suffix = '') =>
  `/api/v1/contracts/${encodeURIComponent(String(id))}${suffix}`
async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { data } = await apiClient.request<T>(path, options)
  if (data == null) throw new Error('응답 데이터를 확인할 수 없습니다.')
  return data
}
export const contractListQuery = ({ page, ...filters }: ContractCriteria) =>
  queryOptions({
    queryKey: ['contracts', 'list', filters, page],
    queryFn: ({ signal }) => {
      const query = filterQuery(filters)
      query.set('page', String(page))
      query.set('size', String(PAGE_SIZE))
      return request<ContractPage>(`/api/v1/contracts?${query}`, { signal })
    },
    placeholderData: keepPreviousData,
    meta: { errorToast: false },
  })

export async function getContract(id: string | number, signal?: AbortSignal) {
  const data = await request<ContractDetail>(contractPath(id), { signal })
  if (data.contractId == null) throw new Error('계약 정보를 확인할 수 없습니다.')
  return data
}
export const contractDetailQuery = (id: string) =>
  queryOptions({
    queryKey: ['contracts', 'detail', id],
    queryFn: ({ signal }) => getContract(id, signal),
    enabled: Boolean(id),
    meta: { errorToast: false },
  })
const contractTabQuery = <T>(id: string, tab: string, suffix = `/${tab}`, extraKey?: string) =>
  queryOptions({
    queryKey: ['contracts', 'detail', id, tab, ...(extraKey ? [extraKey] : [])],
    queryFn: ({ signal }) => request<T>(contractPath(id, suffix), { signal }),
    enabled: Boolean(id),
    meta: { errorToast: false },
  })
export const contractEventsQuery = (id: string) => contractTabQuery<ContractStatusEvent[]>(id, 'status-events')
export const contractSchedulesQuery = (id: string, paymentStage: string) =>
  contractTabQuery<ContractSchedule>(
    id,
    'schedules',
    `/schedules?paymentStage=${encodeURIComponent(paymentStage)}`,
    paymentStage,
  )
export const contractCapChecksQuery = (id: string) => contractTabQuery<ContractCapCheck[]>(id, 'cap-checks')
export const contractArbitrageChecksQuery = (id: string) =>
  contractTabQuery<ContractArbitrageCheck[]>(id, 'arbitrage-checks')
export const contractJournalsQuery = (id: string) => contractTabQuery<ContractJournal[]>(id, 'journals')
export const contractTransactionsQuery = (id: string) => contractTabQuery<ContractTransactions>(id, 'transactions')

export async function createContract(body: ContractCreateRequest) {
  const data = await request<Schema['ContractCreateResponse']>('/api/v1/contracts', { method: 'POST', body })
  if (data.contractId == null) throw new Error('등록된 계약을 확인할 수 없습니다.')
  return data
}
export async function updateContract(id: string | number, body: ContractUpdateRequest) {
  const data = await request<Schema['ContractUpdateResponse']>(contractPath(id), { method: 'PUT', body })
  if (data.contractId == null) throw new Error('수정된 계약을 확인할 수 없습니다.')
  return data
}
export const recheckContractCap = (id: string) =>
  request<ContractCapCheck[]>(contractPath(id, '/cap-check'), { method: 'POST' })
export const recheckContractArbitrage = (id: string, body: ReArbitrageCheckRequest) =>
  request<Schema['ReArbitrageCheckResponse']>(contractPath(id, '/arbitrage-check'), { method: 'POST', body })
export const regenerateContractSchedules = (id: string, reason: string) =>
  request<number[]>(contractPath(id, `/schedules/regenerate?reason=${encodeURIComponent(reason)}`), { method: 'POST' })

export function listCsvPath(filters: ContractFilters) {
  const query = filterQuery(filters).toString()
  return `/api/v1/contracts/export.csv${query ? `?${query}` : ''}`
}
