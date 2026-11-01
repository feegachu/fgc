import { StatusBadge } from '../../components/StatusBadge'
import { isNegative, won } from '../../lib/format'
import type { CandidateRow } from './api'
import { calculationStatuses } from './labels'
export function CalculationBadge({ row }: { row: CandidateRow }) {
  const status = calculationStatuses.find((item) => item.value === row.calculationStatus)
  return <StatusBadge tone={status?.tone ?? 'neutral'}>{status?.label ?? '자료 없음'}</StatusBadge>
}
/**
 * 환수 계산액. 미계산·검토필요는 금액 칸에 0원을 쓰지 않는다.
 * 계산완료일 때만 서버 금액을 보이며, 그 값이 0이면 실제 0원이다.
 */
export function CalculatedAmount({ row }: { row: CandidateRow }) {
  if (row.calculationStatus === 'UNCALCULATED') return <span className="claw-muted">미계산</span>
  if (row.calculationStatus === 'REVIEW_REQUIRED') return <span className="claw-muted">검토필요</span>
  if (row.calculatedAmount == null) return <span className="claw-muted">자료 없음</span>
  return <Amount value={row.calculatedAmount} />
}
/** 금액 칸 공통. null은 '자료 없음'으로 0원과 구분한다. */
export function Amount({ value }: { value: number | null | undefined }) {
  if (value == null) return <span className="claw-muted">자료 없음</span>
  return <span className={isNegative(value) ? 'is-negative-amount' : undefined}>{won(value)}</span>
}
