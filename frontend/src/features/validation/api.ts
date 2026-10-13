import { keepPreviousData, queryOptions } from '@tanstack/react-query'
import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'

type Schemas = components['schemas']
export type ValidationRun = Schemas['ValidationRunItemResponse']
export type ValidationRunPage = Schemas['ValidationRunSearchResponse']
export type ValidationRunDetail = Schemas['ValidationRunDetailResponse']
export type ValidationTarget = Schemas['ValidationTargetItemResponse']
export type ValidationProgress = Schemas['ValidationRunProgressResponse']
export type FinalizeChecklist = Schemas['FinalizeChecklistResponse']
export type CreatedRun = Schemas['CreateValidationRunResponse']

/** 화면정의서 §4-1 공통 규칙 7 — 목록은 20행씩, 상세 실행 선택지는 최근 20건. */
export const PAGE_SIZE = 20
const MAX_PAGE = Math.floor(2147483647 / PAGE_SIZE)

export const RUN_STATUSES = [
  { value: 'CREATED', label: '생성됨' },
  { value: 'RUNNING', label: '실행중' },
  { value: 'COMPLETED', label: '계산완료' },
  { value: 'FAILED', label: '실패' },
  { value: 'FINALIZED', label: '확정(잠김)' },
] as const
export const RUN_TYPES = [
  { value: 'MONTHLY', label: '월간 (MONTHLY)' },
  { value: 'MANUAL_CONTRACT', label: '계약 수동 (MANUAL_CONTRACT)' },
] as const

export interface RunCriteria {
  status: string
  page: number
}

/** 1차 화면과 같이 알 수 없는 status 는 전체로, page 는 1~최대 범위로 보정한다. */
export function normalizeCriteria(params: URLSearchParams): RunCriteria {
  const status = params.get('status') ?? ''
  const page = Number.parseInt(params.get('page') ?? '', 10)
  return {
    status: RUN_STATUSES.some((item) => item.value === status) ? status : '',
    page: Number.isNaN(page) ? 1 : Math.max(1, Math.min(page, MAX_PAGE)),
  }
}

async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  const { data } = await apiClient.request<T>(path, { signal })
  if (data == null) throw new Error('응답 데이터를 확인할 수 없습니다.')
  return data
}

export const runsQuery = (criteria: RunCriteria, size = PAGE_SIZE) =>
  queryOptions({
    queryKey: ['validation-runs', 'list', criteria, size],
    queryFn: ({ signal }) => {
      const query = new URLSearchParams({ page: String(criteria.page), size: String(size) })
      if (criteria.status) query.set('status', criteria.status)
      return get<ValidationRunPage>(`/api/v1/validation-runs?${query}`, signal)
    },
    placeholderData: keepPreviousData,
    meta: { errorToast: false },
  })

export const activeMonthlyQuery = (month: string) =>
  queryOptions({
    queryKey: ['validation-runs', 'active-monthly', month],
    queryFn: ({ signal }) =>
      get<Schemas['ActiveMonthlyRunResponse']>(
        `/api/v1/validation-runs/active-monthly?month=${encodeURIComponent(month)}`,
        signal,
      ),
    meta: { errorToast: false },
  })

export const runDetailQuery = (id: string) =>
  queryOptions({
    queryKey: ['validation-runs', 'detail', id],
    queryFn: ({ signal }) => get<ValidationRunDetail>(`/api/v1/validation-runs/${encodeURIComponent(id)}`, signal),
    meta: { errorToast: false },
  })

/** IF-API-49 — 진행 중인 동안 2초 간격으로 폴링하고, 끝나면(COMPLETED/FAILED/FINALIZED) 멈춘다. */
export const POLL_INTERVAL = 2000
export const isRunning = (status?: string) => status === 'RUNNING' || status === 'CREATED'
export const runProgressQuery = (id: string, enabled: boolean) =>
  queryOptions({
    queryKey: ['validation-runs', 'progress', id],
    queryFn: ({ signal }) =>
      get<ValidationProgress>(`/api/v1/validation-runs/${encodeURIComponent(id)}/progress`, signal),
    enabled,
    // execute 직후엔 배치가 아직 안 떠서 CREATED 가 조회될 수 있다 — 종료 상태에서만 멈춘다.
    refetchInterval: (query) => (query.state.data && !isRunning(query.state.data.status) ? false : POLL_INTERVAL),
    // 폴링은 항상 최신값이어야 하므로 캐시를 재사용하지 않는다.
    gcTime: 0,
    meta: { errorToast: false },
  })

export const finalizeChecklistQuery = (id: string, enabled: boolean) =>
  queryOptions({
    queryKey: ['validation-runs', 'checklist', id],
    queryFn: async ({ signal }) => {
      const checklist = await get<FinalizeChecklist>(
        `/api/v1/validation-runs/${encodeURIComponent(id)}/finalize-checklist`,
        signal,
      )
      if (!Array.isArray(checklist.conditions) || checklist.conditions.length !== 6) {
        throw new Error('확정 조건 응답 형식이 올바르지 않습니다.')
      }
      return checklist
    },
    enabled,
    meta: { errorToast: false },
  })

async function post<T>(path: string, body?: unknown, idempotencyKey?: string): Promise<T> {
  const { data } = await apiClient.request<T>(path, { method: 'POST', body, idempotencyKey })
  if (data == null) throw new Error('응답 데이터를 확인할 수 없습니다.')
  return data
}

/** IF-API-45 — 서버가 yyyy-MM 검증월과 runType 을 검증하고 중복 활성 MONTHLY 는 409(FGC-VRUN-001)로 막는다. */
export const createRun = (validationMonth: string, runType: string) =>
  post<CreatedRun>('/api/v1/validation-runs', { validationMonth, runType })

/** IF-API-48 — 202 수락만 의미하고 진행은 폴링으로 본다. */
export const executeRun = (id: string) =>
  post<Schemas['ValidationRunExecuteResponse']>(`/api/v1/validation-runs/${encodeURIComponent(id)}/execute`)

/** IF-API-51 — 같은 멱등키로 다시 보내면 서버가 같은 결과를 돌려준다. */
export const finalizeRun = (id: string, idempotencyKey: string) =>
  post<Schemas['FinalizeValidationRunResponse']>(
    `/api/v1/validation-runs/${encodeURIComponent(id)}/finalize`,
    undefined,
    idempotencyKey,
  )
