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
