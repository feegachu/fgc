import { keepPreviousData, queryOptions } from '@tanstack/react-query'
import { apiClient } from '../../lib/api/client'
import { ApiError } from '../../lib/api/errors'
import type { components } from '../../lib/api/schema'
import { validMonth } from '../../stores/workspace'
import { PAYMENT_STAGE, RESULT_TYPE } from './labels'
import { formatSort, parseSort } from '../../lib/tableSort'

type Schemas = components['schemas']
export type Run = Schemas['ReconciliationRunHistoryResponse']
export type Result = Schemas['ReconciliationResultListItemResponse']
export type Detail = Schemas['ReconciliationResultDetailResponse']
export type Match = Schemas['Match']
export type Summary = Schemas['ReconciliationSummaryResponse']
export type History = Schemas['ReconciliationRunSearchResponse']
export type Results = Schemas['ReconciliationResultSearchResponse']
export const PAGE_SIZE = 20
/** 서버(ReconciliationRunHistoryRepository.SORT_KEYS)와 같은 이력 정렬 key. 지급단계·상태는 순서 기준이 없어 제외한다. */
export const HISTORY_SORT_KEYS = [
  'createdAt',
  'reconciliationRunId',
  'settlementMonth',
  'insurerName',
  'targetCount',
  'matchedCount',
  'exceptionCount',
  'matchRatePct',
  'differenceTotal',
  'startedAt',
] as const
/** 서버(ReconciliationResultRepository.RESULT_SORT_KEYS)와 같은 결과 정렬 key. 결과 유형·원인은 제외한다. */
export const RESULT_SORT_KEYS = [
  'createdAt',
  'contractNo',
  'commissionItemName',
  'installmentNo',
  'expectedAgentName',
  'actualAgentName',
  'expectedTotalAmount',
  'actualTotalAmount',
  'differenceAmount',
] as const
export const FILTER_KEYS = {
  stage: '',
  insurer: '',
  page: '',
  runId: '',
  runMonth: '',
  resultId: '',
  resultType: '',
  onlyMismatch: '',
  resultPage: '',
  sort: '',
  resultSort: '',
}
export const positiveId = (value: string | null) =>
  value && /^[1-9]\d*$/.test(value) && Number.isSafeInteger(Number(value)) ? value : ''
export function criteriaFromParams(params: URLSearchParams, month: string | null) {
  const stage = params.get('stage') ?? ''
  return {
    month: validMonth(month) ? month : '',
    stage: Object.hasOwn(PAYMENT_STAGE, stage) ? stage : '',
    insurer: positiveId(params.get('insurer')),
    sort: formatSort(parseSort(params.get('sort'), HISTORY_SORT_KEYS)),
    page: Math.min(Number(positiveId(params.get('page')) || 1), Math.floor(2147483647 / PAGE_SIZE)),
  }
}
export type Criteria = ReturnType<typeof criteriaFromParams>
export type RunInput = Pick<Criteria, 'month' | 'stage' | 'insurer'>
async function get<T>(path: string, signal?: AbortSignal) {
  const { data } = await apiClient.request<T>(path, { signal })
  if (!data) throw new Error('응답 데이터를 확인할 수 없습니다.')
  return data
}
export const historyQuery = (criteria: Criteria) =>
  queryOptions({
    queryKey: ['reconciliations', 'history', criteria],
    queryFn: ({ signal }) => {
      const query = new URLSearchParams({
        month: criteria.month,
        page: String(criteria.page),
        size: String(PAGE_SIZE),
        sort: criteria.sort || 'createdAt,desc',
      })
      if (criteria.stage) query.set('stage', criteria.stage)
      if (criteria.insurer) query.set('insurerId', criteria.insurer)
      return get<History>(`/api/v1/reconciliations?${query}`, signal)
    },
    enabled: !!criteria.month,
    placeholderData: keepPreviousData,
    meta: { errorToast: false },
    // 개별 실행 조회 API는 없다. 기존 이력 API의 서버 status를 사용한다.
    refetchInterval: (query) =>
      query.state.data?.content?.some((run) => run.status === 'RUNNING' || run.status === 'CREATED') ? 2000 : false,
  })
export const resultQuery = (runId: string, resultType: string, page: number, running: boolean, sort = '') =>
  queryOptions({
    queryKey: ['reconciliations', 'results', runId, resultType, page, sort],
    queryFn: ({ signal }) => {
      const query = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE), sort: sort || 'createdAt,desc' })
      if (Object.hasOwn(RESULT_TYPE, resultType)) query.set('resultType', resultType)
      return get<Results>(`/api/v1/reconciliations/${runId}/results?${query}`, signal)
    },
    enabled: !!runId,
    meta: { errorToast: false },
    refetchInterval: running ? 2000 : false,
  })
export const detailQuery = (resultId: string) =>
  queryOptions({
    queryKey: ['reconciliations', 'detail', resultId],
    queryFn: ({ signal }) => get<Detail>(`/api/v1/reconciliations/results/${resultId}`, signal),
    enabled: !!resultId,
    meta: { errorToast: false },
  })
export async function createRun(input: RunInput) {
  const query = new URLSearchParams({ month: input.month, status: 'COMPLETED', page: '1', size: '1' })
  const latest = await get<Schemas['ValidationRunSearchResponse']>(`/api/v1/validation-runs?${query}`)
  const validationRunId = latest.content?.[0]?.validationRunId
  if (validationRunId == null)
    throw new ApiError(
      {
        code: 'FGC-RECO-CLIENT-001',
        message: '이 정산월의 월 통합검증이 아직 완료되지 않았습니다. 검증을 먼저 완료하세요.',
      },
      null,
      422,
    )
  const body: Schemas['CreateReconciliationRunRequest'] = {
    settlementMonth: input.month,
    paymentStage: input.stage,
    insurerId: Number(input.insurer),
    validationRunId,
  }
  const { data } = await apiClient.request<Schemas['CreateReconciliationRunResponse']>('/api/v1/reconciliations', {
    method: 'POST',
    body,
  })
  if (data?.reconciliationRunId == null) throw new Error('대사 실행 결과를 확인할 수 없습니다.')
  return data
}
export async function bulkExceptions(runId: string) {
  const { data } = await apiClient.request<Schemas['ReconciliationExceptionBulkCreateResponse']>(
    `/api/v1/reconciliations/${runId}/exceptions`,
    { method: 'POST' },
  )
  if (!data) throw new Error('예외 일괄 생성 결과를 확인할 수 없습니다.')
  return data
}
