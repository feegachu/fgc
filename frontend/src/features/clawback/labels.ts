import type { StatusTone } from '../../components/StatusBadge'
export const triggerLabels: Record<string, string> = {
  STATUS_EVENT: '상태 사건',
  ARBITRAGE: '차익거래',
}
export const calculationStatuses = [
  { value: 'UNCALCULATED', label: '미계산', tone: 'neutral' },
  { value: 'CALCULATED', label: '계산완료', tone: 'success' },
  { value: 'REVIEW_REQUIRED', label: '검토필요', tone: 'review' },
] as const satisfies readonly { value: string; label: string; tone: StatusTone }[]
/** 서버 reviewReasons 코드 → 화면 문구. 모르는 코드는 원문 코드를 그대로 보인다. */
export const reviewReasonLabels: Record<string, string> = {
  POLICY_MISSING: '유효한 환수 정책 없음',
  POLICY_SELECTION_REQUIRED: '적용 정책이 여러 개라 선택 필요',
  SCHEDULE_MISSING: '연결된 지급 스케줄 없음',
  PAYMENT_INSTALLMENT_MISSING: '지급 회차가 연결되지 않은 지급 있음',
  PAYMENT_SCOPE_MISMATCH: '지급 귀속 범위 불일치',
  STATUS_EVENT_SUPERSEDED: '이후 상태 사건으로 대체됨',
  EVENT_NOT_EFFECTIVE: '사건 효력 전',
  ARBITRAGE_SOURCE_CHANGED: '차익거래 검증 결과 변경',
  CALCULATION_STALE: '계산 후 원천이 바뀌어 다시 계산 필요',
}
export const reasonText = (code: string) => reviewReasonLabels[code] ?? code
