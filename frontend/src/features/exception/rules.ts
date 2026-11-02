import type { StatusTone } from '../../components/StatusBadge'
import type { ExceptionStatus } from './api'

// 색 분류는 templates/exception/list.html 의 th:classappend 규칙을 그대로 옮긴 것이다.
export function severityTone(severity?: string): StatusTone {
  if (severity === 'WARNING') return 'warning'
  if (severity === 'INFO') return 'info'
  if (severity === 'HIGH') return 'risk'
  if (severity === 'CRITICAL') return 'error'
  return 'neutral'
}

/** Category colors are separate from severity and the KPI warning colors. */
export function typeBadgeTone(type = ''): StatusTone {
  if (type.startsWith('RECON')) return 'info'
  if (type.startsWith('ARBITRAGE')) return 'review'
  if (type.startsWith('CAP')) return type.includes('WARNING') ? 'warning' : type.includes('REVIEW') ? 'review' : 'error'
  if (type.startsWith('JOURNAL')) return 'warning'
  if (type.startsWith('DATA') || type.startsWith('PRODUCT') || type.startsWith('REFUND')) return 'review'
  return 'neutral'
}

/** 처리 상태(신규, 검토중, 해결, 반려)는 색 없이 쓴다. 위험은 심각도가 알린다(공통 원칙 02). */
export function statusTone(status?: string): StatusTone {
  if (status === 'RESOLVED') return 'success'
  return 'neutral'
}

export const statusOptions = [
  { value: 'OPEN', label: '미처리 (신규 + 검토중)' },
  { value: 'ALL', label: '전체' },
  { value: 'NEW', label: '신규' },
  { value: 'IN_REVIEW', label: '검토중' },
  { value: 'RESOLVED', label: '해결' },
  { value: 'REJECTED', label: '오탐/반려' },
]

export const JOURNAL_CORRECTION_TYPE = 'JOURNAL_CORRECTION_REQUIRED'

/**
 * 조치 유형 표와 상태 전이 규칙. 서버 ExceptionActionType.supports()·isJournalCorrectionGeneralAction() 과
 * 같은 표다 — 화면은 허용되는 조치만 보여 주기 위해 쓰고, 최종 판정은 서버(409 FGC-EXCP-003)가 한다.
 */
const actionTable: { type: string; label: string; from: ExceptionStatus[]; generalForCorrection: boolean }[] = [
  { type: 'ASSIGN', label: '담당 배정', from: ['NEW', 'IN_REVIEW'], generalForCorrection: true },
  { type: 'START_REVIEW', label: '검토 시작', from: ['NEW'], generalForCorrection: true },
  { type: 'CORRECT', label: '정정', from: ['IN_REVIEW'], generalForCorrection: false },
  { type: 'REDUCE', label: '감액', from: ['IN_REVIEW'], generalForCorrection: false },
  { type: 'CANCEL', label: '취소', from: ['IN_REVIEW'], generalForCorrection: false },
  { type: 'DEFER', label: '이연', from: ['IN_REVIEW'], generalForCorrection: false },
  { type: 'RECONCILE_AGAIN', label: '재대사', from: ['IN_REVIEW'], generalForCorrection: false },
  { type: 'FALSE_POSITIVE', label: '오탐', from: ['IN_REVIEW'], generalForCorrection: true },
  { type: 'RESOLVE', label: '해결', from: ['IN_REVIEW'], generalForCorrection: false },
  { type: 'REJECT', label: '반려', from: ['IN_REVIEW'], generalForCorrection: true },
  { type: 'REOPEN', label: '재검토 시작', from: ['REJECTED'], generalForCorrection: true },
  { type: 'COMMENT', label: '의견', from: ['NEW', 'IN_REVIEW'], generalForCorrection: true },
]

export function availableActions(status: string | undefined, exceptionType: string | undefined) {
  return actionTable.filter(
    (action) =>
      action.from.includes(status as ExceptionStatus) &&
      (exceptionType !== JOURNAL_CORRECTION_TYPE || action.generalForCorrection),
  )
}

/** 처리 폼은 종결(RESOLVED)된 예외에는 없다. */
export const isActionable = (status?: string) => status === 'NEW' || status === 'IN_REVIEW' || status === 'REJECTED'
