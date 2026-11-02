import type { ReactNode } from 'react'
import { int } from '../lib/format'

export function ListHeader({
  children,
  total,
  totalId,
  pageSize,
  pageSizeOptions,
  onPageSizeChange,
  actions,
  className = '',
}: {
  children: ReactNode
  total?: number | null
  totalId?: string
  pageSize?: number
  pageSizeOptions?: readonly number[]
  onPageSizeChange?: (size: number) => void
  actions?: ReactNode
  className?: string
}) {
  return (
    <header className={`surface-header list-header ${className}`}>
      <div className="list-header-main">
        {children}
        {total !== undefined && (
          <span className="list-header-total tabular-nums" aria-live="polite" aria-atomic="true">
            총 {totalId ? <span id={totalId}>{int(total)}</span> : int(total)}건
          </span>
        )}
      </div>
      {(pageSize !== undefined || actions) && (
        <div className="list-header-aside">
          {pageSize !== undefined &&
            (pageSizeOptions && onPageSizeChange ? (
              <label className="list-header-page-size">
                화면
                <select
                  className="select-control"
                  aria-label="화면 행 수"
                  value={pageSize}
                  onChange={(event) => onPageSizeChange(Number(event.target.value))}
                >
                  {pageSizeOptions.map((size) => (
                    <option key={size} value={size}>
                      {int(size)}
                    </option>
                  ))}
                </select>
                행
              </label>
            ) : (
              <span className="list-header-page-size tabular-nums">화면 {int(pageSize)}행</span>
            ))}
          {actions}
        </div>
      )}
    </header>
  )
}
