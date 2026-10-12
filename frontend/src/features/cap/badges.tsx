import { Button } from '../../components/Button'
import { EvidenceLink } from '../../components/EvidenceLink'
import { StatusBadge } from '../../components/StatusBadge'
import type { StatusTone } from '../../components/StatusBadge'
import { errorText, isNegative, usageRate, won } from '../../lib/format'
import { ApiError } from '../../lib/api/errors'
import { STATUSES } from './labels'

const tones: Record<string, StatusTone> = {
  NORMAL: 'success',
  WARNING: 'warning',
  VIOLATION: 'error',
  REVIEW_REQUIRED: 'review',
}
export function CapStatus({ code, label }: { code?: string; label?: string }) {
  return (
    <StatusBadge tone={tones[code ?? ''] ?? 'neutral'}>
      {label || STATUSES[code as keyof typeof STATUSES] || code || '—'}
    </StatusBadge>
  )
}
export function Amount({ value }: { value?: number }) {
  return <span className={isNegative(value) ? 'is-negative-amount' : undefined}>{won(value)}</span>
}
/** Number는 시각적 막대 폭에만 사용. 수치 표시와 판정은 서버 값 그대로다. */
export function CapUsage({ value, status, warning }: { value?: string; status?: string; warning?: unknown }) {
  const width = Math.max(0, Math.min(100, Number(value) || 0))
  const warningWidth = typeof warning === 'number' || typeof warning === 'string' ? Number(warning) : NaN
  return (
    <div className="cap-react-usage">
      <span
        className="cap-react-track"
        role="progressbar"
        aria-label="사용률"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={width}
        aria-valuetext={`${usageRate(value)}%`}
      >
        <span className={`cap-react-bar cap-tone-${tones[status ?? ''] ?? 'neutral'}`} style={{ width: `${width}%` }} />
        {Number.isFinite(warningWidth) && (
          <span
            className="cap-react-warning"
            title={`주의 기준 ${usageRate(warning)}%`}
            style={{ left: `${Math.max(0, Math.min(100, warningWidth))}%` }}
          />
        )}
      </span>
      <span className="tabular-nums">{value == null || value === '' ? '—' : `${usageRate(value)}%`}</span>
    </div>
  )
}
/** COMMON-500은 서버 내부 상세 대신 추적 ID만 노출한다. 메시지 비교 분기를 하지 않는다. */
export function CapError({ error, retry }: { error: Error; retry: () => void }) {
  const message =
    error instanceof ApiError && error.code === 'FGC-COMMON-500'
      ? `요청 ID: ${error.requestId ?? '—'}`
      : errorText(error)
  return (
    <div role="alert" className="cap-react-error">
      <p>{message}</p>
      <Button variant="secondary" onClick={retry}>
        다시 시도
      </Button>
    </div>
  )
}
export function CapEvidence() {
  return (
    <EvidenceLink icon label="1,200% 한도 판정 근거">
      <strong>REG-08·09·10·23 · SRC-022 제4-32조⑤·⑭</strong>
      <p>계약별 저장 판정입니다. 해약환급금은 합산하고 준법경영비 공제는 원수사 → GA 단계에만 적용합니다.</p>
    </EvidenceLink>
  )
}
