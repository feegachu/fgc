import { JOURNAL_TYPE } from '../ledger/labels'
import { contractStatusLabels, dataOriginLabels, paymentCycleLabels } from '../contract/labels'
import { screenMessage } from '../../lib/screenText'

// 감사 이벤트 기록 경로의 코드 표시용 매핑. 저장값과 검색값은 원본 코드를 유지한다.
export const actionLabels = {
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
  ACQUISITION_COST_CHECKED: '계약체결비용 한도 검증',
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
  POLICY_CREATE: '정책 초안 등록',
  POLICY_UPDATE: '정책 초안 수정',
  POLICY_CLONE: '정책 복제',
  POLICY_SUBMIT: '정책 승인 요청',
  POLICY_APPROVE: '정책 승인',
  POLICY_REJECT: '정책 반려',
  POLICY_ACTIVATE: '정책 시행',
  POLICY_RETIRE: '정책 종료',
} as const

export const entityTypeLabels = {
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
  POLICY_VERSION: '정책 버전',
  CLAWBACK_CASE: '환수 건',
} as const

function label(code: string | null | undefined, labels: Record<string, string>) {
  if (code == null || code === '') return '-'
  return Object.hasOwn(labels, code) ? labels[code] : code
}

export const auditActionLabel = (code?: string | null) => label(code, actionLabels)
export const auditEntityTypeLabel = (code?: string | null) => label(code, entityTypeLabels)
export const auditActionWithCode = auditActionLabel
export const auditEntityTypeWithCode = auditEntityTypeLabel

const loginFailureReasons = {
  BadCredentialsException: '아이디 또는 비밀번호 불일치',
  UsernameNotFoundException: '사용자 계정 없음',
  DisabledException: '사용 중지된 계정',
  LockedException: '잠긴 계정',
  AccountExpiredException: '계정 유효기간 만료',
  CredentialsExpiredException: '비밀번호 유효기간 만료',
} as const

/** 지급 초안을 지워 예외를 닫을 때 서버가 남긴 사유. 감사로그와 예외 처리 이력이 같은 문구를 쓴다. */
export function paymentDeletedReason(reason?: string | null) {
  const id = /^초안 삭제\(PAYMENT_DELETED\): 지급 ([0-9]+)의 지급 예정이 철회되어 종결$/.exec(reason ?? '')?.[1]
  return id ? `지급 초안 삭제로 종결, 지급 건 ${id}` : null
}

/** 알려진 자동 생성 형식만 번역한다. 사용자 사유·오류 원문·미등록 형식은 보존한다. */
export function auditReasonLabel(actionCode?: string | null, reason?: string | null) {
  if (reason == null || reason === '') return '-'
  if (actionCode === 'EXCEPTION_ACTION') {
    const deletion = paymentDeletedReason(reason)
    if (deletion) return deletion
  }
  if (actionCode === 'VALIDATION_RUN_STARTED' && reason === 'MonthlyValidationJob started') return '월 통합검증 시작'
  if (actionCode === 'VALIDATION_RUN_COMPLETED' && reason === 'MonthlyValidationJob completed')
    return '월 통합검증 완료'
  if (
    actionCode === 'VALIDATION_RUN_RETRIED' &&
    reason === 'FAILED validation_run retried (status reverted to RUNNING)'
  )
    return '실패한 월 통합검증 재시도, 실행 중으로 전환'
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
      return `${type} 분개 초안 저장, 원천: ${source}${sourceId}, ${run}`
    }
  }
  return /^[a-zA-Z]\w*=/.test(reason) ? reason : screenMessage(reason)
}
/** 배열 인덱스("[0]")만 "1번째"로 풀어 읽기 쉽게 한다("[0].result.details[1].amount" → "1번째.result.details.2번째.amount"). 점 경로는 그대로 두고 원래 경로는 title 로 남긴다. */
export function diffFieldLabel(field: string): string {
  const fields: Record<string, string> = {
    value: '값',
    payment: '지급',
    amount: '금액',
    status: '상태',
    contract: '계약',
    result: '결과',
    details: '상세',
    journal: '분개',
    type: '유형',
    activeYn: '사용 여부',
    agent: '설계사',
    policy: '정책',
    name: '이름',
    reason: '사유',
    before: '이전값',
    after: '이후값',
    expectedAmount: '예상액',
    actualAmount: '실제액',
    differenceAmount: '차액',
    paymentStage: '지급단계',
    // 계약 수정 감사행(ContractServiceImpl.toAuditSnapshot)의 항목
    contractId: '계약 내부 번호',
    insurerId: '보험사 번호',
    productOfferingId: '상품 판매버전 번호',
    contractNo: '계약번호',
    contractDate: '계약일',
    agentId: '설계사 번호',
    organizationId: '조직 번호',
    premiumPerCycleAmount: '주기 보험료',
    firstPremiumAmount: '초회보험료',
    monthlyEquivalentFirstPremium: '월납환산 초회보험료',
    premiumConversionRuleCode: '보험료 환산 규칙',
    paymentCycleCode: '납입주기',
    paymentTermMonths: '납입기간(개월)',
    standardSurrenderDeductionAmount: '표준해약공제액',
    currentStatus: '계약상태',
    dataOrigin: '데이터 출처',
    createdBy: '등록자',
    updatedAt: '수정 시각',
    statusEventId: '상태 변경 번호',
  }
  return field
    .replace(/\[(\d+)]/g, (_, index: string) => `.${Number(index) + 1}번째.`)
    .split('.')
    .filter(Boolean)
    .map((part) => fields[part] ?? part)
    .join(' / ')
}

// 같은 코드라도 항목마다 뜻이 다르다(계약 ACTIVE는 '정상'). 항목 전용 표를 먼저 본다.
const fieldValueLabels: Record<string, Record<string, string>> = {
  currentStatus: contractStatusLabels,
  dataOrigin: dataOriginLabels,
  paymentCycleCode: paymentCycleLabels,
}

export function auditValueLabel(value: string | null | undefined, field?: string) {
  value ??= '-'
  const own = fieldValueLabels[field?.split('.').at(-1) ?? '']
  if (own) return own[value] ?? value
  if (!/(status|type|method|stage|regime|decision|severity|origin)$/i.test(field ?? '')) return value
  const labels: Record<string, string> = {
    CREATED: '생성',
    STARTED: '시작',
    DRAFT: '작성중',
    CONFIRMED: '확정',
    POSTED: '기표됨',
    REVERSED: '역분개됨',
    ACTIVE: '활성',
    INACTIVE: '비활성',
    CANCELLED: '취소',
    NEW: '신규',
    IN_REVIEW: '검토중',
    RESOLVED: '해결',
    REJECTED: '반려',
    COMPLETED: '완료',
    RUNNING: '실행중',
    FAILED: '실패',
    INSURER_TO_GA: '원수사→GA',
    GA_TO_FC: 'GA→설계사',
    RATE: '요율',
    FIXED: '정액',
    INCLUDED: '산입',
    EXCLUDED: '제외',
    REVIEW_REQUIRED: '검토필요',
  }
  return labels[value] ?? value
}
