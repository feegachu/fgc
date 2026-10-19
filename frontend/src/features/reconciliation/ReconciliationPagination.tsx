export function ReconciliationPagination({
  page,
  totalPages,
  label,
  onPageChange,
}: {
  page: number
  totalPages: number
  label: string
  onPageChange: (page: number) => void
}) {
  if (totalPages <= 1) return null
  const current = Math.max(1, Math.min(totalPages, page))
  return (
    <footer className="reco-react-pagination">
      <nav className="reco-react-pagination-nav" aria-label={label}>
        <button
          type="button"
          className="icon-button"
          aria-label="이전 페이지"
          title="이전 페이지"
          disabled={current === 1}
          onClick={() => onPageChange(current - 1)}
        >
          <span className="material-symbols-rounded" aria-hidden="true">chevron_left</span>
        </button>
        <span className="reco-react-pagination-status tabular-nums" aria-live="polite" aria-atomic="true">
          {current} / {totalPages}
        </span>
        <button
          type="button"
          className="icon-button"
          aria-label="다음 페이지"
          title="다음 페이지"
          disabled={current === totalPages}
          onClick={() => onPageChange(current + 1)}
        >
          <span className="material-symbols-rounded" aria-hidden="true">chevron_right</span>
        </button>
      </nav>
    </footer>
  )
}
