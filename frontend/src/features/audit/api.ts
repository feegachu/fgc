import { keepPreviousData, queryOptions } from '@tanstack/react-query'
import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'

export type AuditLog = components['schemas']['AuditLogResponse']
export type AuditLogPage = components['schemas']['PageResponseAuditLogResponse']
export type AuditLogOptions = components['schemas']['AuditLogOptionsResponse']
export type AuditLogDetail = components['schemas']['AuditLogDetailResponse']

/** 1차 AuditLogViewController 와 같은 한 화면 20행(화면정의서 §4-1 공통 규칙 7). */
export const PAGE_SIZE = 20
const MAX_PAGE = Math.floor(2147483647 / PAGE_SIZE)
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

export interface AuditCriteria {
  entityType: string
  entityId: string
  userId: string
  action: string
  from: string
  to: string
  page: number
}

/**
 * URL 쿼리를 1차 화면과 같은 규칙으로 보정한다 — 시작일 > 종료일이면 교환, page 는 1~최대 범위로.
 * 목록 API(IF-API-52)는 뒤집힌 기간을 FGC-COMMON-002 로 거절하므로(계약 유지) 화면에서 먼저 맞춘다.
 * 형식이 틀린 날짜·사용자 ID 는 1차에서 400 화면이 되던 값이라 조건에서 뺀다.
 */
export function normalizeCriteria(params: URLSearchParams): AuditCriteria {
  const text = (key: string) => params.get(key)?.trim() ?? ''
  let from = ISO_DATE.test(text('from')) ? text('from') : ''
  let to = ISO_DATE.test(text('to')) ? text('to') : ''
  if (from && to && from > to) [from, to] = [to, from]
  const page = Number.parseInt(text('page'), 10)
  return {
    entityType: text('entityType'),
    entityId: text('entityId'),
    userId: /^\d+$/.test(text('userId')) ? text('userId') : '',
    action: text('action'),
    from,
    to,
    page: Number.isNaN(page) ? 1 : Math.max(1, Math.min(page, MAX_PAGE)),
  }
}

export const auditLogsQuery = (criteria: AuditCriteria) =>
  queryOptions({
    queryKey: ['audit-logs', 'list', criteria],
    queryFn: async ({ signal }) => {
      const query = new URLSearchParams({ size: String(PAGE_SIZE) })
      Object.entries(criteria).forEach(([key, value]) => {
        if (value !== '') query.set(key, String(value))
      })
      return (await apiClient.request<AuditLogPage>(`/api/v1/audit-logs?${query}`, { signal })).data
    },
    // 페이지를 넘길 때 표가 비었다가 다시 차지 않게 이전 결과를 유지한다.
    placeholderData: keepPreviousData,
    meta: { errorToast: false },
  })

export const auditLogOptionsQuery = () =>
  queryOptions({
    queryKey: ['audit-logs', 'options'],
    queryFn: async ({ signal }) =>
      (await apiClient.request<AuditLogOptions>('/api/v1/audit-logs/options', { signal })).data,
    meta: { errorToast: false },
  })

export const auditLogDetailQuery = (auditLogId: number) =>
  queryOptions({
    queryKey: ['audit-logs', 'detail', auditLogId],
    queryFn: async ({ signal }) =>
      (await apiClient.request<AuditLogDetail>(`/api/v1/audit-logs/${auditLogId}`, { signal })).data,
    // 감사로그는 append-only 라 한 번 받은 상세가 바뀌지 않는다.
    staleTime: Infinity,
    meta: { errorToast: false },
  })
