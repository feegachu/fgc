import type { components } from '../../lib/api/schema'
import { LINE_STATUS } from '../schedule/labels'

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
  SEED: '초기 자료',
  NORMALIZED_DB: '보험사 자료',
  MANUAL: '직접 입력',
} satisfies Record<NonNullable<Schema['ContractView']['dataOrigin']>, string>

/** 상태 사건 출처(source_system). 서버가 라벨을 주지 않는다. FGC_MANUAL은 화면 등록과 수정 모두에 쓴다. */
export const sourceSystemLabels: Record<string, string> = {
  INSURER_FEED: '보험사 자료',
  FGC_MANUAL: '직접 입력',
}

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

/** IF-API-16 followUp.status. 계약 저장 성공과 별개인 후속 처리의 현재 상태다. */
export const eventFollowUpLabels = {
  NOT_REQUIRED: '대상 없음',
  PENDING: '처리 대기',
  PROCESSING: '처리 중',
  FAILED: '실패, 재시도 대기',
  EXHAUSTED: '자동 재시도 소진',
  SUCCEEDED: '완료',
} satisfies Record<NonNullable<Schema['EventFollowUp']['status']>, string>

/** 영향이 0건일 때 그 이유. 조회 오류나 아직 끝나지 않은 처리를 0건으로 읽지 않게 후속 상태별로 다르게 쓴다. */
export const followUpEmptyEffects = {
  NOT_REQUIRED: '이 사건은 후속 처리 대상이 아니라 영향이 없습니다.',
  PENDING: '후속 처리 대기 중입니다. 처리가 끝나면 영향이 표시됩니다.',
  PROCESSING: '후속 처리 중입니다. 처리가 끝나면 영향이 표시됩니다.',
  FAILED: '후속 처리에 실패해 영향이 기록되지 않았습니다. 자동 재시도를 기다립니다.',
  EXHAUSTED: '자동 재시도를 모두 써서 영향이 기록되지 않았습니다. 운영 담당 확인이 필요합니다.',
  SUCCEEDED: '후속 처리를 마쳤고 바뀐 대상이 없습니다.',
} satisfies Record<keyof typeof eventFollowUpLabels, string>

const followUpFailures: Record<string, string> = {
  'FGC-IMPL-POLICY_MISSING': '적용할 정책을 찾지 못했습니다',
}
/** errorCode는 서버 오류 코드 또는 예외 이름이라 화면에 쓰지 않는다(화면정의서 공통규칙 10). 원문은 서버 로그와 처리 이력에 있다. */
export const followUpFailureText = (code?: string | null) =>
  !code ? '기록 없이 중단됨' : (followUpFailures[code] ?? '시스템 오류')

/** 상태 사건 사유코드. 자유 사유가 없는 사건(계약 등록, 보험사 자료)에 대신 보인다. */
export const eventReasonLabels: Record<string, string> = {
  ...statusReasonLabels,
  NEW_CONTRACT: '신규 계약',
  PREMIUM_UNPAID: '보험료 미납',
  LAPSE_BY_UNPAID: '미납 실효',
  SURRENDER: '해지 신청',
}

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

/** 상태 사건 후속 처리 작업 이름. 배치 작업의 원문 이름은 화면에 쓰지 않는다(SRC-074). */
export const processingJobLabels: Record<string, string> = {
  DailyChangedContractJob: '일일 변경 계약 재검증',
  PaymentHoldJob: '지급 보류 처리',
  PaymentResumeJob: '부활 재개 처리',
}

export const paymentStatusLabels: Record<string, string> = {
  DRAFT: '작성중',
  CONFIRMED: '확정',
  CANCELLED: '취소',
}

export const effectEntityLabels: Record<string, string> = { PAYMENT: '지급 건', SCHEDULE_LINE: '스케줄 회차' }
/** IF-API-89 상태. 지급 보류(payment_hold)와 회차 보류, 회차 중단은 다른 일이라 같은 말로 쓰지 않는다. */
const effectStates: Record<string, Record<string, string>> = {
  PAYMENT: { ...paymentStatusLabels, HELD: '지급 보류', RELEASED: '보류 해제' },
  SCHEDULE_LINE: { ...LINE_STATUS, PLANNED: '지급 예정', HOLD: '회차 보류', CANCELLED: '지급 예정 중단' },
}
const effectExclusions: Record<string, string> = {
  ALREADY_CONFIRMED: '이력 보존, 처리 제외',
  MONTH_LOCKED: '이력 보존, 처리 제외',
  NO_RETROACTIVE: '소급 제외',
}
export function effectStateLabel(
  effect: Pick<Schema['ContractStatusEffectResponse'], 'entityType' | 'reasonCode'>,
  state?: string | null,
) {
  if (!state) return '자료 없음'
  if (state === 'EXCLUDED') return effectExclusions[effect.reasonCode ?? ''] ?? '처리 제외'
  return effectStates[effect.entityType ?? '']?.[state] ?? '확인 필요'
}

export function responseLabel(labels: Record<string, string>, code: string | null | undefined, label?: string | null) {
  return label || (code ? labels[code] || '확인 필요' : '-')
}
