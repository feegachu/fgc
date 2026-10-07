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
  icons = false,
}: {
  children: ReactNode
  onSubmit: () => void
  onReset?: () => void
  actions?: ReactNode
  className?: string
  wrapFields?: boolean
  resetFirst?: boolean
  icons?: boolean
}) {
  function submit(event: FormEvent) {
    event.preventDefault()
    onSubmit()
  }
  const icon = (name: string) =>
    icons ? (
      <span className="material-symbols-rounded" aria-hidden="true">
        {name}
      </span>
    ) : null
  const submitButton = <Button type="submit">{icon('search')}조회</Button>
  return (
    <form className={`filter-bar ${className}`} aria-label="조회 조건" onSubmit={submit}>
      {wrapFields ? <div className="filter-fields">{children}</div> : children}
      <div className="filter-actions">
        {!resetFirst && submitButton}
        {onReset && (
          <Button variant="secondary" onClick={onReset}>
            {icon('restart_alt')}초기화
          </Button>
        )}
        {resetFirst && submitButton}
        {actions}
      </div>
    </form>
  )
}
