import type { FormEvent, ReactNode } from 'react'
import { Button } from './Button'
export function FilterBar({
  children,
  onSubmit,
  onReset,
  actions,
}: {
  children: ReactNode
  onSubmit: () => void
  onReset?: () => void
  actions?: ReactNode
}) {
  function submit(event: FormEvent) {
    event.preventDefault()
    onSubmit()
  }
  return (
    <form className="filter-bar" aria-label="조회 조건" onSubmit={submit}>
      <div className="filter-fields">{children}</div>
      <div className="filter-actions">
        <Button type="submit">조회</Button>
        {onReset && (
          <Button variant="secondary" onClick={onReset}>
            초기화
          </Button>
        )}
        {actions}
      </div>
    </form>
  )
}
