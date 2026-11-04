import { CellDisclosure } from '../../components/CellDisclosure'
import { AnimatedNumber } from '../../components/AnimatedNumber'
import { StatusBadge } from '../../components/StatusBadge'
import { ApiError } from '../../lib/api/errors'
import { errorText, internalErrorText, isNegative } from '../../lib/format'
import { amountText } from './amounts'
import type { Amount } from './amounts'
import { RESULT_TYPE, text } from './labels'
export function Disclosure({ value }: { value: unknown }) {
  return <CellDisclosure singleLine preview={text(value)} full={text(value)} />
}
export function Money({
  value,
  difference = false,
  unit = true,
  animate = false,
}: {
  value?: Amount
  difference?: boolean
  unit?: boolean
  animate?: boolean
}) {
  const formatted = amountText(value, unit)
  const numeric = Number(value)
  const canAnimateAmount = animate && Number.isSafeInteger(numeric) && formatted === amountText(numeric, unit)
  return (
    <span
      className={`${isNegative(value) ? 'is-negative-amount' : ''} ${difference && value != null && Number(value) !== 0 ? 'reco-react-difference' : ''}`}
    >
      {canAnimateAmount
        ? <AnimatedNumber value={numeric} format={(amount) => amountText(Math.round(amount), unit)} />
        : formatted}
    </span>
  )
}
export function ResultBadge({ type, label }: { type?: string; label?: string }) {
  return (
    <StatusBadge
      optionCount={Object.keys(RESULT_TYPE).length}
      kind="category"
      tone={type === 'MATCHED' ? 'success' : type === 'REVIEW_REQUIRED' ? 'review' : 'error'}
    >
      {label || RESULT_TYPE[type as keyof typeof RESULT_TYPE] || text(type)}
    </StatusBadge>
  )
}
export function Failure({ error }: { error: unknown }) {
  if (!error) return null
  return (
    <p role="alert" className="guidance guidance-warning reco-react-guidance">
      {error instanceof ApiError
        ? error.code === 'FGC-COMMON-500'
          ? internalErrorText(error.requestId)
          : errorText(error)
        : errorText(error instanceof Error ? error : null)}
    </p>
  )
}
