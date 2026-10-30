import { queryOptions } from '@tanstack/react-query'
import { ApiError, apiClient } from '../../lib/api/client'
import { errorText } from '../../lib/format'

/** IF-API-87 목록·합계·기준 응답. 서버(report 패키지)의 DTO 와 같은 모양이다. */
export type EntryType = 'PAYMENT' | 'HELD' | 'ADJUSTMENT' | 'CLAWBACK'
export interface StatementEntry {
  entryKey: string
  entryType: EntryType
  contractId: number | null
  contractNo: string | null
  commissionItemCode: string
  commissionItemName: string
  installmentNo: number | null
  basisDate: string
  statusCode: string
  statusLabel: string
  amount: number | null
  policyVersionId: number | null
  source: { entityType: string; id: number }
  dataStatus: 'AVAILABLE' | 'MISSING'
}
export interface StatementBucket {
  count: number
  amount: number
}
export interface StatementSummary {
  payment: StatementBucket
  held: StatementBucket
  adjustment: StatementBucket
  clawback: StatementBucket
}
export interface StatementBasis {
  month: string
  range: { from: string; to: string }
  dateBasis: Record<EntryType, string>
  warnings: string[]
}
export interface StatementPage {
  content: StatementEntry[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
export interface StatementList {
  items: StatementPage
  summary: StatementSummary
  basis: StatementBasis
}
export interface StatementDetail {
  entry: StatementEntry
  evidence: Record<string, string | number | null>
  basis: StatementBasis
}

export const PAGE_SIZE = 20
/** 서버(AgentStatementRepository.SORT_KEYS)와 같은 정렬 key. 상태·내역 구분은 순서 기준이 없어도 서버가 허용한다. */
export const STATEMENT_SORT_KEYS = [
  'basisDate',
  'contractNo',
  'commissionItemCode',
  'installmentNo',
  'entryType',
  'statusCode',
  'amount',
] as const
export const ENTRY_TYPE_OPTIONS: readonly (readonly [EntryType, string])[] = [
  ['PAYMENT', '지급'],
  ['HELD', '보류'],
  ['ADJUSTMENT', '조정'],
  ['CLAWBACK', '환수'],
]
export const entryTypeLabel = (type: string) => ENTRY_TYPE_OPTIONS.find(([key]) => key === type)?.[1] ?? type

export interface StatementFilters {
  month: string
  agentId: string
  contractNo: string
  commissionItemId: string
  entryType: string
  sort: string
  page: number
}

export function readFilters(params: URLSearchParams, defaultMonth: string): StatementFilters {
  const page = Number(params.get('page'))
  return {
    month: params.get('month') ?? defaultMonth,
    agentId: params.get('agentId') ?? '',
    contractNo: params.get('contractNo') ?? '',
    commissionItemId: params.get('commissionItemId') ?? '',
    entryType: params.get('entryType') ?? '',
    sort: params.get('sort') ?? '',
    page: Number.isInteger(page) && page > 0 ? page : 1,
  }
}

/** URL 에 남기는 값. agentId 는 내부 조회에서만 의미가 있고 본인 화면은 보내지 않는다. */
export function filterParams(filters: StatementFilters, keepAgent: boolean) {
  const query = new URLSearchParams()
  query.set('month', filters.month)
  if (keepAgent && filters.agentId) query.set('agentId', filters.agentId)
  for (const key of ['contractNo', 'commissionItemId', 'entryType', 'sort'] as const)
    if (filters[key]) query.set(key, filters[key])
  query.set('page', String(filters.page))
  return query
}

/** 본인 경로(agentId 없음)와 내부 경로를 한곳에서 정한다. 본인 화면은 설계사 ID 를 보내지 않는다. */
export function statementPath(agentId: string | null) {
  return agentId ? `/api/v1/reports/agents/${agentId}/statements` : '/api/v1/agent/me/statements'
}

export function statementListQuery(filters: StatementFilters, agentId: string | null, enabled: boolean) {
  return queryOptions({
    queryKey: ['agent-statements', 'list', agentId, filters],
    enabled,
    queryFn: async ({ signal }) => {
      const query = filterParams(filters, false)
      query.set('size', String(PAGE_SIZE))
      const response = await apiClient.request<StatementList>(`${statementPath(agentId)}?${query}`, { signal })
      if (!response.data?.items || !Array.isArray(response.data.items.content))
        throw new ApiError(null, response.requestId, 500)
      return response.data
    },
    meta: { errorToast: false },
  })
}

export function statementDetailQuery(entryKey: string, agentId: string | null) {
  return queryOptions({
    queryKey: ['agent-statements', 'detail', agentId, entryKey],
    enabled: entryKey !== '',
    queryFn: async ({ signal }) => {
      const response = await apiClient.request<StatementDetail>(
        `${statementPath(agentId)}/${encodeURIComponent(entryKey)}`,
        { signal },
      )
      if (!response.data?.entry) throw new ApiError(null, response.requestId, 500)
      return response.data
    },
    meta: { errorToast: false },
  })
}

/** 403·없는 상세·조회 실패를 서로 다른 문장으로 구별한다(0건과 섞지 않는다). */
export function statementError(error: Error | null, fallback = '수수료 명세를 불러오지 못했습니다. 잠시 후 다시 시도하세요.') {
  if (error instanceof ApiError) {
    if (error.status === 403) return '이 명세를 조회할 권한이 없습니다.'
    if (error.status === 404) return '요청한 명세를 찾을 수 없습니다.'
    if (error.status >= 500) return `요청 ID: ${error.requestId ?? '-'}`
  }
  return errorText(error, fallback)
}
