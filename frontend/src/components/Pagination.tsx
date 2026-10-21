import { Button } from './Button'
export function Pagination({
  page,
  totalPages,
  onPageChange,
  compact = false,
  legacy = false,
  allPages = false,
  label = '페이지 선택',
}: {
  compact?: boolean
  legacy?: boolean
  allPages?: boolean
  label?: string
  page: number
  totalPages: number
  onPageChange: (page: number) => void
}) {
  const last = Math.max(1, totalPages)
  const current = Math.max(1, Math.min(last, page))
  const start = allPages
    ? 1
    : compact
      ? Math.max(1, Math.min(current - 2, last - 4))
      : Math.floor((current - 1) / 5) * 5 + 1
  return (
    <nav className="pagination-controls" aria-label={label}>
      {!compact && !legacy && (
        <Button variant="ghost" disabled={current === 1} onClick={() => onPageChange(1)}>
          처음
        </Button>
      )}
      <button
        type="button"
        className={compact || legacy ? 'pagination-button' : 'button button-ghost'}
        title={legacy ? '이전 5페이지' : undefined}
        aria-label={legacy ? '이전 5페이지' : compact ? '이전 페이지' : undefined}
        disabled={compact ? current === 1 : start === 1}
        onClick={() => onPageChange(compact ? current - 1 : start - 1)}
      >
        {legacy ? (
          <span className="material-symbols-rounded" aria-hidden="true">
            chevron_left
          </span>
        ) : compact ? (
          '‹'
        ) : (
          '이전'
        )}
      </button>
      {Array.from({ length: allPages ? last : Math.min(5, last - start + 1) }, (_, index) => start + index).map(
        (number) =>
          legacy && current === number ? (
            <span className="pagination-button is-active" key={number} aria-current="page">
              {number}
            </span>
          ) : (
            <button
              type="button"
              className={`pagination-button ${current === number ? 'is-active' : ''}`}
              key={number}
              aria-current={current === number ? 'page' : undefined}
              onClick={() => onPageChange(number)}
            >
              {number}
            </button>
          ),
      )}
      <button
        type="button"
        className={compact || legacy ? 'pagination-button' : 'button button-ghost'}
        title={legacy ? '다음 5페이지' : undefined}
        aria-label={legacy ? '다음 5페이지' : compact ? '다음 페이지' : undefined}
        disabled={compact ? current === last : start + 4 >= last}
        onClick={() => onPageChange(compact ? current + 1 : start + 5)}
      >
        {legacy ? (
          <span className="material-symbols-rounded" aria-hidden="true">
            chevron_right
          </span>
        ) : compact ? (
          '›'
        ) : (
          '다음'
        )}
      </button>
      {!compact && !legacy && (
        <Button variant="ghost" disabled={current === last} onClick={() => onPageChange(last)}>
          마지막
        </Button>
      )}
    </nav>
  )
}
