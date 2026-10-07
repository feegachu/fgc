import type { FormEvent, ReactNode } from 'react'
import { Button } from './Button'
export function FilterBar({
  children,
  onSubmit,
  onReset,
  actions,
  className = '',
  wrapFields = true,
  resetFirst = false,
}: {
  children: ReactNode
  onSubmit: () => void
  onReset?: () => void
  actions?: ReactNode
  className?: string
  wrapFields?: boolean
  resetFirst?: boolean
}) {
  function submit(event: FormEvent) {
    event.preventDefault()
    onSubmit()
  }
  return (
    <form className={`filter-bar ${className}`} aria-label="조회 조건" onSubmit={submit}>
      {wrapFields ? <div className="filter-fields">{children}</div> : children}
      <div className="filter-actions">
        {!resetFirst && <Button type="submit">조회</Button>}
        {onReset && (
          <Button variant="secondary" onClick={onReset}>
            초기화
          </Button>
        )}
        {resetFirst && <Button type="submit">조회</Button>}
        {actions}
      </div>
    </form>
  )
}
