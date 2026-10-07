import { Button } from './Button'
export function Pagination({
  page,
  totalPages,
  onPageChange,
  compact = false,
}: {
  compact?: boolean
  page: number
  totalPages: number
  onPageChange: (page: number) => void
}) {
  const last = Math.max(1, totalPages)
  const current = Math.max(1, Math.min(last, page))
  const start = compact ? Math.max(1, Math.min(current - 2, last - 4)) : Math.floor((current - 1) / 5) * 5 + 1
  return (
    <nav className="pagination-controls" aria-label="페이지 선택">
      {!compact && (
        <Button variant="ghost" disabled={current === 1} onClick={() => onPageChange(1)}>
          처음
        </Button>
      )}
      <button
        type="button"
        className={compact ? 'pagination-button' : 'button button-ghost'}
        aria-label={compact ? '이전 페이지' : undefined}
        disabled={compact ? current === 1 : start === 1}
        onClick={() => onPageChange(compact ? current - 1 : start - 1)}
      >
        {compact ? '‹' : '이전'}
      </button>
      {Array.from({ length: Math.min(5, last - start + 1) }, (_, index) => start + index).map((number) => (
        <button
          type="button"
          className={`pagination-button ${current === number ? 'is-active' : ''}`}
          key={number}
          aria-current={current === number ? 'page' : undefined}
          onClick={() => onPageChange(number)}
        >
          {number}
        </button>
      ))}
      <button
        type="button"
        className={compact ? 'pagination-button' : 'button button-ghost'}
        aria-label={compact ? '다음 페이지' : undefined}
        disabled={compact ? current === last : start + 4 >= last}
        onClick={() => onPageChange(compact ? current + 1 : start + 5)}
      >
        {compact ? '›' : '다음'}
      </button>
      {!compact && (
        <Button variant="ghost" disabled={current === last} onClick={() => onPageChange(last)}>
          마지막
        </Button>
      )}
    </nav>
  )
}
