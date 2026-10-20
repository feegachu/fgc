import { ApiError } from '../../lib/api/errors'
import { errorText } from '../../lib/format'
import type { ContractStatusEvent } from './api'

export const contractTabs = [
  ['basic', '기본정보'],
  ['schedules', '예상 스케줄'],
  ['cap', '1,200%'],
  ['arbitrage', '차익거래'],
  ['ledger', '원장'],
  ['payment', '지급·대사'],
] as const
export type ContractTab = (typeof contractTabs)[number][0]
export const contractTab = (value: string | null): ContractTab =>
  contractTabs.find(([name]) => name === value)?.[0] ?? 'basic'

/** COMMON-500은 서버 내부 상세 대신 요청 ID만 노출한다. */
export const contractErrorText = (error: Error, fallback?: string) =>
  error instanceof ApiError && (error.code === 'FGC-COMMON-500' || error.status >= 500)
    ? `요청 ID: ${error.requestId ?? '—'}`
    : errorText(error, fallback)

export type StatusSort = 'effectiveAt' | 'receivedAt'
/** 유효한 일시 오름차순 → 사건 순번. 잘못된 일시는 끝에 둔다. 원본 캐시는 정렬하지 않는다. */
export function sortStatusEvents(events: ContractStatusEvent[], sort: StatusSort) {
  return [...events].sort((left, right) => {
    const a = Date.parse(left[sort] ?? ''),
      b = Date.parse(right[sort] ?? '')
    if (Number.isNaN(a) !== Number.isNaN(b)) return Number.isNaN(a) ? 1 : -1
    if (!Number.isNaN(a) && a !== b) return a - b
    return (left.eventSeq ?? 0) - (right.eventSeq ?? 0)
  })
}
