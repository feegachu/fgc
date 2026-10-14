import { StatusBadge } from '../../components/StatusBadge'
import { isNegative, won } from '../../lib/format'
import { present } from './utils'
import type { ArbitrageRow } from './api'
export function ArbitrageBadge({ row }: { row: ArbitrageRow }) {
  return (
    <StatusBadge
      tone={
        row.resultStatus === 'CLEAR'
          ? 'success'
          : row.resultStatus === 'CANDIDATE'
            ? 'warning'
            : row.resultStatus === 'REVIEW_REQUIRED'
              ? 'review'
              : 'neutral'
      }
    >
      {present(row.resultStatusLabel)}
    </StatusBadge>
  )
}
export function ArbitrageAmount({ value, excess = false }: { value: unknown; excess?: boolean }) {
  return (
    <span
      className={
        isNegative(value) ? 'is-negative-amount' : excess && Number(value) > 0 ? 'arb-amount-error' : undefined
      }
    >
      {won(value)}
    </span>
  )
}
