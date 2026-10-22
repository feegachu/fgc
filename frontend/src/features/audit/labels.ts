import { JOURNAL_TYPE } from '../ledger/labels'

// 감사 이벤트 기록 경로의 코드 표시용 매핑. 저장값과 검색값은 원본 코드를 유지한다.
const actionLabels = {
  LOGIN_SUCCESS: '로그인 성공',
  LOGIN_FAIL: '로그인 실패',
  LOGOUT: '로그아웃',
  LOGIN_SUPERSEDED: '새 로그인으로 기존 로그인 무효화',
  TOKEN_REFRESH: '인증 토큰 갱신',
  REFRESH_REUSE_DETECTED: '갱신 토큰 재사용 탐지',
  CONTRACT_CREATED: '계약 등록',
  CONTRACT_UPDATED: '계약 수정',
  CONTRACT_CAP_RECHECKED: '계약 한도 재검증',
  CONTRACT_SCHEDULES_REGENERATED: '계약 스케줄 재생성',
  PAYMENT_CREATED: '지급 등록',
  PAYMENT_UPDATED: '지급 수정',
  PAYMENT_DELETED: '지급 초안 삭제',
  PAYMENT_CONFIRMED: '지급 확정',
  PAYMENT_CANCELLED: '지급 취소',
  SCHEDULE_CONFIRMED: '스케줄 확정',
  ARBITRAGE_RECHECKED: '차익거래 재검증',
  EXCEPTION_ACTION: '예외 조치',
  JOURNAL_DRAFT_SAVED: '분개 초안 저장',
  JOURNAL_CORRECTION_REQUESTED: '원장 정정 요청',
  JOURNAL_REVERSED: '원분개 역분개',
  JOURNAL_REVERSED_AND_REPOSTED: '원분개 역분개 및 재기표',
  VALIDATION_RUN_STARTED: '검증 실행 시작',
  VALIDATION_RUN_COMPLETED: '검증 실행 완료',
  VALIDATION_RUN_STEP_ADVANCED: '검증 실행 단계 진행',
  VALIDATION_RUN_FAILED: '검증 실행 실패',
  VALIDATION_RUN_CREATION_FAILED: '검증 실행 생성 실패',
  VALIDATION_RUN_RETRIED: '검증 실행 재시도',
  VALIDATION_RUN_FINALIZED: '검증 실행 확정',
} as const

const entityTypeLabels = {
  APP_USER: '사용자 계정',
  CONTRACT: '보험계약',
  COMMISSION_PAYMENT: '수수료 지급',
  COMMISSION_TRANSACTION: '수수료 거래',
  SCHEDULE: '지급 스케줄',
  ARBITRAGE_CHECK: '차익거래 검증',
  VALIDATION_RUN: '검증 실행',
  BATCH_JOB_EXECUTION: '배치 작업 실행',
  JOURNAL_HEADER: '회계전표',
  EXCEPTION_CASE: '예외 건',
  SCHEDULE_LINE: '스케줄행',
} as const

function label(code: string | null | undefined, labels: Record<string, string>) {
  if (code == null || code === '') return '-'
  return Object.hasOwn(labels, code) ? labels[code] : code
}

function withCode(code: string | null | undefined, display: string) {
  return code && display !== code ? `${display} (${code})` : display
}

export const auditActionLabel = (code?: string | null) => label(code, actionLabels)
export const auditEntityTypeLabel = (code?: string | null) => label(code, entityTypeLabels)
export const auditActionWithCode = (code?: string | null) => withCode(code, auditActionLabel(code))
export const auditEntityTypeWithCode = (code?: string | null) => withCode(code, auditEntityTypeLabel(code))

const loginFailureReasons = {
  BadCredentialsException: '아이디 또는 비밀번호 불일치',
  UsernameNotFoundException: '사용자 계정 없음',
  DisabledException: '사용 중지된 계정',
  LockedException: '잠긴 계정',
  AccountExpiredException: '계정 유효기간 만료',
  CredentialsExpiredException: '비밀번호 유효기간 만료',
} as const

/** 알려진 자동 생성 형식만 번역한다. 사용자 사유·오류 원문·미등록 형식은 보존한다. */
export function auditReasonLabel(actionCode?: string | null, reason?: string | null) {
  if (reason == null || reason === '') return '-'
  if (actionCode === 'VALIDATION_RUN_STARTED' && reason === 'MonthlyValidationJob started') return '월 통합검증 시작'
  if (actionCode === 'VALIDATION_RUN_COMPLETED' && reason === 'MonthlyValidationJob completed')
    return '월 통합검증 완료'
  if (
    actionCode === 'VALIDATION_RUN_RETRIED' &&
    reason === 'FAILED validation_run retried (status reverted to RUNNING)'
  )
    return '실패한 월 통합검증 재시도 · 실행 중으로 전환'
  if (actionCode === 'VALIDATION_RUN_STEP_ADVANCED') {
    const step = /^MonthlyValidationJob advanced to step ([0-9]+)$/.exec(reason)
    if (step && step[0] === reason) return `월 통합검증 ${step[1]}단계 진행`
  }
  if (actionCode === 'LOGIN_FAIL' && Object.hasOwn(loginFailureReasons, reason))
    return loginFailureReasons[reason as keyof typeof loginFailureReasons]
  if (actionCode === 'JOURNAL_DRAFT_SAVED') {
    const draft =
      /^journalType=([A-Z_]+),sourceEntityType=([A-Z_]+),sourceEntityId=([0-9]+|null),validationRunId=([0-9]+|null)$/.exec(
        reason,
      )
    if (
      draft &&
      draft[0] === reason &&
      Object.hasOwn(JOURNAL_TYPE, draft[1]) &&
      Object.hasOwn(entityTypeLabels, draft[2])
    ) {
      const type = JOURNAL_TYPE[draft[1] as keyof typeof JOURNAL_TYPE]
      const source = entityTypeLabels[draft[2] as keyof typeof entityTypeLabels]
      const sourceId = draft[3] === 'null' ? ' (식별자 없음)' : ` #${draft[3]}`
      const run = draft[4] === 'null' ? '검증 실행 없음' : `검증 실행 #${draft[4]}`
      return `${type} 분개 초안 저장 · 원천: ${source}${sourceId} · ${run}`
    }
  }
  return reason
}
