import { ApiError } from '../../lib/api/errors'
import { errorText, internalErrorText } from '../../lib/format'
import type { ContractStatusEvent } from './api'

export const contractTabs = [
  ['basic', '기본정보'],
  ['schedules', '예상 스케줄'],
  ['cap', '1,200%'],
  ['arbitrage', '차익거래'],
  ['acquisition', '계약체결비용'],
  ['ledger', '원장'],
  ['payment', '지급/대사'],
] as const
export type ContractTab = (typeof contractTabs)[number][0]
export const contractTabIcons: Record<ContractTab, string> = {
  basic: 'description', schedules: 'calendar_month', cap: 'fact_check', arbitrage: 'search',
  acquisition: 'payments', ledger: 'menu_book', payment: 'payments',
}
export const contractTabDescriptions: Record<ContractTab, string> = {
  basic: '기본정보 계약 조건과 보험료, 납입기간을 확인합니다.',
  schedules: '예상 스케줄 회차별 수령인과 예상 지급액을 확인합니다.',
  cap: '1,200% 지급단계별 한도와 산입액, 최신 판정을 확인합니다.',
  arbitrage: '차익거래 납입보험료와 수수료 등을 비교한 검증 결과입니다.',
  acquisition: '계약체결비용 선지급 및 유지관리 비용의 한도를 확인합니다.',
  ledger: '원장 이 계약에서 발생한 분개의 차변과 대변을 확인합니다.',
  payment: '지급/대사 실제 지급 내역과 예상액의 차이를 확인합니다.',
}
export const contractTab = (value: string | null): ContractTab =>
  contractTabs.find(([name]) => name === value)?.[0] ?? 'basic'

/** COMMON-500은 서버 내부 상세 대신 표준 문구와 요청번호만 노출한다. */
export const contractErrorText = (error: Error, fallback?: string) =>
  error instanceof ApiError && (error.code === 'FGC-COMMON-500' || error.status >= 500)
    ? internalErrorText(error.requestId)
    : errorText(error, fallback)

/** IF-API-64 역할(인터페이스정의서 §4-4-8). 서버가 다시 검사하므로 화면은 버튼만 가린다. */
export const canPreviewStatusEvent = (roleCode?: string) =>
  ['SETTLEMENT', 'GA_ADMIN', 'SYSTEM_ADMIN'].includes(roleCode ?? '')

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

/** 후속 작업은 worker가 10초마다 처리하므로 같은 주기로 다시 조회한다. */
export const FOLLOW_UP_REFETCH_MS = 10_000
export const hasExhaustedFollowUp = (events?: ContractStatusEvent[]) =>
  Boolean(events?.some((event) => event.followUp?.status === 'EXHAUSTED'))
/** 소진된 작업이 있으면 같은 계약의 뒤 사건도 멈추므로 다시 조회해도 바뀌지 않는다. */
export const followUpRunning = (events?: ContractStatusEvent[]) =>
  !hasExhaustedFollowUp(events) &&
  Boolean(events?.some((event) => ['PENDING', 'PROCESSING', 'FAILED'].includes(event.followUp?.status ?? '')))
