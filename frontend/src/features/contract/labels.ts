import type { components } from '../../lib/api/schema'

type Schema = components['schemas']

/** 필터 선택지와 응답 라벨 누락 시 대체값. 표시값은 서버의 *Label을 우선한다. */
export const contractStatusLabels = {
  APPLIED: '청약',
  ACTIVE: '정상',
  UNPAID: '미납',
  LAPSED: '실효',
  REVIVED: '부활',
  CANCELLED: '청약철회',
  TERMINATED: '해지',
  MATURED: '만기',
} satisfies Record<Schema['ContractCreateRequest']['contractStatus'], string>

export const paymentCycleLabels = {
  MONTHLY: '월납',
  QUARTERLY: '3개월납',
  SEMI_ANNUAL: '6개월납',
  ANNUAL: '연납',
  SINGLE: '일시납',
  OTHER: '기타',
} satisfies Record<Schema['ContractCreateRequest']['paymentCycleCode'], string>

export const dataOriginLabels = {
  SEED: '시드',
  NORMALIZED_DB: '정규화 DB',
  MANUAL: '수동 입력',
} satisfies Record<NonNullable<Schema['ContractView']['dataOrigin']>, string>

export const capStatusLabels = {
  NORMAL: '정상',
  WARNING: '주의',
  VIOLATION: '위반',
  NOT_APPLICABLE: '적용대상 아님',
  REVIEW_REQUIRED: '검토필요',
} satisfies Record<NonNullable<Schema['ContractView']['capResultStatus']>, string>

/** REVIEW_REQUIRED는 CAP의 검토필요와 차익거래의 자료부족이 서로 다르다. */
export const arbitrageStatusLabels = {
  CLEAR: '이상없음',
  CANDIDATE: '검토대상',
  NOT_APPLICABLE: '적용대상 아님',
  REVIEW_REQUIRED: '자료부족',
} satisfies Record<NonNullable<Schema['ArbitrageCheckView']['resultStatus']>, string>

/** 상태 변경 사유코드(IF-API-12 statusReasonCodes). 서버가 라벨을 주지 않는다. */
export const statusReasonLabels = {
  MANUAL_STATUS_CHANGE: '수기 상태 변경',
} satisfies Record<NonNullable<Schema['ContractDetailResponse']['statusReasonCodes']>[number], string>

/** IF-API-19 응답 followUp.status. 200은 저장 성공이며 후속 처리 완료가 아니다. */
export const followUpStatusLabels = {
  PENDING: '대기 중',
  SUCCEEDED: '완료',
  FAILED: '실패',
  NOT_REQUIRED: '대상 없음',
} satisfies Record<NonNullable<Schema['FollowUp']['status']>, string>

/** 처리 결과는 서버 문자열 코드다. 1차 상세 화면의 표시 규칙을 유지한다. */
export const processingStatusLabels: Record<string, string> = {
  PENDING: '처리 대기',
  RUNNING: '처리 중',
  COMPLETED: '완료',
  SUCCESS: '완료',
  SUCCEEDED: '완료',
  FAILED: '실패',
  SKIPPED: '건너뜀',
}

export function responseLabel(labels: Record<string, string>, code: string | null | undefined, label?: string | null) {
  return label || (code ? labels[code] || code : '—')
}
