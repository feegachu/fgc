import { dateTime, errorText } from '../../lib/format'

const updatedText = (at: number) => (at ? dateTime(new Date(at).toISOString()) : null)

/** 영역별 갱신 시각. 아직 받은 값이 없으면 시각을 지어내지 않는다. */
export function UpdatedAt({ at }: { at: number }) {
  const text = updatedText(at)
  return text ? <span className="region-updated tabular-nums">갱신 {text}</span> : null
}

/** 한 영역의 오류는 그 영역에 재시도와 갱신 시각만 보인다. 못 불러온 건수를 0으로 바꿔 보이지 않는다. */
export function RegionError({
  error,
  fallback,
  updatedAt,
  busy,
  onRetry,
}: {
  error: Error
  fallback: string
  updatedAt: number
  busy: boolean
  onRetry: () => void
}) {
  const updated = updatedText(updatedAt)
  return (
    <div role="alert" className="empty-state region-error">
      <p>{errorText(error, fallback)}</p>
      <p className="region-updated tabular-nums">{updated ? `마지막 갱신 ${updated}` : '아직 불러온 적이 없습니다.'}</p>
      <button
        data-guide-feature="features.dashboard.dashboard-page.button"
        type="button"
        className="button button-secondary"
        disabled={busy}
        onClick={onRetry}
      >
        다시 시도
      </button>
    </div>
  )
}
