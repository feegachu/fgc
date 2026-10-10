import { keepPreviousData, queryOptions } from '@tanstack/react-query'
import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'
import { PAYMENT_STAGE, SCHEDULE_PURPOSE, SCHEDULE_REGIME, SCHEDULE_STATUS } from './labels'

type Schemas = components['schemas']
export type ScheduleHeader = Schemas['ScheduleHeaderResponse']
export type ScheduleLine = Schemas['ScheduleLineResponse']
export type ScheduleDetail = Schemas['ScheduleDetailResponse']
export type SchedulePage = Schemas['PageResponseScheduleHeaderResponse']
export type ScheduleRegenResult = Schemas['ScheduleRegenResponse']
export type PaymentStage = keyof typeof PAYMENT_STAGE

/** 1차 schedule-list.js 와 같은 한 화면 20행(인터페이스정의서 §2-3). */
export const PAGE_SIZE = 20
/** ScheduleRegenRequest.reason @Size(max = 40). */
export const REASON_MAX = 40

export interface ScheduleFilters {
  contractNo: string
  stage: string
  regime: string
  purpose: string
  status: string
}
export interface ScheduleCriteria extends ScheduleFilters {
  page: number
}
export const FILTER_NAMES = ['contractNo', 'stage', 'regime', 'purpose', 'status'] as const
export const DEFAULT_PURPOSE = 'OPERATIONAL'

const allowed = (labels: Record<string, string>, value: string | null, fallback: string) =>
  value && Object.hasOwn(labels, value) ? value : fallback

/** 1차 positivePage — 숫자가 아니거나 1 미만이면 1페이지. */
export function positivePage(value: string | null) {
  if (!value || !/^[1-9]\d*$/.test(value)) return 1
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) ? parsed : 1
}

/**
 * URL 쿼리를 1차 stateFromUrl 과 같은 규칙으로 보정한다. 목록에 없는 코드값은 API 가 400 으로
 * 거절하므로(enum 바인딩) 1차처럼 화면에서 "전체"(용도는 운영)로 되돌린다.
 */
export function criteriaFromParams(params: URLSearchParams): ScheduleCriteria {
  return {
    contractNo: params.get('contractNo')?.trim() ?? '',
    stage: allowed(PAYMENT_STAGE, params.get('stage'), ''),
    regime: allowed(SCHEDULE_REGIME, params.get('regime'), ''),
    purpose: allowed(SCHEDULE_PURPOSE, params.get('purpose'), DEFAULT_PURPOSE),
    status: allowed(SCHEDULE_STATUS, params.get('status'), ''),
    page: positivePage(params.get('page')),
  }
}

/** 응답 페이지가 범위를 넘으면 마지막 페이지(결과가 없으면 1)로 맞춘다 — 1차 normalizePage. */
export function normalizePage(requested: number, totalPages: number | undefined) {
  const last = Number.isInteger(totalPages) && totalPages! > 0 ? totalPages! : 1
  return Math.min(Math.max(1, requested), last)
}

export function filterQuery(filters: ScheduleFilters) {
  const query = new URLSearchParams()
  FILTER_NAMES.forEach((name) => {
    if (filters[name]) query.set(name, filters[name])
  })
  return query
}

const schedulePath = (id: string, suffix = '') => `/api/v1/schedules/${encodeURIComponent(id)}${suffix}`

async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  const { data } = await apiClient.request<T>(path, { signal })
  if (data == null) throw new Error('응답 데이터를 확인할 수 없습니다.')
  return data
}

export const scheduleListQuery = ({ page, ...filters }: ScheduleCriteria) =>
  queryOptions({
    queryKey: ['schedules', 'list', filters, page],
    queryFn: ({ signal }) => {
      const query = filterQuery(filters)
      query.set('page', String(page))
      query.set('size', String(PAGE_SIZE))
      return get<SchedulePage>(`/api/v1/schedules?${query}`, signal)
    },
    // 페이지를 넘길 때 표가 비었다가 다시 차지 않게 이전 결과를 유지한다.
    placeholderData: keepPreviousData,
    meta: { errorToast: false },
  })

export const scheduleDetailQuery = (id: string) =>
  queryOptions({
    queryKey: ['schedules', 'detail', id],
    queryFn: async ({ signal }) => {
      const detail = await get<ScheduleDetail>(schedulePath(id), signal)
      if (!detail.header) throw new Error('응답 데이터를 확인할 수 없습니다.')
      return detail
    },
    meta: { errorToast: false },
  })

export const scheduleVersionsQuery = (id: string) =>
  queryOptions({
    queryKey: ['schedules', 'versions', id],
    queryFn: ({ signal }) => get<ScheduleHeader[]>(schedulePath(id, '/versions'), signal),
    meta: { errorToast: false },
  })

/** IF-API-28C — 같은 계약의 다른 지급단계에서 사용 중인 운영 스케줄 ID. */
export const findActiveSchedule = (id: string, paymentStage: string) =>
  get<number>(schedulePath(id, `/active?paymentStage=${encodeURIComponent(paymentStage)}`))

export async function confirmSchedule(id: string) {
  const { data } = await apiClient.request<ScheduleDetail>(schedulePath(id, '/confirm'), { method: 'POST' })
  if (!data?.header) throw new Error('확정 결과를 확인할 수 없습니다.')
  return data
}

export async function regenerateSchedule(id: string, reason: string) {
  const { data } = await apiClient.request<ScheduleRegenResult>(schedulePath(id, '/regenerate'), {
    method: 'POST',
    body: { reason },
  })
  if (data?.scheduleHeaderId == null) throw new Error('새 버전을 확인할 수 없습니다.')
  return data
}

export const listCsvPath = (filters: ScheduleFilters) => {
  const query = filterQuery(filters).toString()
  return `/api/v1/schedules/export.csv${query ? `?${query}` : ''}`
}
export const detailCsvPath = (id: string) => schedulePath(id, '/export.csv')
