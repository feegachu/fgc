import { keepPreviousData, queryOptions } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'

type Schemas = components['schemas']
export type ExceptionCase = Schemas['ExceptionCaseResponseDTO']
export type ExceptionAction = Schemas['ExceptionActionResponse']
export type ExceptionOccurrence = Schemas['ExceptionOccurrenceResponse']
export type ExceptionSearchResult = Schemas['ExceptionCaseSearchResponse']
export type ExceptionOptions = Schemas['ExceptionOptionsResponse']
export type JournalAccount = Schemas['JournalAccountRow']
export type JournalDetail = Schemas['JournalDetailResponse']
export type JournalCorrectionRequest = Schemas['JournalCorrectionActionRequest']
export type JournalCorrectionResult = Schemas['JournalCorrectionActionResponse']
export type ExceptionStatus = 'NEW' | 'IN_REVIEW' | 'RESOLVED' | 'REJECTED'

export const PAGE_SIZE = 20
/** 화면의 상태 select 값. API 에서는 OPEN 이 미처리 묶음이고 빈 값이 전체다. */
export const ALL_STATUS = 'ALL'
export const OPEN_STATUS = 'OPEN'

export interface ExceptionFilters {
  type: string
  reasonCode: string
  severity: string
  status: string
  assigneeFilter: string
  validationMonth: string
  contractNo: string
  page: number
}

export function searchPath(filters: ExceptionFilters) {
  const query = new URLSearchParams()
  const set = (key: string, value: string) => value && query.set(key, value)
  set('type', filters.type)
  set('reasonCode', filters.reasonCode)
  set('severity', filters.severity)
  // 빈 status 는 "전체"라서 보내야 하고, 아예 빠지면 서버가 미처리로 연다.
  query.set('status', filters.status === ALL_STATUS ? '' : filters.status)
  set('assigneeFilter', filters.assigneeFilter)
  set('validationMonth', filters.validationMonth)
  set('contractNo', filters.contractNo.trim())
  query.set('page', String(filters.page))
  query.set('size', String(PAGE_SIZE))
  return `/api/v1/exceptions?${query.toString()}`
}

async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  const { data } = await apiClient.request<T>(path, { signal })
  if (data == null) throw new Error('응답 데이터를 확인할 수 없습니다.')
  return data
}

export const exceptionSearchQueryOptions = (filters: ExceptionFilters) =>
  queryOptions({
    queryKey: ['exceptions', 'search', filters],
    queryFn: ({ signal }) => get<ExceptionSearchResult>(searchPath(filters), signal),
    placeholderData: keepPreviousData,
    meta: { errorToast: false },
  })

export const exceptionOptionsQueryOptions = () =>
  queryOptions({
    queryKey: ['exceptions', 'options'],
    queryFn: ({ signal }) => get<ExceptionOptions>('/api/v1/exceptions/options', signal),
    staleTime: 5 * 60_000,
  })

export const journalAccountsQueryOptions = () =>
  queryOptions({
    queryKey: ['journals', 'accounts'],
    queryFn: ({ signal }) => get<JournalAccount[]>('/api/v1/journals/accounts', signal),
    staleTime: 5 * 60_000,
  })

export const journalDetailQueryOptions = (journalId: string) =>
  queryOptions({
    queryKey: ['journals', 'detail', journalId],
    queryFn: ({ signal }) => get<JournalDetail>(`/api/v1/journals/${encodeURIComponent(journalId)}`, signal),
    meta: { errorToast: false },
  })

export interface ActionInput {
  actionType: string
  reason: string
  evidenceRef: string
}

export async function postAction(exceptionCaseId: number, input: ActionInput): Promise<ExceptionAction> {
  const { data } = await apiClient.request<ExceptionAction>(`/api/v1/exceptions/${exceptionCaseId}/actions`, {
    method: 'POST',
    body: { actionType: input.actionType, reason: input.reason.trim(), evidenceRef: input.evidenceRef.trim() || null },
  })
  if (!data) throw new Error('처리 결과를 확인할 수 없습니다.')
  return data
}

export async function postJournalCorrection(
  exceptionCaseId: number,
  body: JournalCorrectionRequest,
): Promise<JournalCorrectionResult> {
  const { data } = await apiClient.request<JournalCorrectionResult>(
    `/api/v1/exceptions/${exceptionCaseId}/journal-correction`,
    { method: 'POST', body },
  )
  if (!data) throw new Error('정정 결과를 확인할 수 없습니다.')
  return data
}

/**
 * 조치 결과를 이미 받아 온 목록 캐시에 반영한다. 재조회하면 미처리 필터에서 방금 처리한 행이
 * 사라져 패널이 비므로, 사용자가 다시 조회할 때까지 행을 그대로 두고 값만 바꾼다.
 * 요약 카드 건수는 다음 조회 때 갱신되도록 stale 로만 표시한다.
 */
export function applyActionToCache(
  client: QueryClient,
  exceptionCaseId: number,
  action: ExceptionAction,
  extra: Partial<ExceptionCase> = {},
) {
  client.setQueriesData<ExceptionSearchResult>({ queryKey: ['exceptions', 'search'] }, (current) => {
    if (!current) return current
    return {
      ...current,
      content: current.content?.map((item) =>
        item.exceptionCaseId === exceptionCaseId
          ? {
              ...item,
              status: action.toStatus,
              statusLabel: action.toStatusLabel,
              ...(action.actionType === 'ASSIGN'
                ? { assignedTo: action.actionBy, assigneeLoginId: action.actionByLoginId }
                : {}),
              ...extra,
              actions: [...(item.actions ?? []), action],
            }
          : item,
      ),
    }
  })
  void client.invalidateQueries({ queryKey: ['exceptions', 'search'], refetchType: 'none' })
}
