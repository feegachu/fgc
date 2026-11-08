import type { StatusTone } from '../../components/StatusBadge'
import { int } from '../../lib/format'
/** 정책 후보와 정책 상세 머리 모두 내부 번호 대신 정책 코드와 판으로 보인다. */
type PolicyName = { policyName?: string; versionNo?: number }
export const policyLabel = (policy?: PolicyName) => (policy ? `${policy.policyName} v${int(policy.versionNo)}` : '선택한 정책')
export const triggerLabels: Record<string, string> = {
  STATUS_EVENT: '상태 변경',
  ARBITRAGE: '차익거래',
}
export const calculationStatuses = [
  { value: 'UNCALCULATED', label: '미계산', tone: 'neutral' },
  { value: 'CALCULATED', label: '계산완료', tone: 'success' },
  { value: 'REVIEW_REQUIRED', label: '검토필요', tone: 'review' },
] as const satisfies readonly { value: string; label: string; tone: StatusTone }[]
/** 서버 reviewReasons와 계산 중단 사유(IF-API-85 422 reasons) 코드 → 화면 문구. 코드는 화면에 쓰지 않는다(공통규칙 10). */
export const reviewReasonLabels: Record<string, string> = {
  POLICY_MISSING: '유효한 환수 정책 없음',
  POLICY_SELECTION_REQUIRED: '적용 정책이 여러 개라 선택 필요',
  SCHEDULE_MISSING: '연결된 지급 스케줄 없음',
  PAYMENT_INSTALLMENT_MISSING: '지급 회차가 연결되지 않은 지급 있음',
  PAYMENT_SCOPE_MISMATCH: '지급 귀속 범위 불일치',
  STATUS_EVENT_SUPERSEDED: '이후 상태 변경으로 대체됨',
  EVENT_NOT_EFFECTIVE: '변경 효력 전',
  ARBITRAGE_SOURCE_CHANGED: '차익거래 검증 결과 변경',
  CALCULATION_STALE: '계산 후 근거 자료가 바뀌어 다시 계산 필요',
  POLICY_NOT_OFFERED: '선택한 정책이 이 후보의 적용 정책이 아님',
  PAID_ATTRIBUTION_MISSING: '환수할 기지급 내역 없음',
  ELAPSED_MONTH_NOT_COVERED: '경과월에 맞는 환수 규칙 없음',
  CLAWBACK_RULE_AMBIGUOUS: '경과월에 맞는 환수 규칙이 둘 이상',
  BASIS_CODE_UNSUPPORTED: '정책의 기준금액 방식을 계산할 수 없음',
  CONDITION_UNSUPPORTED: '정책의 적용 조건을 계산할 수 없음',
  NO_CLAWBACK_RULE: '환수 규칙이 없는 수수료 항목',
}
export const reasonText = (code: string) => reviewReasonLabels[code] ?? '확인이 필요한 사유'
