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
  showSubmit = true,
  fieldsClassName = '',
  actionsClassName = '',
}: {
  children: ReactNode
  onSubmit: () => void
  onReset?: () => void
  actions?: ReactNode
  className?: string
  wrapFields?: boolean
  resetFirst?: boolean
  icons?: boolean
  showSubmit?: boolean
  fieldsClassName?: string
  actionsClassName?: string
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
  const submitButton = <Button data-guide-feature="components.filter-bar.button" type="submit">{icon('search')}조회</Button>
  return (
    <form data-guide-feature="components.filter-bar.form" className={`filter-bar ${className}`} aria-label="조회 조건" onSubmit={submit}>
      {wrapFields ? <div className={`filter-fields ${fieldsClassName}`}>{children}</div> : children}
      <div className={`filter-actions ${actionsClassName}`}>
        {showSubmit && !resetFirst && submitButton}
        {onReset && (
          <Button data-guide-feature="components.filter-bar.button-2" variant="secondary" onClick={onReset}>
            {icon('restart_alt')}초기화
          </Button>
        )}
        {showSubmit && resetFirst && submitButton}
        {actions}
      </div>
    </form>
  )
}
