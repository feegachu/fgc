import type { ReactNode } from 'react'
import { Pagination } from './Pagination'

export function ListPagination({
  page,
  totalPages,
  label,
  onPageChange,
  className = '',
  controls,
}: {
  page: number
  totalPages: number
  label: string
  onPageChange: (page: number) => void
  className?: string
  controls?: ReactNode
}) {
  if (totalPages <= 1 && !controls) return null
  const current = Math.max(1, Math.min(totalPages, page))
  return (
    <footer className={`list-pagination ${className}`}>
      {controls && <div className="list-pagination-options">{controls}</div>}
      {totalPages > 1 && (
        <div className="list-pagination-group">
          <span className="list-pagination-status tabular-nums" aria-live="polite" aria-atomic="true">
            {current} / {totalPages} 페이지
          </span>
          <Pagination legacy page={current} totalPages={totalPages} label={label} onPageChange={onPageChange} />
        </div>
      )}
    </footer>
  )
}
